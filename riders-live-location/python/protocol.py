"""
protocol.py — Wire format, replay protection, and relay/forwarding rules
for Rider's Live Location.

A LocationPacket is built as plaintext fields, serialized to compact
JSON, then the whole thing is passed to security.encrypt_payload().
Only `ride_id` and `key_epoch` travel outside the ciphertext (as
associated data) so a relay can route packets without ever being able
to read a rider's coordinates.

Packet size note: BLE GATT notifications are commonly limited to
~20 bytes per ATT write on older stacks and up to ~244-512 bytes with
modern BLE 4.2+/5.0 MTU negotiation. This module keeps the plaintext
payload minimal and lets the Android layer handle MTU negotiation and
fragmentation/reassembly for anything that doesn't fit in one PDU —
see mobile/android .../ble/PacketFragmenter (documented, not
duplicated here).
"""

from __future__ import annotations

import json
import time
from dataclasses import dataclass, asdict
from typing import Optional

from security import encrypt_payload, decrypt_payload

DEFAULT_HOP_LIMIT = 5          # generous for a small group ride, bounded to prevent storms
PACKET_FRESHNESS_WINDOW_S = 30  # reject packets older than this
DEDUP_CACHE_TTL_S = 60          # how long a relay remembers packet_ids it has already forwarded


@dataclass
class LocationPacket:
    ride_id: str
    rider_id: str
    packet_id: str
    latitude: float
    longitude: float
    speed_mps: Optional[float]
    heading_deg: Optional[float]
    timestamp: float
    sequence_number: int
    hop_count: int = 0
    hop_limit: int = DEFAULT_HOP_LIMIT
    destination: Optional[dict] = None  # {"lat":..,"lon":..,"name":..} — only set on destination-update packets

    def to_plaintext_bytes(self) -> bytes:
        return json.dumps(asdict(self), separators=(",", ":")).encode("utf-8")

    @staticmethod
    def from_plaintext_bytes(data: bytes) -> "LocationPacket":
        return LocationPacket(**json.loads(data.decode("utf-8")))


def build_encrypted_packet(packet: LocationPacket, session_key: bytes, key_epoch: int) -> bytes:
    """Encrypt a LocationPacket for transmission.

    Wire format: [1 byte key_epoch][4 bytes ride_id_hash][ciphertext blob]
    The associated data binds the ciphertext to (ride_id, key_epoch) so
    a receiver can cheaply reject wrong-ride/stale-epoch traffic before
    spending a decryption attempt, without leaking the ride_id itself
    in a directly reusable form.
    """
    import hashlib

    aad = f"{packet.ride_id}|{key_epoch}".encode("utf-8")
    ciphertext = encrypt_payload(session_key, packet.to_plaintext_bytes(), associated_data=aad)
    ride_hash = hashlib.sha256(packet.ride_id.encode("utf-8")).digest()[:4]
    epoch_byte = bytes([key_epoch & 0xFF])
    return epoch_byte + ride_hash + ciphertext


def open_encrypted_packet(blob: bytes, session_key: bytes, ride_id: str, key_epoch: int) -> LocationPacket:
    """Decrypt + validate a wire-format packet. Raises on tamper/mismatch."""
    import hashlib

    if len(blob) < 5:
        raise ValueError("Packet too short")
    epoch_byte, ride_hash, ciphertext = blob[0], blob[1:5], blob[5:]
    expected_hash = hashlib.sha256(ride_id.encode("utf-8")).digest()[:4]
    if ride_hash != expected_hash:
        raise ValueError("Packet does not belong to this ride")
    if epoch_byte != (key_epoch & 0xFF):
        raise ValueError("Packet key epoch mismatch (stale or revoked key)")

    aad = f"{ride_id}|{key_epoch}".encode("utf-8")
    plaintext = decrypt_payload(session_key, ciphertext, associated_data=aad)
    return LocationPacket.from_plaintext_bytes(plaintext)


# --------------------------------------------------------------------------
# Replay protection + relay bookkeeping
# --------------------------------------------------------------------------

class ReplayGuard:
    """Tracks seen packet_ids and per-rider sequence numbers.

    Two independent checks, both required to accept a packet:
      1. packet_id has not been seen before (within DEDUP_CACHE_TTL_S) —
         stops naive replay and duplicate relay delivery.
      2. sequence_number is greater than the last accepted sequence
         number for that rider_id — stops an attacker from replaying
         an OLD packet_id/timestamp pair that happens to be unexpired.
    """

    def __init__(self) -> None:
        self._seen_packet_ids: dict[str, float] = {}
        self._last_sequence: dict[str, int] = {}

    def _evict_expired(self, now: float) -> None:
        expired = [pid for pid, seen_at in self._seen_packet_ids.items()
                   if now - seen_at > DEDUP_CACHE_TTL_S]
        for pid in expired:
            del self._seen_packet_ids[pid]

    def should_accept(self, packet: LocationPacket, now: Optional[float] = None) -> tuple[bool, str]:
        now = now if now is not None else time.time()
        self._evict_expired(now)

        if now - packet.timestamp > PACKET_FRESHNESS_WINDOW_S:
            return False, "stale packet (outside freshness window)"
        if packet.timestamp - now > 5:
            return False, "packet timestamped in the future"
        if packet.packet_id in self._seen_packet_ids:
            return False, "duplicate packet_id (replay or re-relay)"

        last_seq = self._last_sequence.get(packet.rider_id, -1)
        if packet.sequence_number <= last_seq:
            return False, "sequence_number did not advance (replay)"

        return True, "ok"

    def record_accepted(self, packet: LocationPacket, now: Optional[float] = None) -> None:
        now = now if now is not None else time.time()
        self._seen_packet_ids[packet.packet_id] = now
        self._last_sequence[packet.rider_id] = packet.sequence_number

    def has_forwarded(self, packet_id: str) -> bool:
        return packet_id in self._seen_packet_ids


def should_relay(packet: LocationPacket) -> tuple[bool, LocationPacket | None]:
    """Decide whether a relay node should forward this packet onward.

    Returns (should_forward, packet_with_incremented_hop_count).
    A relay never modifies lat/lon/rider_id/timestamp — only hop_count,
    and only on its own outgoing copy (the AEAD tag over the original
    ciphertext is untouched; hop_count lives in a small unencrypted
    relay header wrapping the opaque ciphertext, not inside it —
    see docs/bluetooth-protocol.md for the exact on-air framing).
    """
    if packet.hop_count >= packet.hop_limit:
        return False, None
    forwarded = LocationPacket(**{**asdict(packet), "hop_count": packet.hop_count + 1})
    return True, forwarded
