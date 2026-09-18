package com.riderslive.ble

import com.riderslive.security.CryptoManager
import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * PacketFramer — turns an encrypted LocationPacket blob (as produced by
 * CryptoManager.encryptPayload, matching protocol.build_encrypted_packet)
 * into one or more BLE GATT writes, and reassembles them on the other end.
 *
 * Why this exists: BLE GATT payload per write is bounded by the
 * negotiated MTU. A default, unnegotiated BLE connection gives ~20
 * usable bytes per ATT write; after requesting a larger MTU (this app
 * requests 247, the practical ceiling on most modern stacks) a single
 * write can usually carry the whole encrypted packet, but fragmentation
 * must always be implemented because MTU negotiation can fail, be
 * refused by the peer's stack, or the packet may include a destination
 * update payload that pushes it over the limit anyway.
 *
 * Frame header layout (5 bytes) + payload chunk:
 *   [1 byte  fragment_index]
 *   [1 byte  fragment_count]
 *   [1 byte  hop_count]        -- mutable per-hop, NOT covered by the AEAD tag
 *   [2 bytes total_length_hint]
 *   [N bytes chunk of the ciphertext blob]
 *
 * hop_count lives OUTSIDE the encrypted+authenticated blob on purpose:
 * a relay must be able to update it (and only it) without needing the
 * session key, and without invalidating the AEAD tag over the actual
 * location fields. See protocol.should_relay in the Python reference.
 */
object PacketFramer {

    private const val HEADER_SIZE = 5
    const val DEFAULT_CHUNK_SIZE = 240 // leaves headroom under a 247-byte negotiated MTU

    data class Fragment(val bytes: ByteArray)

    fun fragment(encryptedBlob: ByteArray, initialHopCount: Int, chunkSize: Int = DEFAULT_CHUNK_SIZE): List<Fragment> {
        val chunks = encryptedBlob.toList().chunked(chunkSize)
        val fragmentCount = chunks.size.coerceAtLeast(1)
        return chunks.mapIndexed { index, chunk ->
            val header = ByteBuffer.allocate(HEADER_SIZE)
            header.put(index.toByte())
            header.put(fragmentCount.toByte())
            header.put(initialHopCount.toByte())
            header.putShort(encryptedBlob.size.toShort())
            Fragment(header.array() + chunk.toByteArray())
        }
    }

    /** Bumps only the hop_count byte of an already-built fragment, leaving
     * everything else — including the AEAD ciphertext bytes — untouched. */
    fun withIncrementedHop(fragment: Fragment): Fragment {
        val bytes = fragment.bytes.copyOf()
        val currentHop = bytes[2].toInt() and 0xFF
        bytes[2] = (currentHop + 1).toByte()
        return Fragment(bytes)
    }

    fun readHopCount(fragment: Fragment): Int = fragment.bytes[2].toInt() and 0xFF

    class Reassembler {
        private val buffers = HashMap<String, Array<ByteArray?>>()

        /** Key fragments by the sender's connection/session id so concurrent
         * senders on the same relay hop don't interleave into garbage. */
        fun accept(senderKey: String, fragment: Fragment): ByteArray? {
            val bytes = fragment.bytes
            val index = bytes[0].toInt() and 0xFF
            val count = bytes[1].toInt() and 0xFF
            val chunk = bytes.copyOfRange(HEADER_SIZE, bytes.size)

            val slots = buffers.getOrPut(senderKey) { arrayOfNulls(count) }
            if (slots.size != count) {
                // Sender restarted a transfer mid-stream; reset.
                buffers[senderKey] = arrayOfNulls(count)
            }
            buffers[senderKey]!![index] = chunk

            val current = buffers[senderKey]!!
            if (current.all { it != null }) {
                val assembled = current.fold(ByteArray(0)) { acc, part -> acc + (part ?: ByteArray(0)) }
                buffers.remove(senderKey)
                return assembled
            }
            return null
        }
    }

    /** Simple CRC32 sanity check usable before spending a decryption
     * attempt on a reassembled blob — NOT a security mechanism, purely
     * to cheaply drop obviously-corrupt-in-transit BLE frames. The AEAD
     * tag inside the ciphertext is what actually guarantees integrity. */
    fun quickChecksum(bytes: ByteArray): Long {
        val crc = CRC32()
        crc.update(bytes)
        return crc.value
    }
}
