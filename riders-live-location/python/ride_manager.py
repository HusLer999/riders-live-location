"""
ride_manager.py — Ride lifecycle, roles, and participant management.

State machine (master prompt §25):

    CREATED -> WAITING -> ACTIVE -> PAUSED -> ENDED
                  ^__________________|

This module is transport-agnostic: it doesn't know about Bluetooth.
The Android layer feeds it events (participant discovered/authenticated,
packet received) and calls it for decisions (should I relay this?
what is my current session key?), then does the actual radio work.
"""

from __future__ import annotations

import time
from dataclasses import dataclass, field
from enum import Enum, auto
from typing import Dict, Optional

from security import (
    RideKeyMaterial,
    derive_session_key,
    generate_ride_code,
    generate_rider_id,
    rotate_session_key,
)


class RideState(Enum):
    CREATED = auto()
    WAITING = auto()
    ACTIVE = auto()
    PAUSED = auto()
    ENDED = auto()


class Role(Enum):
    PRIMARY = auto()
    RIDER = auto()


_VALID_TRANSITIONS = {
    RideState.CREATED: {RideState.WAITING},
    RideState.WAITING: {RideState.ACTIVE, RideState.ENDED},
    RideState.ACTIVE: {RideState.PAUSED, RideState.ENDED},
    RideState.PAUSED: {RideState.ACTIVE, RideState.ENDED},
    RideState.ENDED: set(),
}


class RideError(Exception):
    pass


class UnauthorizedError(RideError):
    pass


@dataclass
class Participant:
    rider_id: str
    display_name: str
    role: Role
    key_material: RideKeyMaterial
    joined_at: float = field(default_factory=time.time)
    revoked: bool = False
    share_speed: bool = True
    share_heading: bool = True


@dataclass
class Destination:
    latitude: float
    longitude: float
    name: Optional[str] = None
    set_by: Optional[str] = None
    updated_at: float = field(default_factory=time.time)


class Ride:
    """One ride session, as seen from a single device's point of view."""

    def __init__(self, ride_id: str, code_length: int = 6, alphanumeric_code: bool = False):
        self.ride_id = ride_id
        self.code = generate_ride_code(length=code_length, alphanumeric=alphanumeric_code)
        self.state = RideState.CREATED
        self.participants: Dict[str, Participant] = {}
        self.destination: Optional[Destination] = None
        self.created_at = time.time()
        self.max_recommended_participants = 8  # see docs/architecture.md for why

    # -- lifecycle -----------------------------------------------------

    def _transition(self, new_state: RideState) -> None:
        if new_state not in _VALID_TRANSITIONS[self.state]:
            raise RideError(f"Cannot transition ride from {self.state.name} to {new_state.name}")
        self.state = new_state

    def open_for_joining(self) -> None:
        self._transition(RideState.WAITING)

    def start(self) -> None:
        self._transition(RideState.ACTIVE)

    def pause(self) -> None:
        self._transition(RideState.PAUSED)

    def resume(self) -> None:
        self._transition(RideState.ACTIVE)

    def end(self) -> None:
        self._transition(RideState.ENDED)
        # Secure deletion of session key material — see docs/security.md
        # ("Threat 7"). Real deletion of the underlying bytes on Android
        # goes through the Keystore-backed wrapper; here we drop our
        # only references so they become eligible for GC/zeroing.
        for participant in self.participants.values():
            participant.key_material.session_key = None
        self.participants.clear()

    # -- participants ----------------------------------------------------

    def create_as_primary(self, display_name: str) -> Participant:
        rider_id = generate_rider_id()
        me = Participant(
            rider_id=rider_id,
            display_name=display_name,
            role=Role.PRIMARY,
            key_material=RideKeyMaterial(),
        )
        self.participants[rider_id] = me
        return me

    def add_participant(
        self, display_name: str, peer_public_key_bytes: bytes, role: Role = Role.RIDER,
    ) -> Participant:
        if self.state == RideState.ENDED:
            raise RideError("Cannot join a ride that has ended")
        rider_id = generate_rider_id()
        key_material = RideKeyMaterial()
        # In the real handshake this is completed with the peer's
        # actual public key exchanged over BLE; here we just show the
        # call shape. See mobile bluetooth/HandshakeManager.kt.
        self.participants[rider_id] = Participant(
            rider_id=rider_id, display_name=display_name, role=role, key_material=key_material,
        )
        return self.participants[rider_id]

    def _require_primary(self, acting_rider_id: str) -> Participant:
        actor = self.participants.get(acting_rider_id)
        if actor is None or actor.revoked:
            raise UnauthorizedError("Unknown or revoked rider")
        if actor.role != Role.PRIMARY:
            raise UnauthorizedError("Only the primary rider may perform this action")
        return actor

    def remove_participant(self, acting_rider_id: str, target_rider_id: str,
                            peer_public_key_bytes: bytes) -> None:
        """Primary-rider-only. Revokes a participant and rotates keys so
        previously captured ciphertext does not become readable through
        whatever new session key is established next (forward secrecy
        across the rotation — see security.rotate_session_key).
        """
        self._require_primary(acting_rider_id)
        target = self.participants.get(target_rider_id)
        if target is None:
            raise RideError("No such participant")
        target.revoked = True
        del self.participants[target_rider_id]

        # Rotate remaining participants' key material so the removed
        # device's last-known key epoch can no longer decrypt anything.
        for participant in self.participants.values():
            participant.key_material = rotate_session_key(
                participant.key_material, peer_public_key_bytes, self.ride_id, self.code,
            )

    def set_destination(self, acting_rider_id: str, latitude: float, longitude: float,
                         name: Optional[str] = None) -> Destination:
        actor = self._require_primary(acting_rider_id)
        self.destination = Destination(latitude=latitude, longitude=longitude, name=name, set_by=actor.rider_id)
        return self.destination

    def active_participant_count(self) -> int:
        return len(self.participants)
