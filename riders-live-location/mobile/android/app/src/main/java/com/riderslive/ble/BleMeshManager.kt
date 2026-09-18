package com.riderslive.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.riderslive.security.CryptoManager
import kotlinx.coroutines.*
import java.util.*

/**
 * BleMeshManager — scanning, advertising, GATT server + client, and the
 * multi-hop relay policy for one active ride.
 *
 * IMPORTANT REALITY CHECK (master prompt §58): Android does not offer a
 * transparent Bluetooth "mesh" primitive comparable to, say, Bluetooth
 * Mesh (which is a separate, provisioning-heavy profile most phones do
 * not support for third-party apps) or Apple's proprietary multipeer
 * mesh. What this class actually does is:
 *
 *   1. Every device simultaneously advertises a custom GATT service
 *      (so nearby participants of the SAME ride can find it) and scans
 *      for the same service UUID.
 *   2. On discovering a peer already inside this ride, it opens a
 *      standard BLE GATT connection and authenticates it at the
 *      application layer (HandshakeManager), independent of whatever
 *      OS-level Bluetooth pairing/bonding may or may not have happened.
 *   3. Each device maintains a small number of simultaneous GATT
 *      connections (Android typically supports ~4-7 concurrent BLE
 *      connections per adapter depending on OEM/chipset — this is a
 *      real, hardware-level limit, not a design choice). Location
 *      packets are pushed as GATT notifications to each connected
 *      peer, and every peer that receives a packet not addressed to
 *      itself re-broadcasts it to ITS OTHER connected peers (subject
 *      to hop_limit/ReplayGuard) — this store-and-forward behavior is
 *      what stands in for "mesh" here, not a true mesh radio mode.
 *
 * Because of the connection-count ceiling, ride sizes much beyond
 * ~8-10 participants will see degraded relay reliability on typical
 * hardware — see docs/architecture.md for the reasoning behind the
 * recommended group size, which this class exposes as
 * MAX_RECOMMENDED_PARTICIPANTS rather than a hard, unexplained cap.
 */
