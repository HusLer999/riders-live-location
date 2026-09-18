import sys, os, time
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import pytest
from cryptography.exceptions import InvalidTag

import security as sec


def test_ride_code_length_and_alphabet():
    code = sec.generate_ride_code(length=6)
    assert len(code) == 6
    assert code.isdigit()


def test_ride_code_is_random():
    codes = {sec.generate_ride_code() for _ in range(200)}
    assert len(codes) > 190  # collisions astronomically unlikely


def test_ride_code_rejects_too_short():
    with pytest.raises(ValueError):
        sec.generate_ride_code(length=3)


def test_rider_id_format():
    rid = sec.generate_rider_id()
    assert rid.startswith("RIDER-")
    assert len(rid) == len("RIDER-") + 4


def test_x25519_handshake_produces_matching_keys():
    ride_id, code = "ride-abc", "583921"
    alice = sec.RideKeyMaterial()
    bob = sec.RideKeyMaterial()

    alice_key = sec.derive_session_key(alice.private_key, bob.public_bytes(), ride_id, code)
    bob_key = sec.derive_session_key(bob.private_key, alice.public_bytes(), ride_id, code)

    assert alice_key == bob_key
    assert len(alice_key) == 32


def test_different_ride_code_yields_different_key():
    alice = sec.RideKeyMaterial()
    bob = sec.RideKeyMaterial()
    k1 = sec.derive_session_key(alice.private_key, bob.public_bytes(), "ride-1", "111111")
    k2 = sec.derive_session_key(alice.private_key, bob.public_bytes(), "ride-1", "222222")
    assert k1 != k2


def test_encrypt_decrypt_roundtrip():
    key = os.urandom(32)
    plaintext = b'{"lat": 27.68, "lon": 84.43}'
    blob = sec.encrypt_payload(key, plaintext, associated_data=b"ride-1|0")
    recovered = sec.decrypt_payload(key, blob, associated_data=b"ride-1|0")
    assert recovered == plaintext


def test_tampered_ciphertext_is_rejected():
    key = os.urandom(32)
    blob = bytearray(sec.encrypt_payload(key, b"hello", associated_data=b"aad"))
    blob[-1] ^= 0xFF  # flip a bit in the auth tag
    with pytest.raises(InvalidTag):
        sec.decrypt_payload(key, bytes(blob), associated_data=b"aad")


def test_wrong_associated_data_is_rejected():
    key = os.urandom(32)
    blob = sec.encrypt_payload(key, b"hello", associated_data=b"ride-1|0")
    with pytest.raises(InvalidTag):
        sec.decrypt_payload(key, blob, associated_data=b"ride-1|1")  # wrong epoch


def test_key_rotation_changes_key():
    ride_id, code = "ride-1", "111111"
    original = sec.RideKeyMaterial()
    peer = sec.RideKeyMaterial()
    original.session_key = sec.derive_session_key(original.private_key, peer.public_bytes(), ride_id, code)

    rotated = sec.rotate_session_key(original, peer.public_bytes(), ride_id, code)
    assert rotated.session_key != original.session_key
    assert rotated.key_epoch == original.key_epoch + 1


def test_plausible_movement_normal_riding_speed():
    now = time.time()
    assert sec.is_plausible_movement(27.700, 84.400, now, 27.7005, 84.4005, now + 2)


def test_implausible_teleport_is_flagged():
    now = time.time()
    # ~25km jump in 1 second -> ~90,000 km/h, clearly impossible
    assert not sec.is_plausible_movement(27.70, 84.40, now, 27.90, 84.90, now + 1)
