/**
 * ride.js — thin state layer above RidersLive.bridge. Screens read from
 * RidersLive.ride rather than calling the bridge directly, so app.js
 * doesn't need to know whether it's talking to native Android or the
 * in-browser simulation.
 */
(function (global) {
  const bridge = global.RidersLive.bridge;

  const ride = {
    rideId: null,
    code: null,
    myRiderId: null,
    role: null, // 'PRIMARY' | 'RIDER'
    state: "NONE", // mirrors RideState in ride_manager.py/RideManager.kt
    destination: null,

    createAsPrimary(displayName) {
      const result = bridge.createRide(displayName);
      this.rideId = result.rideId;
      this.code = result.code;
      this.myRiderId = result.riderId;
      this.role = "PRIMARY";
      this.state = "CREATED";
      return result;
    },

    start() {
      bridge.startRide();
    },

    end() {
      bridge.endRide();
      this.rideId = null;
      this.code = null;
      this.role = null;
      this.state = "NONE";
      this.destination = null;
    },

    setDestination(lat, lon, name) {
      if (this.role !== "PRIMARY") {
        global.RidersLive.toast("Only the primary rider can set the destination");
        return;
      }
      bridge.setDestination(this.myRiderId, lat, lon, name);
    },

    removeParticipant(targetRiderId) {
      if (this.role !== "PRIMARY") {
        global.RidersLive.toast("Only the primary rider can remove a participant");
        return;
      }
      bridge.removeParticipant(this.myRiderId, targetRiderId);
    },
  };

  bridge.subscribe("rideStateChanged", (detail) => {
    ride.state = detail.state;
  });
  bridge.subscribe("destinationChanged", (detail) => {
    ride.destination = detail;
  });
  bridge.subscribe("error", (detail) => {
    global.RidersLive.toast(detail.message, "warning");
  });

  global.RidersLive.ride = ride;
})(window);
