package com.treadmill.bridge

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.min

/**
 * Foreground service that owns the treadmill bridge lifecycle.
 * Survives Activity destruction — the USB connection, Dircon server,
 * and mDNS advertiser run here independently of the UI.
 */
class BridgeService : Service() {
    companion object {
        private const val TAG = "BridgeService"
        private const val ACTION_USB_PERMISSION = "com.treadmill.bridge.USB_PERMISSION"
        private const val DIRCON_PORT = 36866
        private const val MAX_BACKOFF_MS = 16_000L
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "bridge_channel"
    }

    inner class LocalBinder : Binder() {
        val service: BridgeService get() = this@BridgeService
    }
    private val binder = LocalBinder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var connectJob: Job? = null

    // --- Published state for the UI ---

    data class BridgeStatus(
        val statusMsg: String = "Starting…",
        val statusColor: Int = R.color.status_yellow,
        val metricsMsg: String = "",
        val readCount: Int = 0,
        val errorCount: Int = 0
    )

    private val _status = MutableStateFlow(BridgeStatus())
    val status: StateFlow<BridgeStatus> get() = _status

    // --- Lifecycle ---

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting…"))
        registerReceiver(usbReceiver, IntentFilter(ACTION_USB_PERMISSION))
        findAndConnect()
    }

    override fun onDestroy() {
        super.onDestroy()
        connectJob?.cancel()
        scope.cancel()
        teardown()
        unregisterReceiver(usbReceiver)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    // --- Notification ---

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Treadmill Bridge", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else
            Notification.Builder(this)
        return builder
            .setContentTitle("Treadmill Bridge")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_treadmill)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    // --- Internal types ---

    /** Owns a claimed USB interface so teardown always releases it. */
    private class UsbSession(
        val connection: UsbDeviceConnection,
        val intf: UsbInterface
    ) {
        fun close() {
            connection.releaseInterface(intf)
            connection.close()
        }
    }

    private sealed class BridgeState {
        object Disconnected : BridgeState()
        data class Connected(
            val usbSession: UsbSession,
            val fitPro1: FitPro1,
            val dirconServer: DirconServer,
            val mdnsAdvertiser: MdnsAdvertiser
        ) : BridgeState()
    }

    private var bridge: BridgeState = BridgeState.Disconnected
    private var readCount = 0
    private var errorCount = 0

    // --- USB permission ---

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION) {
                @Suppress("DEPRECATION")
                val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted && device != null) connectWithRetry(device)
                else updateStatus("USB permission denied", R.color.status_red)
            }
        }
    }

    // --- Connection lifecycle ---

    private fun teardown() {
        val current = bridge
        bridge = BridgeState.Disconnected
        if (current is BridgeState.Connected) {
            current.fitPro1.stopUsbLoop()
            current.dirconServer.stop()
            current.mdnsAdvertiser.stop()
            current.usbSession.close()
        }
    }

    private fun findAndConnect() {
        scope.launch {
            while (isActive) {
                val usbManager = getSystemService(USB_SERVICE) as UsbManager
                val device = usbManager.deviceList.values
                    .firstOrNull { it.vendorId == TreadmillProfile.USB_VENDOR_ID && it.productId == TreadmillProfile.USB_PRODUCT_ID }
                if (device != null) {
                    if (usbManager.hasPermission(device)) connectWithRetry(device)
                    else {
                        usbManager.requestPermission(device,
                            PendingIntent.getBroadcast(this@BridgeService, 0,
                                Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE))
                        updateStatus("Requesting USB permission…", R.color.status_yellow)
                    }
                    return@launch
                }
                updateStatus("No motor controller found, retrying…", R.color.status_yellow)
                delay(2000)
            }
        }
    }

    private fun connectWithRetry(device: UsbDevice) {
        connectJob?.cancel()
        connectJob = scope.launch {
            var attempt = 0
            while (isActive) {
                attempt++
                if (tryConnect(device)) return@launch

                teardown()
                val delayMs = min(1000L * (1 shl min(attempt - 1, 4)), MAX_BACKOFF_MS)
                updateStatus("Retrying in ${delayMs / 1000}s… (attempt $attempt)", R.color.status_yellow)
                delay(delayMs)
            }
        }
    }

    private suspend fun tryConnect(device: UsbDevice): Boolean = withContext(Dispatchers.IO) {
        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        val connection = usbManager.openDevice(device) ?: return@withContext false
        val intf = device.getInterface(0)

        if (!connection.claimInterface(intf, true)) {
            Log.e(TAG, "claimInterface failed")
            connection.close()
            return@withContext false
        }
        val session = UsbSession(connection, intf)

        if (intf.endpointCount < 2) { session.close(); return@withContext false }

        withContext(Dispatchers.Main) { updateStatus("Handshaking…", R.color.status_yellow) }

        val writeEp = intf.getEndpoint(1)
        val readEp = intf.getEndpoint(0)
        val transport = object : UsbTransport {
            override fun write(data: ByteArray) =
                connection.bulkTransfer(writeEp, data, data.size, 50) >= 0
            override fun read(buf: ByteArray) =
                connection.bulkTransfer(readEp, buf, buf.size, 50)
        }
        val fp = FitPro1(transport)

        if (!fp.handshake()) { session.close(); return@withContext false }
        withContext(Dispatchers.Main) { updateStatus("Initializing…", R.color.status_yellow) }
        if (!fp.initialize()) { session.close(); return@withContext false }

        fp.onStateUpdate = { state ->
            readCount++
            val modeName = fp.workoutModeName(state.workoutMode)
            val clients = (bridge as? BridgeState.Connected)?.dirconServer?.clientCount ?: 0
            val snapshot = fp.snapshotFlow.value
            val metrics = "Speed:   %.1f km/h\nIncline: %.1f%%\nMode:    %s\nDist:    %dm\nTime:    %ds\nStart:   %s\nZwift:   %d client%s"
                .format(state.speedKPH, state.inclinePct, modeName, snapshot.distanceM, snapshot.elapsedSec,
                    if (state.startRequested) "yes" else "no", clients, if (clients != 1) "s" else "")
            _status.value = BridgeStatus("Ready — Dircon :$DIRCON_PORT", R.color.status_green, metrics, readCount, errorCount)
        }

        // BridgeService owns reconnection: observe FitPro1's terminal failure signal
        scope.launch {
            val e = fp.terminalFailure.await()
            Log.e(TAG, "USB error, reconnecting", e)
            errorCount++
            teardown()
            connectWithRetry(device)
        }

        val server = DirconServer(DIRCON_PORT, { fp.snapshotFlow.value }) { command, params ->
            handleControlCommand(fp, command, params)
        }
        server.start()

        fp.startUsbLoop()

        withContext(Dispatchers.Main) {
            val advertiser = startMdns()
            bridge = BridgeState.Connected(session, fp, server, advertiser)
            updateStatus("Ready — Dircon :$DIRCON_PORT", R.color.status_green)
        }
        return@withContext true
    }

    private suspend fun handleControlCommand(fp: FitPro1, command: DirconServer.FtmsCommand, params: ByteArray): Boolean {
        return try {
            when (command) {
                DirconServer.FtmsCommand.SetSpeed -> {
                    if (params.size >= 2) {
                        val speed = params.leU16At(0) / 100.0
                        Log.d(TAG, "Zwift set speed: $speed km/h")
                        withTimeout(2000) { fp.setSpeed(speed).await() }
                    } else false
                }
                DirconServer.FtmsCommand.SetIncline -> {
                    if (params.size >= 2) {
                        val incline = params.leS16At(0) / 10.0
                        Log.d(TAG, "Zwift set incline: $incline%")
                        withTimeout(2000) { fp.setIncline(incline).await() }
                    } else false
                }
                DirconServer.FtmsCommand.Start -> {
                    Log.d(TAG, "Zwift: start")
                    withTimeout(2000) { fp.startWorkout(TreadmillProfile.MIN_SPEED_KPH, 0.0).await() }
                }
                DirconServer.FtmsCommand.Stop -> {
                    Log.d(TAG, "Zwift: stop")
                    withTimeout(2000) { fp.stopWorkout().await() }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Control command error", e)
            false
        }
    }

    private fun intToIpBytes(ip: Int) = byteArrayOf(
        (ip and 0xFF).toByte(),
        ((ip shr 8) and 0xFF).toByte(),
        ((ip shr 16) and 0xFF).toByte(),
        ((ip shr 24) and 0xFF).toByte()
    )

    private fun startMdns(): MdnsAdvertiser {
        @Suppress("DEPRECATION")
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val wifiInfo = wifiManager.connectionInfo
        @Suppress("DEPRECATION")
        val ipBytes = intToIpBytes(wifiInfo.ipAddress)
        val mac = wifiInfo.macAddress ?: "00:00:00:00:00:00"
        Log.d(TAG, "WiFi IP: ${ipBytes.joinToString(".") { (it.toInt() and 0xFF).toString() }}, MAC: $mac")

        return MdnsAdvertiser(this, "Treadmill Bridge", DIRCON_PORT, ipBytes, mac).also { it.start() }
    }

    private fun updateStatus(msg: String, colorRes: Int) {
        Log.d(TAG, msg)
        _status.value = _status.value.copy(statusMsg = msg, statusColor = colorRes)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(msg))
    }
}
