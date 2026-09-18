import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import distance as dist
import eta as eta_mod


def test_haversine_zero_distance():
    assert dist.haversine_km(27.7, 84.4, 27.7, 84.4) == 0


def test_haversine_known_distance_kathmandu_pokhara():
    # Kathmandu ~ (27.7172, 85.3240), Pokhara ~ (28.2096, 83.9856)
    km = dist.haversine_km(27.7172, 85.3240, 28.2096, 83.9856)
    # Straight-line distance is roughly 130-145 km
    assert 120 < km < 150


def test_bearing_north_is_zero():
    b = dist.bearing_deg(27.0, 84.0, 28.0, 84.0)
    assert -1 <= b <= 1 or 359 <= b <= 361


def test_distance_between_without_routing_provider():
    result = dist.distance_between(27.0, 84.0, 27.1, 84.1)
    assert result.road_km is None
    assert not result.is_road_distance_available
    assert result.best_estimate_km == result.straight_line_km


class FakeRouter:
    def route_distance_km(self, lat1, lon1, lat2, lon2):
        return dist.haversine_km(lat1, lon1, lat2, lon2) * 1.3  # roads aren't straight


def test_distance_between_with_routing_provider():
    result = dist.distance_between(27.0, 84.0, 27.1, 84.1, routing_provider=FakeRouter())
    assert result.is_road_distance_available
    assert result.road_km > result.straight_line_km
    assert result.best_estimate_km == result.road_km


def test_eta_uses_current_speed_when_moving():
    est = eta_mod.estimate_eta_minutes(distance_km=10, current_speed_mps=20)  # 72 km/h
    assert est.based_on == "current_speed"
    assert 8 < est.minutes < 9  # 10km at 72km/h = ~8.3 min


def test_eta_falls_back_to_speed_history():
    history = eta_mod.SpeedHistory()
    for s in [10, 12, 11, 9]:
        history.add_sample(s)
    est = eta_mod.estimate_eta_minutes(distance_km=5, current_speed_mps=None, speed_history=history)
    assert est.based_on == "average_recent_speed"
    assert est.minutes is not None


def test_eta_stationary_has_no_minutes():
    est = eta_mod.estimate_eta_minutes(distance_km=5, current_speed_mps=0.1)
    assert est.based_on == "stationary"
    assert est.minutes is None
    assert "unavailable" in est.label()


def test_eta_zero_distance():
    est = eta_mod.estimate_eta_minutes(distance_km=0, current_speed_mps=10)
    assert est.minutes == 0.0


def test_eta_no_data_uses_fallback():
    est = eta_mod.estimate_eta_minutes(distance_km=10, current_speed_mps=None, speed_history=None)
    assert est.based_on == "fallback_default"
    assert est.minutes is not None
