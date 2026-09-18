/**
 * bluetooth-ui.js — the ONE place that decides whether we're talking to
 * real hardware (via window.AndroidBridge, injected by MainActivity.kt's
 * WebView) or running as a standalone browser demo.
 *
 * This file never touches Bluetooth or GPS APIs directly — a WebView (and
 * a desktop browser) has no BLE central/peripheral API to speak of, which
 * is exactly why the master spec calls for a native bridge instead of
 * pretending browser APIs provide unrestricted Bluetooth access.
 *
 * RidersLive.bridge exposes the same async-ish, event-driven interface
 * either way: `createRide`, `joinRide`, `startRide`, `endRide`,
 * `setDestination`, `removeParticipant`, and a subscribe(event, cb) for
 * 'rideStateChanged' | 'destinationChanged' | 'participantRemoved' |
 * 'riderUpdate' | 'error'.
 */
(function (global) {
  const listeners = {};
  function emit(event, detail) {
    (listeners[event] || []).forEach((cb) => cb(detail));
  }
  function subscribe(event, cb) {
    (listeners[event] = listeners[event] || []).push(cb);
  }

  const hasNativeBridge = typeof window.AndroidBridge !== "undefined";

  // Native events arrive as window CustomEvents dispatched by
  // MainActivity.notifyJs(...). Re-route them into our listener map.
  if (hasNativeBridge) {
    ["rideStateChanged", "destinationChanged", "participantRemoved", "error"].forEach((evt) => {
      window.addEventListener(evt, (e) => emit(evt, e.detail));
    });
  }

  // ---------------------------------------------------------------------
  // NATIVE bridge: thin wrapper over window.AndroidBridge (see MainActivity.kt)
  // ---------------------------------------------------------------------
  const NativeBridge = {
    mode: "native",
    hasPermissions: () => window.AndroidBridge.hasRequiredPermissions(),
    requestPermissions: () => window.AndroidBridge.requestPermissions(),
    createRide: (displayName) => JSON.parse(window.AndroidBridge.createRide(displayName)),
    startRide: () => window.AndroidBridge.startRide(),
    endRide: () => window.AndroidBridge.endRide(),
    setDestination: (riderId, lat, lon, name) => window.AndroidBridge.setDestination(riderId, lat, lon, name),
    removeParticipant: (actingId, targetId) => window.AndroidBridge.removeParticipant(actingId, targetId),
    joinRide: () => {
      throw new Error("Joining is completed over BLE by the native layer once a ride code is entered; see BleMeshManager.kt + HandshakeManager.");
    },
    subscribe,
  };

  // ---------------------------------------------------------------------
  // SIMULATED bridge: for browser-based UI development and this demo.
  // Simulates 2-4 nearby riders moving along a route toward a shared
  // destination, with occasional simulated connection loss, so every
  // screen (map, dashboard, connection status) has real, changing data
  // to render without needing physical hardware.
  // ---------------------------------------------------------------------
  const SIM_RIDER_NAMES = ["Rider B", "Rider C", "Rider D", "Rider E"];
  const SIM_COLORS = ["#E8A23D", "#5AA9E6", "#C97BD1", "#7FD8A0"];

  function randomRideCode() {
    // Uses the browser CSPRNG, matching the "cryptographically secure,
    // not Math.random()" rule from security.py, for demo fidelity.
    const bytes = new Uint32Array(6);
    crypto.getRandomValues(bytes);
    return Array.from(bytes).map((b) => b % 10).join("");
  }

  const SimulatedBridge = {
    mode: "simulated",
    _state: null,
    _timer: null,

    hasPermissions: () => true,
    requestPermissions: () => emit("error", { message: "(demo mode — no real permissions needed)" }),

    createRide(displayName) {
      const baseLat = 27.7172, baseLon = 85.3240; // Kathmandu, as a plausible demo start point
      const me = { riderId: "RIDER-YOU1", displayName, lat: baseLat, lon: baseLon, speedMps: 0, headingDeg: 0, isSelf: true, lastUpdate: Date.now(), connection: "live" };

      const simRiders = SIM_RIDER_NAMES.slice(0, 3).map((name, i) => ({
        riderId: `RIDER-SIM${i}`,
        displayName: name,
        lat: baseLat + (Math.random() - 0.5) * 0.05,
        lon: baseLon + (Math.random() - 0.5) * 0.05,
        speedMps: 8 + Math.random() * 6,
        headingDeg: Math.random() * 360,
        isSelf: false,
        color: SIM_COLORS[i],
        connection: "live",
        hopCount: i === 0 ? 0 : i, // demonstrate multi-hop relay in the UI
        lastUpdate: Date.now(),
      }));

      this._state = {
        rideId: "sim-" + Math.random().toString(36).slice(2),
        code: randomRideCode(),
        state: "CREATED",
        me,
        riders: simRiders,
        destination: null,
      };

      this._startSimLoop();
      return { rideId: this._state.rideId, code: this._state.code, riderId: me.riderId };
    },

    startRide() {
      if (!this._state) return;
      this._state.state = "ACTIVE";
      emit("rideStateChanged", { state: "ACTIVE" });
    },

    endRide() {
      if (!this._state) return;
      this._state.state = "ENDED";
      clearInterval(this._timer);
      emit("rideStateChanged", { state: "ENDED" });
      this._state = null;
    },

    setDestination(riderId, lat, lon, name) {
      if (!this._state) return;
      this._state.destination = { lat: parseFloat(lat), lon: parseFloat(lon), name };
      emit("destinationChanged", { lat, lon, name });
    },

    removeParticipant(actingId, targetId) {
      if (!this._state) return;
      this._state.riders = this._state.riders.filter((r) => r.riderId !== targetId);
      emit("participantRemoved", { riderId: targetId });
    },

    getState() {
      return this._state;
    },

    _startSimLoop() {
      clearInterval(this._timer);
      this._timer = setInterval(() => {
        if (!this._state) return;
        const dest = this._state.destination;
        [this._state.me, ...this._state.riders].forEach((r) => {
          if (r.connection === "lost") return;
          // Drift each rider slightly toward the destination if one is set,
          // otherwise just wander — enough motion to make ETA/distance
          // numbers visibly update in the demo.
          const stepDeg = 0.0006 * (0.6 + Math.random());
          if (dest) {
            const dLat = dest.lat - r.lat, dLon = dest.lon - r.lon;
            const mag = Math.hypot(dLat, dLon) || 1;
            r.lat += (dLat / mag) * stepDeg;
            r.lon += (dLon / mag) * stepDeg;
          } else {
            r.lat += (Math.random() - 0.5) * stepDeg;
            r.lon += (Math.random() - 0.5) * stepDeg;
          }
          r.speedMps = r.isSelf ? 0 : 6 + Math.random() * 8;
          r.lastUpdate = Date.now();
        });

        // Occasionally simulate a rider dropping out of direct range and
        // being picked back up via relay, to exercise the connection UI.
        if (Math.random() < 0.03 && this._state.riders.length > 1) {
          const r = this._state.riders[Math.floor(Math.random() * this._state.riders.length)];
          r.connection = r.connection === "live" ? "stale" : "live";
        }

        emit("riderUpdate", this._state);
      }, 1500);
    },

    subscribe,
  };

  global.RidersLive = global.RidersLive || {};
  global.RidersLive.bridge = hasNativeBridge ? NativeBridge : SimulatedBridge;
  global.RidersLive.isSimulated = !hasNativeBridge;
})(window);
