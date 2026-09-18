package com.riderslive

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import com.riderslive.permissions.PermissionsManager
import com.riderslive.service.RideForegroundService
import org.json.JSONObject

/**
 * MainActivity — hosts the HTML5/CSS3/JavaScript frontend (frontend/)
 * inside a WebView and exposes a narrow JS bridge to it, per the master
 * prompt's tech-stack decision: "If using a web-based frontend, use a
 * native mobile wrapper/bridge rather than pretending browser APIs
 * provide unrestricted Bluetooth access."
 *
 * The web layer never talks to Bluetooth or GPS hardware directly (it
 * can't — WebView has no BLE peripheral/central API); every hardware
 * action goes through `window.AndroidBridge.*` calls defined below,
 * and results/events are pushed back into the page via
 * `webView.evaluateJavascript(...)`. The same frontend code also runs
 * standalone in a desktop browser for UI development, where
 * frontend/js/bluetooth-ui.js detects the missing bridge and falls
 * back to a local simulation — see that file's header comment.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var activeRide: Ride? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")
        webView.loadUrl("file:///android_asset/frontend/index.html")
    }

    /** All methods here are called from JS as `AndroidBridge.methodName(...)`.
     * Every method returns quickly and pushes real results back via a JS
     * callback event rather than blocking — GATT/GPS calls are async. */
    inner class AndroidBridge {

        @JavascriptInterface
        fun hasRequiredPermissions(): Boolean =
            PermissionsManager.hasLocationPermissions(this@MainActivity) &&
                PermissionsManager.hasBluetoothPermissions(this@MainActivity)

        @JavascriptInterface
        fun requestPermissions() {
            runOnUiThread {
                val needed = (PermissionsManager.locationPermissions() + PermissionsManager.bluetoothPermissions() + PermissionsManager.notificationPermission())
                androidx.core.app.ActivityCompat.requestPermissions(this@MainActivity, needed, 1001)
            }
        }

        @JavascriptInterface
        fun createRide(displayName: String): String {
            val app = application as RidersLiveApplication
            val ride = Ride(rideId = java.util.UUID.randomUUID().toString(), code = generateSharedCode())
            val primary = ride.createAsPrimary(displayName, app.cryptoManager)
            activeRide = ride
            return JSONObject()
                .put("rideId", ride.rideId)
                .put("code", ride.code)
                .put("riderId", primary.riderId)
                .toString()
        }

        @JavascriptInterface
        fun startRide() {
            val ride = activeRide ?: return
            if (ride.state == RideState.CREATED) ride.openForJoining()
            ride.start()
            val intent = Intent(this@MainActivity, RideForegroundService::class.java)
                .putExtra(RideForegroundService.EXTRA_RIDE_ID, ride.rideId)
            androidx.core.content.ContextCompat.startForegroundService(this@MainActivity, intent)
            notifyJs("rideStateChanged", JSONObject().put("state", ride.state.name))
        }

        @JavascriptInterface
        fun endRide() {
            val ride = activeRide ?: return
            val app = application as RidersLiveApplication
            ride.end(app.cryptoManager)
            stopService(Intent(this@MainActivity, RideForegroundService::class.java))
            notifyJs("rideStateChanged", JSONObject().put("state", ride.state.name))
            activeRide = null
        }

        @JavascriptInterface
        fun setDestination(riderId: String, lat: Double, lon: Double, name: String?) {
            val ride = activeRide ?: return
            try {
                val dest = ride.setDestination(riderId, lat, lon, name)
                notifyJs(
                    "destinationChanged",
                    JSONObject().put("lat", dest.latitude).put("lon", dest.longitude).put("name", dest.name ?: ""),
                )
            } catch (e: UnauthorizedError) {
                notifyJs("error", JSONObject().put("message", "Only the primary rider can set the destination"))
            }
        }

        @JavascriptInterface
        fun removeParticipant(actingRiderId: String, targetRiderId: String) {
            val ride = activeRide ?: return
            val app = application as RidersLiveApplication
            try {
                ride.removeParticipant(actingRiderId, targetRiderId, app.cryptoManager, emptyMap())
                notifyJs("participantRemoved", JSONObject().put("riderId", targetRiderId))
            } catch (e: UnauthorizedError) {
                notifyJs("error", JSONObject().put("message", "Only the primary rider can remove a participant"))
            }
        }

        private fun generateSharedCode(): String {
            val digits = java.security.SecureRandom()
            return (1..6).map { digits.nextInt(10) }.joinToString("")
        }
    }

    private fun notifyJs(eventName: String, payload: JSONObject) {
        runOnUiThread {
            val js = "window.dispatchEvent(new CustomEvent('$eventName', { detail: $payload }));"
            webView.evaluateJavascript(js, null)
        }
    }
}
