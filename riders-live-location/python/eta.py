"""
eta.py — Estimated time of arrival, from local information only.

No live traffic, no cloud routing service. Every value this module
returns should be presented in the UI with an "estimate" label — see
master prompt §16. This module never claims more precision than the
inputs support.
"""

from __future__ import annotations

from collections import deque
from dataclasses import dataclass
from typing import Deque, Optional


MIN_MOVING_SPEED_MPS = 0.6      # below this we treat the rider as stationary
DEFAULT_FALLBACK_SPEED_KMH = 25.0  # used only if we have no speed history at all


@dataclass
class EtaEstimate:
    minutes: Optional[float]
    based_on: str  # "current_speed" | "average_recent_speed" | "fallback_default" | "stationary"

    def label(self) -> str:
        if self.minutes is None:
            return "ETA unavailable"
        if self.based_on == "stationary":
            return "ETA unavailable — rider stationary"
        return f"ETA ~{round(self.minutes)} min (estimate)"


class SpeedHistory:
    """Rolling window of recent speed samples for one rider, used to
    smooth out momentary GPS speed noise before computing an ETA."""

    def __init__(self, window_size: int = 10) -> None:
        self._samples: Deque[float] = deque(maxlen=window_size)

    def add_sample(self, speed_mps: float) -> None:
        if speed_mps >= 0:
            self._samples.append(speed_mps)

    def average_mps(self) -> Optional[float]:
        if not self._samples:
            return None
        return sum(self._samples) / len(self._samples)


def estimate_eta_minutes(
    distance_km: float,
    current_speed_mps: Optional[float],
    speed_history: Optional[SpeedHistory] = None,
) -> EtaEstimate:
    """Estimate minutes remaining to cover `distance_km`.

    Preference order:
      1. current_speed_mps, if it indicates real movement.
      2. recent average speed from speed_history.
      3. a conservative fallback average speed, clearly labeled as such.
    """
    if distance_km <= 0:
        return EtaEstimate(minutes=0.0, based_on="current_speed")

    if current_speed_mps is not None and current_speed_mps >= MIN_MOVING_SPEED_MPS:
        speed_kmh = current_speed_mps * 3.6
        return EtaEstimate(minutes=(distance_km / speed_kmh) * 60, based_on="current_speed")

    if speed_history is not None:
        avg = speed_history.average_mps()
        if avg is not None and avg >= MIN_MOVING_SPEED_MPS:
            speed_kmh = avg * 3.6
            return EtaEstimate(minutes=(distance_km / speed_kmh) * 60, based_on="average_recent_speed")

    if current_speed_mps is not None and current_speed_mps < MIN_MOVING_SPEED_MPS:
        return EtaEstimate(minutes=None, based_on="stationary")

    # No usable speed data at all yet (e.g. just joined the ride).
    minutes = (distance_km / DEFAULT_FALLBACK_SPEED_KMH) * 60
    return EtaEstimate(minutes=minutes, based_on="fallback_default")
