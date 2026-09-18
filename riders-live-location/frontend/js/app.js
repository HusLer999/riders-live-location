/**
 * app.js — navigation and top-level wiring. Reads/writes only through
 * RidersLive.ride / RidersLive.bridge / RidersLive.settings; contains no
 * Bluetooth/GPS/crypto logic of its own.
 */
(function (global) {
  const RL = global.RidersLive;

  // -- navigation -----------------------------------------------------
  function showScreen(name) {
    document.querySelectorAll(".screen").forEach((el) => {
      el.dataset.active = el.id === `screen-${name}` ? "true" : "false";
    });
    document.querySelectorAll(".nav-item").forEach((el) => {
      el.dataset.active = el.dataset.nav === name ? "true" : "false";
    });
  }

  document.querySelectorAll("[data-nav]").forEach((el) => {
    el.addEventListener("click", () => showScreen(el.dataset.nav));
  });

  // -- toasts -----------------------------------------------------------
  RL.toast = function (message, kind = "info") {
    const container = document.getElementById("toast-container");
    const el = document.createElement("div");
    el.className = "toast";
    el.textContent = message;
    container.appendChild(el);
    setTimeout(() => el.remove(), 3200);
  };

  // -- permission banner --------------------------------------------------
  function refreshPermissionBanner() {
    const banner = document.getElementById("permission-banner");
    const text = document.getElementById("permission-banner-text");
    if (RL.bridge.hasPermissions()) {
      banner.classList.add("hidden");
      return;
    }
    text.textContent = "Location and Bluetooth access are needed to find nearby riders and share your position.";
    banner.classList.remove("hidden");
  }
  document.getElementById("btn-grant-permissions").addEventListener("click", () => {
    RL.bridge.requestPermissions();
  });

  // -- status bar ---------------------------------------------------------
  function setDot(id, state) {
    const el = document.querySelector(`#${id} .dot`);
    if (el) el.dataset.state = state;
  }

  function updateStatusBar(rideState) {
    const active = rideState && rideState.state === "ACTIVE";
    setDot("status-bluetooth", active ? "connected" : "off");
    setDot("status-gps", active ? "accurate" : "off");
    document.querySelector("#status-network .label").textContent = "Offline";
    setDot("status-network", "warn"); // this app is ALWAYS in offline mode by design
    const riders = rideState ? [rideState.me, ...rideState.riders] : [];
    document.getElementById("rider-count-label").textContent = `${riders.length} rider${riders.length === 1 ? "" : "s"}`;
  }

  // -- home: create / join ------------------------------------------------
  document.getElementById("btn-create-ride").addEventListener("click", () => {
    if (!RL.bridge.hasPermissions()) { refreshPermissionBanner(); return; }
    const alias = RL.settings.get().displayAlias || "Rider";
    RL.ride.createAsPrimary(alias);
    document.getElementById("ride-code-display").textContent = RL.ride.code;
    showScreen("ride-code");
  });

  document.getElementById("btn-join-ride").addEventListener("click", () => showScreen("join"));

  document.getElementById("btn-confirm-join").addEventListener("click", () => {
    const code = document.getElementById("join-code-input").value.trim();
    const name = document.getElementById("join-name-input").value.trim() || "Rider";
    if (code.length !== 6) { RL.toast("Enter the 6-digit ride code"); return; }
    if (RL.isSimulated) {
      RL.toast("Demo mode: joining shows the same simulated group as Create Ride");
      RL.ride.createAsPrimary(name); // demo convenience: reuse the same simulated state
      RL.ride.role = "RIDER";
      showScreen("map");
    } else {
      RL.toast("Searching for the ride over Bluetooth…");
      // Real joining happens asynchronously over BLE (BleMeshManager +
      // HandshakeManager); a production build would show a spinner here
      // and transition screens once 'rideStateChanged' fires.
    }
  });

  // -- ride code / waiting room --------------------------------------------
  document.getElementById("btn-copy-code").addEventListener("click", () => {
    navigator.clipboard?.writeText(RL.ride.code || "");
    RL.toast("Ride code copied");
  });
  document.getElementById("btn-show-qr").addEventListener("click", () => {
    const wrap = document.getElementById("qr-canvas-wrap");
    wrap.classList.toggle("hidden");
    if (!wrap.classList.contains("hidden")) {
      const payload = JSON.stringify({ code: RL.ride.code, rideId: RL.ride.rideId });
      RL.renderQrMockup(document.getElementById("qr-canvas"), payload);
    }
  });
  document.getElementById("btn-start-ride").addEventListener("click", () => {
    RL.ride.start();
    showScreen("map");
  });

  // -- ride controls --------------------------------------------------------
  document.getElementById("btn-set-destination").addEventListener("click", () => {
    const lat = document.getElementById("destination-lat-input").value;
    const lon = document.getElementById("destination-lon-input").value;
    const name = document.getElementById("destination-name-input").value;
    if (!lat || !lon) { RL.toast("Enter a latitude and longitude"); return; }
    RL.ride.setDestination(lat, lon, name);
    RL.toast("Destination updated for all riders");
  });
  document.getElementById("btn-end-ride").addEventListener("click", () => {
    if (confirm("End the ride for everyone and stop sharing?")) {
      RL.ride.end();
      showScreen("home");
    }
  });

  // -- react to ride/bridge events --------------------------------------------
  RL.bridge.subscribe("destinationChanged", (detail) => {
    document.getElementById("destination-name").textContent = detail.name || "Destination set";
  });

  RL.bridge.subscribe("rideStateChanged", () => updateStatusBar(RL.bridge.getState ? RL.bridge.getState() : null));

  if (RL.isSimulated) {
    RL.bridge.subscribe("riderUpdate", (state) => {
      updateStatusBar(state);
      RL.renderWaitingChips(document.getElementById("waiting-room-list"), state.riders);

      const allForList = [state.me, ...state.riders];
      RL.renderRiderList(document.getElementById("riders-list"), allForList, state.destination);

      if (state.destination) {
        const distKm = RL.geo.haversineKm(state.me.lat, state.me.lon, state.destination.lat, state.destination.lon);
        document.getElementById("destination-name").textContent = state.destination.name || "Destination";
        document.getElementById("destination-distance").textContent =
          `${distKm.toFixed(1)} km · ${RL.geo.etaLabel(RL.geo.estimateEtaMinutes(distKm, state.me.speedMps))}`;
      }

      RL.map.render(state);
    });
  }

  // -- init ------------------------------------------------------------------
  RL.map.init(document.getElementById("map-canvas"));
  RL.settings.initSettingsScreen();
  refreshPermissionBanner();
  updateStatusBar(null);
  showScreen("home");
})(window);
