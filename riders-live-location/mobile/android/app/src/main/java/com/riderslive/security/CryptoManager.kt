package com.riderslive.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * CryptoManager — Android-side mirror of python/security.py.
 *
 * Mirrors the exact same protocol decisions as the Python reference
 * implementation so a phone running this code and the Python test
 * suite agree on wire format:
 *   - X25519 ECDH + HKDF-SHA256 for per-ride session key derivation.
 *   - AES-256-GCM (AEAD) for every location packet.
 *   - The human ride code is folded into HKDF `info`, never used
 *     directly as key material.
 *
 * Session keys are held only in memory for the life of the ride.
 * Nothing cryptographic is ever written to plain SharedPreferences —
 * the one thing that IS persisted (this device's own long-lived
 * identity keypair, used to resume a paused ride) goes through
 * EncryptedSharedPreferences, which itself is backed by a key that
 * never leaves the Android Keystore's secure hardware.
 */
class CryptoManager(context: Context) {

    private val secureRandom = SecureRandom()

    // -- Keystore-backed storage for anything that must persist -----------

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs = EncryptedSharedPreferences.create(
        context,
        "riders_live_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    // -- X25519 keypair generation -----------------------------------------

    data class KeyPairRaw(val privateKey: ByteArray, val publicKey: ByteArray)

    fun generateEphemeralKeyPair(): KeyPairRaw {
        val generator = X25519KeyPairGenerator()
        generator.init(X25519KeyGenerationParameters(secureRandom))
        val keyPair = generator.generateKeyPair()
        val priv = keyPair.private as X25519PrivateKeyParameters
        val pub = keyPair.public as X25519PublicKeyParameters
        return KeyPairRaw(priv.encoded, pub.encoded)
    }

    /**
     * Runs X25519 ECDH then HKDF-SHA256 with the ride_id as salt and
     * "riders-live-location|v1|<ride_code>" as info — identical
     * construction to security.derive_session_key in the Python
     * reference implementation.
     */
    fun deriveSessionKey(
        privateKeyBytes: ByteArray,
        peerPublicKeyBytes: ByteArray,
        rideId: String,
        rideCode: String,
    ): ByteArray {
        val priv = X25519PrivateKeyParameters(privateKeyBytes, 0)
        val pub = X25519PublicKeyParameters(peerPublicKeyBytes, 0)
        val agreement = X25519Agreement()
        agreement.init(priv)
        val sharedSecret = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(pub, sharedSecret, 0)

        return hkdfSha256(
            salt = rideId.toByteArray(Charsets.UTF_8),
            ikm = sharedSecret,
            info = "riders-live-location|v1|$rideCode".toByteArray(Charsets.UTF_8),
            outputLength = 32,
        )
    }

    private fun hkdfSha256(salt: ByteArray, ikm: ByteArray, info: ByteArray, outputLength: Int): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        // Extract
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        // Expand
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        var t = ByteArray(0)
        val okm = ByteArrayBuilder()
        var counter = 1
        while (okm.size() < outputLength) {
            mac.reset()
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            okm.append(t)
            counter++
        }
        return okm.build().copyOf(outputLength)
    }

    private class ByteArrayBuilder {
        private val chunks = mutableListOf<ByteArray>()
        private var total = 0
        fun append(b: ByteArray) { chunks.add(b); total += b.size }
        fun size() = total
        fun build(): ByteArray {
            val out = ByteArray(total)
            var offset = 0
            for (c in chunks) { c.copyInto(out, offset); offset += c.size }
            return out
        }
    }

    // -- AES-256-GCM AEAD, matches security.encrypt_payload/decrypt_payload --

    companion object {
        private const val NONCE_SIZE = 12
        private const val GCM_TAG_BITS = 128
    }

    fun encryptPayload(sessionKey: ByteArray, plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        require(sessionKey.size == 32) { "AES-256-GCM requires a 32-byte key" }
        val nonce = ByteArray(NONCE_SIZE).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sessionKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(associatedData)
        val ciphertext = cipher.doFinal(plaintext)
        return nonce + ciphertext
    }

    fun decryptPayload(sessionKey: ByteArray, blob: ByteArray, associatedData: ByteArray): ByteArray {
        require(blob.size > NONCE_SIZE) { "Ciphertext blob too short to contain a nonce" }
        val nonce = blob.copyOfRange(0, NONCE_SIZE)
        val ciphertext = blob.copyOfRange(NONCE_SIZE, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(sessionKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(associatedData)
        return cipher.doFinal(ciphertext) // throws AEADBadTagException on tamper — caller must not swallow it
    }

    /** Secure deletion: overwrite then drop the reference. The JVM/GC does
     * not guarantee zeroing, but this bounds the window a key survives
     * in the managed heap after a ride ends or a rider is revoked. */
    fun wipe(key: ByteArray) {
        key.fill(0)
    }
}
