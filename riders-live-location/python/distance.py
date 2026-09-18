"""
distance.py — Geographic distance calculations.

Two distinct kinds of distance are produced, and callers must not
blur them together in the UI (see master prompt §15):

  * `haversine_km`      — straight-line ("as the crow flies") distance.
                           Always available, works fully offline.
  * `road_distance_km`  — distance along an offline routing graph
                           (e.g. from an offline OSRM/Valhalla graph
                           bundled with the downloaded map region).
                           Only available where offline routing data
                           has been downloaded; returns None otherwise
                           so the UI can show "Road distance unavailable"
                           instead of silently substituting straight-line.
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Optional, Protocol

EARTH_RADIUS_KM = 6371.0088


def haversine_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Great-circle distance between two WGS84 points, in kilometers."""
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlambda = math.radians(lon2 - lon1)

    a = math.sin(dphi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(dlambda / 2) ** 2
    return 2 * EARTH_RADIUS_KM * math.asin(math.sqrt(a))


def bearing_deg(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Initial compass bearing from point 1 to point 2, in degrees [0, 360)."""
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    dlambda = math.radians(lon2 - lon1)

    x = math.sin(dlambda) * math.cos(phi2)
    y = math.cos(phi1) * math.sin(phi2) - math.sin(phi1) * math.cos(phi2) * math.cos(dlambda)
    theta = math.atan2(x, y)
    return (math.degrees(theta) + 360) % 360


@dataclass
class DistanceResult:
    straight_line_km: float
    road_km: Optional[float]
    is_road_distance_available: bool

    @property
    def best_estimate_km(self) -> float:
        """The most accurate distance we can offer, preferring road distance."""
        return self.road_km if self.road_km is not None else self.straight_line_km


class OfflineRoutingProvider(Protocol):
    """Interface an offline routing engine (e.g. bundled OSRM graph) must
    implement to plug into distance_between(). Kept as a Protocol so the
    core logic has zero hard dependency on any specific routing engine —
    the mobile layer supplies a real implementation backed by downloaded
    map data; tests can supply a fake."""

    def route_distance_km(self, lat1: float, lon1: float, lat2: float, lon2: float) -> Optional[float]:
        ...


def distance_between(
    lat1: float, lon1: float, lat2: float, lon2: float,
    routing_provider: Optional[OfflineRoutingProvider] = None,
) -> DistanceResult:
    straight = haversine_km(lat1, lon1, lat2, lon2)
    road = None
    if routing_provider is not None:
        road = routing_provider.route_distance_km(lat1, lon1, lat2, lon2)
    return DistanceResult(straight_line_km=straight, road_km=road, is_road_distance_available=road is not None)
