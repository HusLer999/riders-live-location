import sys, os, time
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import pytest
from cryptography.exceptions import InvalidTag

import security as sec
import protocol as proto


def make_packet(rider_id="RIDER-AAAA", seq=1, ts=None, ride_id="ride-1"):
    return proto.LocationPacket(
        ride_id=ride_id,
        rider_id=rider_id,
        packet_id=sec.generate_packet_id(),
        latitude=27.7, longitude=84.4,
        speed_mps=5.0, heading_deg=90.0,
        timestamp=ts if ts is not None else time.time(),
        sequence_number=seq,
    )


def test_encrypt_decrypt_packet_roundtrip():
    key = os.urandom(32)
    packet = make_packet()
    blob = proto.build_encrypted_packet(packet, key, key_epoch=0)
    recovered = proto.open_encrypted_packet(blob, key, ride_id="ride-1", key_epoch=0)
    assert recovered.latitude == packet.latitude
    assert recovered.rider_id == packet.rider_id
    assert recovered.packet_id == packet.packet_id


def test_wrong_ride_id_rejected():
    key = os.urandom(32)
    packet = make_packet(ride_id="ride-1")
    blob = proto.build_encrypted_packet(packet, key, key_epoch=0)
    with pytest.raises(ValueError, match="does not belong to this ride"):
        proto.open_encrypted_packet(blob, key, ride_id="ride-2", key_epoch=0)


def test_stale_key_epoch_rejected():
    key = os.urandom(32)
    packet = make_packet()
    blob = proto.build_encrypted_packet(packet, key, key_epoch=1)
    with pytest.raises(ValueError, match="key epoch mismatch"):
        proto.open_encrypted_packet(blob, key, ride_id="ride-1", key_epoch=2)


def test_tampered_packet_bytes_rejected():
    key = os.urandom(32)
    packet = make_packet()
    blob = bytearray(proto.build_encrypted_packet(packet, key, key_epoch=0))
    blob[-1] ^= 0xFF
    with pytest.raises(InvalidTag):
        proto.open_encrypted_packet(bytes(blob), key, ride_id="ride-1", key_epoch=0)


def test_replay_guard_accepts_fresh_packet():
    guard = proto.ReplayGuard()
    packet = make_packet(seq=1)
    ok, _ = guard.should_accept(packet)
    assert ok
    guard.record_accepted(packet)


def test_replay_guard_rejects_duplicate_packet_id():
    guard = proto.ReplayGuard()
    packet = make_packet(seq=1)
    guard.should_accept(packet)
    guard.record_accepted(packet)

    ok, reason = guard.should_accept(packet)
    assert not ok
    assert "duplicate" in reason


def test_replay_guard_rejects_non_increasing_sequence():
    guard = proto.ReplayGuard()
    p1 = make_packet(seq=5)
    guard.should_accept(p1)
    guard.record_accepted(p1)

    p2 = make_packet(seq=5)  # same rider, same/lower sequence number, different packet_id
    ok, reason = guard.should_accept(p2)
    assert not ok
    assert "sequence_number" in reason


def test_replay_guard_rejects_stale_timestamp():
    guard = proto.ReplayGuard()
    old_packet = make_packet(seq=1, ts=time.time() - 120)
    ok, reason = guard.should_accept(old_packet)
    assert not ok
    assert "stale" in reason


def test_replay_guard_allows_independent_riders():
    guard = proto.ReplayGuard()
    p1 = make_packet(rider_id="RIDER-AAAA", seq=1)
    p2 = make_packet(rider_id="RIDER-BBBB", seq=1)
    ok1, _ = guard.should_accept(p1)
    guard.record_accepted(p1)
    ok2, _ = guard.should_accept(p2)
    assert ok1 and ok2


def test_should_relay_increments_hop_count():
    packet = make_packet()
    should, forwarded = proto.should_relay(packet)
    assert should
    assert forwarded.hop_count == 1
    assert forwarded.latitude == packet.latitude  # relay never alters coordinates


def test_should_relay_stops_at_hop_limit():
    packet = make_packet()
    packet.hop_count = packet.hop_limit
    should, forwarded = proto.should_relay(packet)
    assert not should
    assert forwarded is None
