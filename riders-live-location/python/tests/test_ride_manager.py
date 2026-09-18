import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import pytest

import ride_manager as rm
from security import RideKeyMaterial


def test_create_ride_generates_code_and_creator():
    ride = rm.Ride(ride_id="ride-1")
    assert len(ride.code) == 6
    primary = ride.create_as_primary("Alex")
    assert primary.role == rm.Role.PRIMARY
    assert ride.state == rm.RideState.CREATED


def test_valid_lifecycle_transitions():
    ride = rm.Ride(ride_id="ride-1")
    ride.open_for_joining()
    assert ride.state == rm.RideState.WAITING
    ride.start()
    assert ride.state == rm.RideState.ACTIVE
    ride.pause()
    assert ride.state == rm.RideState.PAUSED
    ride.resume()
    assert ride.state == rm.RideState.ACTIVE
    ride.end()
    assert ride.state == rm.RideState.ENDED


def test_invalid_transition_raises():
    ride = rm.Ride(ride_id="ride-1")
    with pytest.raises(rm.RideError):
        ride.start()  # can't jump straight from CREATED to ACTIVE


def test_cannot_transition_out_of_ended():
    ride = rm.Ride(ride_id="ride-1")
    ride.open_for_joining()
    ride.start()
    ride.end()
    with pytest.raises(rm.RideError):
        ride.resume()


def test_ending_ride_clears_session_keys():
    ride = rm.Ride(ride_id="ride-1")
    primary = ride.create_as_primary("Alex")
    primary.key_material.session_key = b"x" * 32
    ride.open_for_joining()
    ride.start()
    ride.end()
    assert len(ride.participants) == 0  # keys/participants wiped


def test_only_primary_can_set_destination():
    ride = rm.Ride(ride_id="ride-1")
    primary = ride.create_as_primary("Alex")
    peer_key = RideKeyMaterial().public_bytes()
    rider = ride.add_participant("Sam", peer_key)

    with pytest.raises(rm.UnauthorizedError):
        ride.set_destination(rider.rider_id, 27.7, 85.3)

    dest = ride.set_destination(primary.rider_id, 27.7, 85.3, name="Kathmandu")
    assert dest.name == "Kathmandu"
    assert dest.set_by == primary.rider_id


def test_only_primary_can_remove_participant():
    ride = rm.Ride(ride_id="ride-1")
    primary = ride.create_as_primary("Alex")
    peer_key = RideKeyMaterial().public_bytes()
    rider1 = ride.add_participant("Sam", peer_key)
    rider2 = ride.add_participant("Jo", peer_key)

    with pytest.raises(rm.UnauthorizedError):
        ride.remove_participant(rider1.rider_id, rider2.rider_id, peer_key)

    ride.remove_participant(primary.rider_id, rider1.rider_id, peer_key)
    assert rider1.rider_id not in ride.participants


def test_removal_rotates_remaining_keys():
    ride = rm.Ride(ride_id="ride-1")
    primary = ride.create_as_primary("Alex")
    peer_key = RideKeyMaterial().public_bytes()
    rider1 = ride.add_participant("Sam", peer_key)
    rider2 = ride.add_participant("Jo", peer_key)

    old_epoch = ride.participants[rider2.rider_id].key_material.key_epoch
    ride.remove_participant(primary.rider_id, rider1.rider_id, peer_key)
    new_epoch = ride.participants[rider2.rider_id].key_material.key_epoch
    assert new_epoch == old_epoch + 1


def test_cannot_join_ended_ride():
    ride = rm.Ride(ride_id="ride-1")
    ride.create_as_primary("Alex")
    ride.open_for_joining()
    ride.start()
    ride.end()
    peer_key = RideKeyMaterial().public_bytes()
    with pytest.raises(rm.RideError):
        ride.add_participant("Sam", peer_key)
