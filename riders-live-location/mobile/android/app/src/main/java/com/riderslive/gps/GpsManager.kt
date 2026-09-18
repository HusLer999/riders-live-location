package com.riderslive.gps

import android.annotation.SuppressLint
import android.content.Context
import android.os.BatteryManager
import com.google.android.gms.location.*

/**
 * GpsManager — wraps FusedLocationProviderClient and re-requests updates
 * at a new interval whenever the adaptive policy (mirrors
 * python/location_manager.AdaptiveIntervalConfig) decides the rate
 * should change. Mobile OS background-location rules are respected:
 * this class is only ever started from RideForegroundService while a
 * ride is ACTIVE, never as a bare background listener.
 */
class GpsManager(private val context: Context) {

    data class Config(
        val movingIntervalMs: Long = 2_000,
        val stationaryIntervalMs: Long = 10_000,
        val lowBatteryIntervalMs: Long = 20_000,
        val lowBatteryThresholdPct: Int = 20,
        val stationarySpeedThresholdMps: Float = 1.0f,
    )

    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private var currentIntervalMs: Long = -1
    private var callback: LocationCallback? = null
    private var config = Config()

    private fun batteryPercent(): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun desiredIntervalMs(lastSpeedMps: Float?): Long {
        if (batteryPercent() <= config.lowBatteryThresholdPct) return config.lowBatteryIntervalMs
        val moving = (lastSpeedMps ?: 0f) >= config.stationarySpeedThresholdMps
        return if (moving) config.movingIntervalMs else config.stationaryIntervalMs
    }

    @SuppressLint("MissingPermission") // caller MUST check PermissionsManager.hasLocationPermissions() first
    fun start(onFix: (fix: RawFix) -> Unit) {
        restartWithInterval(config.movingIntervalMs, onFix)
    }

    @SuppressLint("MissingPermission")
    private fun restartWithInterval(intervalMs: Long, onFix: (fix: RawFix) -> Unit) {
        callback?.let { fusedClient.removeLocationUpdates(it) }
        currentIntervalMs = intervalMs

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .build()

        callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                val fix = RawFix(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    speedMps = if (location.hasSpeed()) location.speed else null,
                    headingDeg = if (location.hasBearing()) location.bearing else null,
                    accuracyM = location.accuracy,
                    timestampEpochSeconds = location.time / 1000.0,
                )
                onFix(fix)

                val nextInterval = desiredIntervalMs(fix.speedMps)
                if (nextInterval != currentIntervalMs) {
                    restartWithInterval(nextInterval, onFix)
                }
            }
        }
        fusedClient.requestLocationUpdates(request, callback!!, context.mainLooper)
    }

    fun stop() {
        callback?.let { fusedClient.removeLocationUpdates(it) }
        callback = null
    }

    data class RawFix(
        val latitude: Double,
        val longitude: Double,
        val speedMps: Float?,
        val headingDeg: Float?,
        val accuracyM: Float,
        val timestampEpochSeconds: Double,
    )
}
