/**
 * security-ui.js — wires the Settings screen's toggles to a small local
 * settings store. On-device, this same shape of object is persisted in
 * the `settings` table (database/schema.sql) via an encrypted-storage
 * bridge call; the browser demo keeps it in localStorage purely for a
 * persistent-feeling demo across reloads.
 *
 * Per master prompt §51: settings shown as permanently-on/disabled here
 * (require device authentication, rotate session keys) are NOT fake
 * controls — they reflect protections this app applies unconditionally,
 * shown so the person knows they're active rather than hidden.
 */
(function (global) {
  const STORAGE_KEY = "riders_live_settings_v1";

  const defaults = {
    locationSharing: true,
    saveLocationHistory: false,
    shareSpeed: true,
    shareHeading: true,
    autoDeleteRideData: true,
    displayAlias: "Rider",
    autoExpireRide: true,
    hideRiderIdentity: true,
    requireJoinConfirmation: true,
  };

  function load() {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      return raw ? { ...defaults, ...JSON.parse(raw) } : { ...defaults };
    } catch (e) {
      return { ...defaults };
    }
  }

  function save(settings) {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(settings));
    } catch (e) {
      /* storage may be unavailable (e.g. private browsing) — settings
         simply won't persist across reloads in that case. */
    }
  }

  let current = load();

  function bindToggle(elId, key) {
    const el = document.getElementById(elId);
    if (!el) return;
    el.checked = !!current[key];
    el.addEventListener("change", () => {
      current[key] = el.checked;
      save(current);
    });
  }

  function bindText(elId, key) {
    const el = document.getElementById(elId);
    if (!el) return;
    el.value = current[key];
    el.addEventListener("change", () => {
      current[key] = el.value;
      save(current);
    });
  }

  function initSettingsScreen() {
    bindToggle("pref-location-sharing", "locationSharing");
    bindToggle("pref-save-history", "saveLocationHistory");
    bindToggle("pref-share-speed", "shareSpeed");
    bindToggle("pref-share-heading", "shareHeading");
    bindToggle("pref-auto-delete", "autoDeleteRideData");
    bindToggle("pref-auto-expire", "autoExpireRide");
    bindToggle("pref-hide-identity", "hideRiderIdentity");
    bindToggle("pref-confirm-join", "requireJoinConfirmation");
    bindText("pref-alias", "displayAlias");

    const clearBtn = document.getElementById("btn-clear-data");
    if (clearBtn) {
      clearBtn.addEventListener("click", () => {
        if (confirm("Delete all local ride data on this device? This cannot be undone.")) {
          // On-device this calls into the bridge to wipe schema.sql tables
          // and any EncryptedSharedPreferences-held keys; here we only
          // clear the demo settings store to keep the concept honest.
          localStorage.removeItem(STORAGE_KEY);
          current = { ...defaults };
          global.RidersLive.toast("Local ride data deleted");
          initSettingsScreen();
        }
      });
    }
  }

  global.RidersLive = global.RidersLive || {};
  global.RidersLive.settings = { get: () => current, initSettingsScreen };
})(window);
