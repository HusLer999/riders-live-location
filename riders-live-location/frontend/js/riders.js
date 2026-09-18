/**
 * riders.js — geo math ported from python/distance.py + python/eta.py
 * (kept behaviorally identical to the reference implementation, see
 * python/tests/ for the spec this must match), plus rendering for the
 * rider list / group dashboard and the waiting-room chip row.
 */
(function (global) {
  const EARTH_RADIUS_KM = 6371.0088;

  function haversineKm(lat1, lon1, lat2, lon2) {
    const toRad = (d) => (d * Math.PI) / 180;
    const phi1 = toRad(lat1), phi2 = toRad(lat2);
    const dphi = toRad(lat2 - lat1);
    const dlambda = toRad(lon2 - lon1);
    const a = Math.sin(dphi / 2) ** 2 + Math.cos(phi1) * Math.cos(phi2) * Math.sin(dlambda / 2) ** 2;
    return 2 * EARTH_RADIUS_KM * Math.asin(Math.sqrt(a));
  }

  const MIN_MOVING_SPEED_MPS = 0.6;
  const DEFAULT_FALLBACK_SPEED_KMH = 25.0;

  /** Mirrors eta.estimate_eta_minutes. Straight-line distance is used
   * here because the browser demo has no offline routing graph loaded;
   * on-device, distance_km should come from distance.distance_between()
   * with a real OfflineRoutingProvider when available, per §15. */
  function estimateEtaMinutes(distanceKm, currentSpeedMps) {
    if (distanceKm <= 0) return { minutes: 0, basedOn: "current_speed" };
    if (currentSpeedMps != null && currentSpeedMps >= MIN_MOVING_SPEED_MPS) {
      const speedKmh = currentSpeedMps * 3.6;
      return { minutes: (distanceKm / speedKmh) * 60, basedOn: "current_speed" };
    }
    if (currentSpeedMps != null && currentSpeedMps < MIN_MOVING_SPEED_MPS) {
      return { minutes: null, basedOn: "stationary" };
    }
    return { minutes: (distanceKm / DEFAULT_FALLBACK_SPEED_KMH) * 60, basedOn: "fallback_default" };
  }

  function etaLabel(est) {
    if (est.minutes == null) return "ETA unavailable";
    return `ETA ~${Math.round(est.minutes)} min (estimate)`;
  }

  function connectionLabel(rider) {
    const secondsAgo = Math.round((Date.now() - rider.lastUpdate) / 1000);
    if (rider.connection === "lost" || secondsAgo > 18) return { text: `⚠ Last seen ${secondsAgo}s ago`, state: "lost" };
    if (rider.connection === "stale" || secondsAgo > 6) return { text: `● Relay — ${secondsAgo}s ago`, state: "stale" };
    return { text: `● Live — ${secondsAgo}s ago`, state: "live" };
  }

  function initialsFor(name) {
    return name.split(" ").map((p) => p[0]).slice(0, 2).join("").toUpperCase();
  }

  function renderRiderList(containerEl, meAndRiders, destination) {
    containerEl.innerHTML = "";
    meAndRiders.forEach((rider) => {
      const item = document.createElement("div");
      item.className = "rider-panel-item";

      const avatar = document.createElement("div");
      avatar.className = "rider-avatar";
      avatar.style.background = rider.isSelf ? "var(--teal)" : rider.color || "var(--amber)";
      avatar.textContent = initialsFor(rider.displayName);
      item.appendChild(avatar);

      const info = document.createElement("div");
      info.className = "rider-info";
      const nameRow = document.createElement("div");
      nameRow.className = "rider-name-row";
      const liveDot = document.createElement("span");
      const conn = connectionLabel(rider);
      liveDot.className = "live-dot";
      liveDot.dataset.live = conn.state;
      nameRow.appendChild(liveDot);
      const nameSpan = document.createElement("span");
      nameSpan.textContent = rider.displayName + (rider.isSelf ? " (you)" : "");
      nameRow.appendChild(nameSpan);
      info.appendChild(nameRow);

      const meta = document.createElement("div");
      meta.className = "rider-meta";
      meta.textContent = conn.text + (rider.hopCount ? ` · via relay (${rider.hopCount} hop${rider.hopCount > 1 ? "s" : ""})` : "");
      info.appendChild(meta);
      item.appendChild(info);

      const metrics = document.createElement("div");
      metrics.className = "rider-metrics";
      if (destination && !rider.isSelf) {
        const distKm = haversineKm(rider.lat, rider.lon, destination.lat, destination.lon);
        const dist = document.createElement("div");
        dist.className = "dist";
        dist.textContent = `${distKm.toFixed(1)} km`;
        metrics.appendChild(dist);
        const eta = document.createElement("div");
        eta.className = "eta";
        eta.textContent = etaLabel(estimateEtaMinutes(distKm, rider.speedMps));
        metrics.appendChild(eta);
      } else if (destination && rider.isSelf) {
        const distKm = haversineKm(rider.lat, rider.lon, destination.lat, destination.lon);
        const dist = document.createElement("div");
        dist.className = "dist";
        dist.textContent = `${distKm.toFixed(1)} km`;
        metrics.appendChild(dist);
        metrics.appendChild(Object.assign(document.createElement("div"), { className: "eta", textContent: "to destination" }));
      }
      item.appendChild(metrics);

      if (global.RidersLive.ride.role === "PRIMARY" && !rider.isSelf) {
        const removeBtn = document.createElement("button");
        removeBtn.className = "rider-remove-btn";
        removeBtn.textContent = "Remove";
        removeBtn.onclick = () => global.RidersLive.ride.removeParticipant(rider.riderId);
        item.appendChild(removeBtn);
      }

      containerEl.appendChild(item);
    });
  }

  function renderWaitingChips(containerEl, riders) {
    containerEl.innerHTML = "";
    riders.forEach((r) => {
      const chip = document.createElement("div");
      chip.className = "rider-chip";
      const swatch = document.createElement("span");
      swatch.className = "swatch";
      swatch.style.background = r.color || "var(--teal)";
      chip.appendChild(swatch);
      chip.appendChild(document.createTextNode(r.displayName));
      containerEl.appendChild(chip);
    });
  }

  global.RidersLive = global.RidersLive || {};
  global.RidersLive.geo = { haversineKm, estimateEtaMinutes, etaLabel };
  global.RidersLive.renderRiderList = renderRiderList;
  global.RidersLive.renderWaitingChips = renderWaitingChips;
})(window);
