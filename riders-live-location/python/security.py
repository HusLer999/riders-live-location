"""
security.py — Cryptographic core for Rider's Live Location.

Design rules this module follows (see docs/security.md for the full
threat model):

  * Never invent cryptographic primitives. Everything here is a thin,
    careful wrapper around the Python `cryptography` package (which
    wraps OpenSSL). On Android, the equivalent calls are made against
    Android Keystore / Jetpack Security + javax.crypto, not this file
    directly — this module is the reference implementation and is
    also used by the Python-side test suite / desktop tooling.
  * The human-readable ride code is ONLY a lookup/entry mechanism.
    It is never used directly as an encryption key. It is fed into
    an X25519 + HKDF handshake to derive the real session key.
  * All location packets are protected with AES-256-GCM (AEAD):
    confidentiality + integrity + authenticity in one step.
  * Keys live only in memory for as long as the ride is active.
    Callers on Android must store the wrapped keys in the Android
    Keystore-backed EncryptedSharedPreferences, never in plain
    SharedPreferences or source code.
"""

from __future__ import annotations

import os
import secrets
import string
import time
from dataclasses import dataclass, field
from typing import Optional

from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric.x25519 import (
    X25519PrivateKey,
    X25519PublicKey,
)

# --------------------------------------------------------------------------
# Ride code generation
# --------------------------------------------------------------------------

# Digits-only alphabet by default (fastest to key in on a glove-covered
# phone screen at a fuel stop); an alphanumeric alphabet is offered for
# callers that want a larger keyspace without a longer code.
_DIGIT_ALPHABET = string.digits
_ALNUM_ALPHABET = string.ascii_uppercase.replace("O", "").replace("I", "") + string.digits


def generate_ride_code(length: int = 6, alphanumeric: bool = False) -> str:
    """Generate a cryptographically secure, human-shareable ride code.

    Uses `secrets`, which is seeded from the OS CSPRNG
    (`os.urandom` / `getrandom(2)`), never `random` and never a
    timestamp. The code is short-lived and is NOT the cryptographic
    secret protecting the ride — see `perform_join_handshake` below.
    """
    if length < 4:
        raise ValueError("Ride codes shorter than 4 characters are not permitted")
    alphabet = _ALNUM_ALPHABET if alphanumeric else _DIGIT_ALPHABET
    return "".join(secrets.choice(alphabet) for _ in range(length))


def generate_rider_id() -> str:
    """Generate a random, non-identifying rider ID, e.g. RIDER-7F31."""
    suffix = secrets.token_hex(2).upper()
    return f"RIDER-{suffix}"


def generate_packet_id() -> str:
    """128-bit random packet identifier, used for dedup + replay protection."""
    return secrets.token_hex(16)


# --------------------------------------------------------------------------
# Key establishment (per-ride, per-participant)
# --------------------------------------------------------------------------
#
# Flow:
#   1. Primary rider creates a ride -> generates an X25519 keypair
#      ("ride creator keypair") and the short ride code.
#   2. Ride code + creator's public key are shared out-of-band
#      (typed in, or via QR — see docs/bluetooth-protocol.md for why
#      the QR payload only ever carries a public key + code, never a
#      private key).
#   3. Joining rider generates their OWN ephemeral X25519 keypair,
#      does a Diffie-Hellman exchange with the creator's public key,
#      and both sides run the shared secret through HKDF (with the
#      ride code + ride ID mixed in as context, NOT as the secret
#      itself) to derive a 256-bit AES-GCM session key.
#
# This means: knowing the 6-8 digit ride code alone is NOT enough to
# decrypt traffic captured over the air. An eavesdropper would also
# need to observe/complete the X25519 exchange for that specific
# session, and the derived key changes every time keys are rotated
# (see rotate_session_key).


@dataclass
class RideKeyMaterial:
    """Ephemeral key state for one participant's view of one ride."""

    private_key: X25519PrivateKey = field(default_factory=X25519PrivateKey.generate)
    session_key: Optional[bytes] = None      # 32-byte AES-256-GCM key
    key_epoch: int = 0                       # bumped on every rotation/revocation
    created_at: float = field(default_factory=time.time)

    def public_bytes(self) -> bytes:
        return self.private_key.public_key().public_bytes_raw()


