"""
location_manager.py — Adaptive GPS sampling rate + packet assembly.

Owns the "how often should I sample/transmit" decision (master prompt
§18-19). Does not touch the GPS hardware itself — the Android layer's
FusedLocationProviderClient callback feeds raw fixes into
`LocationManager.on_fix()`, and this module decides whether that fix
is worth turning into an outgoing packet yet.
"""

from __future__ import annotations

import time
from dataclasses import dataclass
from typing import Optional

from protocol import LocationPacket
from security import generate_packet_id, is_plausible_movement


@dataclass
class RawFix:
    latitude: float
    longitude: float
    speed_mps: Optional[float]
    heading_deg: Optional[float]
    accuracy_m: float
    timestamp: float


@dataclass
class AdaptiveIntervalConfig:
    moving_interval_s: float = 2.0
    stationary_interval_s: float = 10.0
    low_battery_interval_s: float = 20.0
    low_battery_threshold_pct: int = 20
    stationary_speed_threshold_mps: float = 1.0  # ~3.6 km/h


class LocationManager:
    def __init__(self, ride_id: str, rider_id: str, config: Optional[AdaptiveIntervalConfig] = None):
        self.ride_id = ride_id
        self.rider_id = rider_id
        self.config = config or AdaptiveIntervalConfig()
        self._sequence_number = 0
        self._last_sent_at: float = 0.0
        self._last_fix: Optional[RawFix] = None
        self._last_accepted_plausibility_fix: Optional[RawFix] = None

    def _current_interval_s(self, fix: RawFix, battery_pct: Optional[int]) -> float:
        if battery_pct is not None and battery_pct <= self.config.low_battery_threshold_pct:
            return self.config.low_battery_interval_s
        is_moving = (fix.speed_mps or 0) >= self.config.stationary_speed_threshold_mps
        return self.config.moving_interval_s if is_moving else self.config.stationary_interval_s

    def should_transmit(self, fix: RawFix, battery_pct: Optional[int] = None, now: Optional[float] = None) -> bool:
        now = now if now is not None else time.time()
        interval = self._current_interval_s(fix, battery_pct)
        return (now - self._last_sent_at) >= interval

    def plausibility_check(self, fix: RawFix) -> bool:
        """Returns False if this fix implies an impossible jump from the
        last accepted fix. Callers should show 'Location data may be
        inaccurate' rather than dropping the rider silently."""
        prev = self._last_accepted_plausibility_fix
        if prev is None:
            self._last_accepted_plausibility_fix = fix
            return True
        plausible = is_plausible_movement(
            prev.latitude, prev.longitude, prev.timestamp,
            fix.latitude, fix.longitude, fix.timestamp,
        )
        self._last_accepted_plausibility_fix = fix
        return plausible

    def build_packet(self, fix: RawFix, share_speed: bool = True, share_heading: bool = True,
                      destination: Optional[dict] = None) -> LocationPacket:
        self._sequence_number += 1
        self._last_sent_at = fix.timestamp
        self._last_fix = fix
        return LocationPacket(
            ride_id=self.ride_id,
            rider_id=self.rider_id,
            packet_id=generate_packet_id(),
            latitude=fix.latitude,
            longitude=fix.longitude,
            speed_mps=fix.speed_mps if share_speed else None,
            heading_deg=fix.heading_deg if share_heading else None,
            timestamp=fix.timestamp,
            sequence_number=self._sequence_number,
            destination=destination,
        )