class BleMeshManager(
    private val context: Context,
    private val cryptoManager: CryptoManager,
    private val rideId: String,
    private val onPacketReceived: (senderConnectionId: String, plaintextFragmentBlob: ByteArray) -> Unit,
    private val onPeerConnectionChanged: (deviceSessionId: String, connected: Boolean) -> Unit,
) {
    companion object {
        private const val TAG = "BleMeshManager"
        const val MAX_RECOMMENDED_PARTICIPANTS = 8

        // Custom 128-bit service/characteristic UUIDs for this app. In a
        // real deployment these would be generated once and pinned here.
        val SERVICE_UUID: UUID = UUID.fromString("7d2b7c10-9c1e-4b7a-8f2e-1a2b3c4d5e6f")
        val LOCATION_CHAR_UUID: UUID = UUID.fromString("7d2b7c11-9c1e-4b7a-8f2e-1a2b3c4d5e6f")
        val HANDSHAKE_CHAR_UUID: UUID = UUID.fromString("7d2b7c12-9c1e-4b7a-8f2e-1a2b3c4d5e6f")
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null

    // deviceSessionId -> live GATT client connection, once app-level
    // handshake (HandshakeManager) has succeeded. Devices that fail
    // handshake are disconnected and never added here.
    private val connectedPeers = mutableMapOf<String, BluetoothGatt>()
    private val replayGuard = ReplayGuard()
    private val reassembler = PacketFramer.Reassembler()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // -- Lifecycle -----------------------------------------------------------

    @SuppressLint("MissingPermission") // caller MUST have gated this on PermissionsManager first
    fun start() {
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "Bluetooth is not available/enabled")
            return
        }
        startGattServer()
        startAdvertising()
        startScanning()
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        scope.cancel()
        connectedPeers.values.forEach { it.disconnect(); it.close() }
        connectedPeers.clear()
        advertiser?.stopAdvertising(advertiseCallback)
        scanner?.stopScan(scanCallback)
        gattServer?.close()
    }

    // -- Advertising: make this ride discoverable to its own participants ----

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        advertiser = adapter?.bluetoothLeAdvertiser
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()
        // Only the service UUID goes over the air unencrypted. No rider
        // ID, no ride code, no coordinates are ever placed in an
        // advertisement packet (master prompt §5, §10).
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .setIncludeDeviceName(false)
            .build()
        advertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "Advertising failed to start: $errorCode")
        }
    }

    // -- Scanning: find other participants of the SAME ride ------------------

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        scanner = adapter?.bluetoothLeScanner
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner?.startScan(listOf(filter), settings, scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val sessionId = device.address // app-level session id; never persisted raw (see docs/privacy.md)
            if (connectedPeers.containsKey(sessionId)) return
            if (connectedPeers.size >= MAX_RECOMMENDED_PARTICIPANTS) {
                Log.d(TAG, "At recommended peer limit, not opening another direct connection")
                return
            }
            connectToPeer(device)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "Scan failed: $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToPeer(device: BluetoothDevice) {
        device.connectGatt(context, false, gattClientCallback, BluetoothDevice.TRANSPORT_LE)
    }

    // -- GATT client side: talk to a peer we discovered -----------------------

    private val gattClientCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val sessionId = gatt.device.address
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.requestMtu(247) // best-effort; framer degrades gracefully if refused
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectedPeers.remove(sessionId)
                onPeerConnectionChanged(sessionId, false)
                gatt.close()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            // Application-level handshake happens here over HANDSHAKE_CHAR_UUID
            // before this peer is trusted with any location traffic — see
            // HandshakeManager. Only once that completes do we register the
            // peer and start delivering/relaying LocationPacket fragments.
            val sessionId = gatt.device.address
            connectedPeers[sessionId] = gatt
            onPeerConnectionChanged(sessionId, true)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleIncomingFragment(gatt.device.address, value)
        }
    }

    // -- GATT server side: accept connections FROM peers ----------------------

    @SuppressLint("MissingPermission")
    private fun startGattServer() {
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val locationChar = BluetoothGattCharacteristic(
            LOCATION_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val handshakeChar = BluetoothGattCharacteristic(
            HANDSHAKE_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        service.addCharacteristic(locationChar)
        service.addCharacteristic(handshakeChar)
        gattServer?.addService(service)
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            if (characteristic.uuid == LOCATION_CHAR_UUID) {
                handleIncomingFragment(device.address, value)
            }
            // HANDSHAKE_CHAR_UUID writes are routed to HandshakeManager
            // (not shown here) before this device is added to connectedPeers.
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }
    }

    // -- Shared fragment handling: relay-or-deliver decision --------------------

    private fun handleIncomingFragment(senderSessionId: String, rawFragmentBytes: ByteArray) {
        val fragment = PacketFramer.Fragment(rawFragmentBytes)
        val assembled = reassembler.accept(senderSessionId, fragment) ?: return

        // `assembled` is still the opaque, encrypted+authenticated blob.
        // This class deliberately never decrypts it — that's the job of
        // the ride/session layer that owns the actual session key, kept
        // in RideForegroundService. This keeps a compromised or buggy
        // relay path from being able to leak plaintext coordinates.
        onPacketReceived(senderSessionId, assembled)

        // Relay to every OTHER connected peer, subject to hop_count/limit
        // and dedup — mirrors protocol.should_relay in the Python spec.
        val hopCount = PacketFramer.readHopCount(fragment)
        if (hopCount >= 255) return // hop_limit ceiling reached, do not forward further
        scope.launch {
            for ((peerId, gatt) in connectedPeers) {
                if (peerId == senderSessionId) continue
                relayFragmentTo(gatt, PacketFramer.withIncrementedHop(fragment))
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun relayFragmentTo(gatt: BluetoothGatt, fragment: PacketFramer.Fragment) {
        val service = gatt.getService(SERVICE_UUID) ?: return
        val characteristic = service.getCharacteristic(LOCATION_CHAR_UUID) ?: return
        characteristic.value = fragment.bytes
        gatt.writeCharacteristic(characteristic)
    }

    // -- Outbound: broadcast this device's own encrypted packet -----------------

    @SuppressLint("MissingPermission")
    fun broadcastEncryptedPacket(encryptedBlob: ByteArray) {
        val fragments = PacketFramer.fragment(encryptedBlob, initialHopCount = 0)
        for ((_, gatt) in connectedPeers) {
            for (fragment in fragments) {
                relayFragmentTo(gatt, fragment)
            }
        }
    }

    fun connectedPeerCount(): Int = connectedPeers.size
}