def derive_session_key(
    private_key: X25519PrivateKey,
    peer_public_bytes: bytes,
    ride_id: str,
    ride_code: str,
) -> bytes:
    """Run X25519 ECDH + HKDF-SHA256 to derive a 32-byte AES key.

    The ride code and ride ID are mixed into the HKDF `info` field as
    context binding (so a key derived for ride A can't be replayed
    against ride B) — the code itself never becomes key material on
    its own, and is not sufficient (by itself) to derive the key.
    """
    peer_public_key = X25519PublicKey.from_public_bytes(peer_public_bytes)
    shared_secret = private_key.exchange(peer_public_key)

    hkdf = HKDF(
        algorithm=hashes.SHA256(),
        length=32,
        salt=ride_id.encode("utf-8"),
        info=f"riders-live-location|v1|{ride_code}".encode("utf-8"),
    )
    return hkdf.derive(shared_secret)


def rotate_session_key(current: RideKeyMaterial, peer_public_bytes: bytes,
                        ride_id: str, ride_code: str) -> RideKeyMaterial:
    """Produce fresh key material for key rotation / post-revocation re-key.

    A fresh ephemeral X25519 keypair is generated, so old captured
    ciphertext cannot be decrypted even if a later shared secret were
    somehow exposed (forward secrecy across rotations). This is what
    `ride_manager.remove_participant` calls after removing someone.
    """
    fresh = RideKeyMaterial(key_epoch=current.key_epoch + 1)
    fresh.session_key = derive_session_key(fresh.private_key, peer_public_bytes, ride_id, ride_code)
    return fresh


# --------------------------------------------------------------------------
# Authenticated encryption for location packets
# --------------------------------------------------------------------------

NONCE_SIZE = 12  # 96-bit nonce, standard for AES-GCM


def encrypt_payload(session_key: bytes, plaintext: bytes, associated_data: bytes = b"") -> bytes:
    """Encrypt+authenticate `plaintext` with AES-256-GCM.

    Returns nonce || ciphertext_with_tag. `associated_data` (e.g. the
    ride_id + key_epoch) is authenticated but not encrypted, so a
    receiver can cheaply reject packets from the wrong ride/epoch
    before attempting decryption.
    """
    if len(session_key) != 32:
        raise ValueError("AES-256-GCM requires a 32-byte key")
    aesgcm = AESGCM(session_key)
    nonce = os.urandom(NONCE_SIZE)
    ciphertext = aesgcm.encrypt(nonce, plaintext, associated_data)
    return nonce + ciphertext


def decrypt_payload(session_key: bytes, blob: bytes, associated_data: bytes = b"") -> bytes:
    """Reverse of encrypt_payload. Raises InvalidTag on any tampering."""
    if len(blob) < NONCE_SIZE:
        raise ValueError("Ciphertext blob too short to contain a nonce")
    nonce, ciphertext = blob[:NONCE_SIZE], blob[NONCE_SIZE:]
    aesgcm = AESGCM(session_key)
    return aesgcm.decrypt(nonce, ciphertext, associated_data)


# --------------------------------------------------------------------------
# Movement plausibility check (anti-spoofing heuristic, NOT an accusation)
# --------------------------------------------------------------------------

EARTH_RADIUS_M = 6_371_000.0
# Generous ceiling: fast highway riding + GPS jitter margin.
MAX_PLAUSIBLE_SPEED_MPS = 90.0  # ~324 km/h


def is_plausible_movement(
    prev_lat: float, prev_lon: float, prev_ts: float,
    new_lat: float, new_lon: float, new_ts: float,
) -> bool:
    """Cheap sanity check used by protocol.py before accepting a fix.

    This is a heuristic, not a security boundary — it exists to flag
    "Location data may be inaccurate" in the UI, never to silently
    drop data or accuse a user of cheating.
    """
    import math

    dt = new_ts - prev_ts
    if dt <= 0:
        return False  # out-of-order or duplicate timestamp

    lat1, lon1, lat2, lon2 = map(math.radians, (prev_lat, prev_lon, new_lat, new_lon))
    dlat, dlon = lat2 - lat1, lon2 - lon1
    a = math.sin(dlat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(dlon / 2) ** 2
    distance_m = 2 * EARTH_RADIUS_M * math.asin(math.sqrt(a))

    speed_mps = distance_m / dt
    return speed_mps <= MAX_PLAUSIBLE_SPEED_MPS
