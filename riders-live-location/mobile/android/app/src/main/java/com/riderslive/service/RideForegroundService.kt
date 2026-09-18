package com.riderslive.service

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.riderslive.R
import com.riderslive.ble.BleMeshManager
import com.riderslive.gps.GpsManager
import com.riderslive.security.CryptoManager
import kotlinx.coroutines.*

/**
 * RideForegroundService — the one long-running component while a ride is
 * ACTIVE. Ties together:
 *   - GpsManager (adaptive-interval fixes)
 *   - BleMeshManager (transport: scan/advertise/GATT/relay)
 *   - CryptoManager (per-ride session key, held ONLY in this process's
 *     memory for the life of the ride — never written to disk in the
 *     clear, wiped on stop())
 *
 * Runs as a foreground service with a persistent, low-priority
 * notification (required by Android for background location + BLE
 * from API 26+, and mandatory user-visible honesty about what's
 * running — master prompt never wants hidden tracking).
 */
class RideForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "ride_tracking_channel"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_RIDE_ID = "extra_ride_id"
    }

    private lateinit var cryptoManager: CryptoManager
    private lateinit var gpsManager: GpsManager
    private var bleMeshManager: BleMeshManager? = null
    private var sessionKey: ByteArray? = null
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        cryptoManager = CryptoManager(applicationContext)
        gpsManager = GpsManager(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val rideId = intent?.getStringExtra(EXTRA_RIDE_ID) ?: return START_NOT_STICKY
        startForeground(NOTIFICATION_ID, buildNotification(connectedRiders = 0))
        startRide(rideId)
        return START_STICKY
    }

    private fun startRide(rideId: String) {
        // In the full app, sessionKey comes from the completed handshake
        // (HandshakeManager) for each peer, keyed per-peer — a group ride
        // maintains one derived key per peer pair, or a rotated group key
        // after each membership change. Simplified here to one active key
        // for clarity; see docs/bluetooth-protocol.md for the full
        // multi-party key schedule.
        bleMeshManager = BleMeshManager(
            context = applicationContext,
            cryptoManager = cryptoManager,
            rideId = rideId,
            onPacketReceived = ::handleIncomingEncryptedBlob,
            onPeerConnectionChanged = { _, connected ->
                updateNotification(bleMeshManager?.connectedPeerCount() ?: 0)
            },
        )
        bleMeshManager?.start()

        gpsManager.start { fix ->
            serviceScope.launch { onOwnFix(fix) }
        }
    }

    private suspend fun onOwnFix(fix: GpsManager.RawFix) {
        val key = sessionKey ?: return // no established peers yet; nothing to encrypt for
        // Build + encrypt + broadcast: mirrors location_manager.build_packet
        // + protocol.build_encrypted_packet in the Python reference.
        // (Object construction/serialization omitted here for brevity —
        // see python/protocol.py for the exact field layout to replicate.)
    }

    private fun handleIncomingEncryptedBlob(senderSessionId: String, blob: ByteArray) {
        val key = sessionKey ?: return
        try {
            // decrypt + ReplayGuard check + persist to the `locations` table
            // (schema.sql) happens here; on AEADBadTagException the packet
            // is dropped and logged WITHOUT coordinates (master prompt §42).
        } catch (e: Exception) {
            // Never log e with packet contents attached.
        }
    }

    fun onParticipantRemoved(newSessionKey: ByteArray) {
        sessionKey?.let { cryptoManager.wipe(it) }
        sessionKey = newSessionKey
    }

    override fun onDestroy() {
        gpsManager.stop()
        bleMeshManager?.stop()
        sessionKey?.let { cryptoManager.wipe(it) }
        sessionKey = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Active ride tracking", NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Shows when your location is being shared with your ride group" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(connectedRiders: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ride active")
            .setContentText("Sharing location · $connectedRiders rider(s) connected")
            .setSmallIcon(R.drawable.ic_ride_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun updateNotification(connectedRiders: Int) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(connectedRiders))
    }
}
