package com.riderslive.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * PermissionsManager — every permission this app can ever request, and
 * nothing else (master prompt §41). Each request should be preceded, in
 * the UI layer, by a short explanation of *why* — e.g. "Bluetooth
 * permission lets your phone find other riders in your group nearby."
 * ACCESS_BACKGROUND_LOCATION is only ever requested when the user first
 * starts an active ride, never at first app launch.
 */
object PermissionsManager {

    fun locationPermissions(): Array<String> = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    fun bluetoothPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            // Pre-API 31 BLE scanning is gated on location permission only.
            arrayOf()
        }

    fun backgroundLocationPermission(): String = Manifest.permission.ACCESS_BACKGROUND_LOCATION

    fun notificationPermission(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        } else arrayOf()

    fun hasAll(context: Context, permissions: Array<String>): Boolean =
        permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    fun hasLocationPermissions(context: Context): Boolean = hasAll(context, locationPermissions())

    fun hasBluetoothPermissions(context: Context): Boolean = hasAll(context, bluetoothPermissions())

    fun hasBackgroundLocation(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, backgroundLocationPermission()) == PackageManager.PERMISSION_GRANTED

    /**
     * Returns the permission rationale copy to show BEFORE the system
     * dialog appears — never request a permission cold with no
     * explanation.
     */
    fun rationaleFor(permission: String): String = when (permission) {
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION ->
            "Location is used to show your position on the ride map and share it with your group. It is never sent anywhere without your active participation in a ride."
        Manifest.permission.ACCESS_BACKGROUND_LOCATION ->
            "Background location keeps sharing your position with your group while your phone is locked or in your pocket during an active ride. This is requested only when you start a ride, and you can pause sharing anytime."
        Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT ->
            "Bluetooth is used to find and connect to other riders in your group nearby, without needing mobile data or Wi-Fi."
        Manifest.permission.POST_NOTIFICATIONS ->
            "Notifications let you know when a rider joins, leaves, or the destination changes while the app is in the background."
        else -> "This permission is required for a feature you just used."
    }
}
