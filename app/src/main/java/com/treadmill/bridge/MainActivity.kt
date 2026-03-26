package com.treadmill.bridge

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.app.Activity
import android.widget.TextView
import android.util.Log
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    companion object {
        private const val TAG = "TreadmillBridge"
        private const val ACTION_USB_PERMISSION = "com.treadmill.bridge.USB_PERMISSION"
        private const val DIRCON_PORT = 36866
    }

    private lateinit var statusText: TextView
    private var fitPro1: FitPro1? = null
    private var dirconServer: DirconServer? = null
    private var mdnsAdvertiser: MdnsAdvertiser? = null
    private val handler = Handler(Looper.getMainLooper())
    private var readCount = 0
    private var errorCount = 0

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION) {
                val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted && device != null) connectToDevice(device)
                else status("USB permission denied")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        statusText = TextView(this).apply { textSize = 18f; setPadding(32, 32, 32, 32) }
        setContentView(statusText)
        registerReceiver(usbReceiver, IntentFilter(ACTION_USB_PERMISSION))
        status("Looking for motor controller...")
        findAndConnect()
    }

    override fun onDestroy() {
        super.onDestroy()
        fitPro1?.stopUsbLoop()
        dirconServer?.stop()
        mdnsAdvertiser?.stop()
        unregisterReceiver(usbReceiver)
    }

    private fun findAndConnect() {
        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        val device = usbManager.deviceList.values.firstOrNull { it.vendorId == 8508 && it.productId == 2 }
        if (device == null) { status("No motor controller found"); return }
        if (usbManager.hasPermission(device)) connectToDevice(device)
        else {
            usbManager.requestPermission(device,
                PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE))
            status("Requesting USB permission...")
        }
    }

    private fun connectToDevice(device: UsbDevice) {
        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        val connection = usbManager.openDevice(device) ?: run { status("Failed to open USB"); return }
        val intf = device.getInterface(0)
        connection.claimInterface(intf, true)
        if (intf.endpointCount < 2) { status("Need 2 endpoints"); return }

        status("Handshaking...")

        Thread {
            val writeEp = intf.getEndpoint(1)
            val readEp = intf.getEndpoint(0)
            val transport = object : UsbTransport {
                override fun write(data: ByteArray) =
                    connection.bulkTransfer(writeEp, data, data.size, 50) >= 0
                override fun read(buf: ByteArray) =
                    connection.bulkTransfer(readEp, buf, buf.size, 50)
            }
            val fp = FitPro1(transport)
            if (!fp.handshake()) { handler.post { status("Handshake FAILED") }; return@Thread }
            handler.post { status("Initializing...") }

            fp.initialize()
            fitPro1 = fp

            // UI updates from USB loop
            fp.onStateUpdate = { state ->
                readCount++
                val clients = dirconServer?.clientCount ?: 0
                val elapsed = if (fp.lastSnapshot.elapsedSec > 0) fp.lastSnapshot.elapsedSec else 0
                val modeName = fp.workoutModeName(state.workoutMode)
                handler.post {
                    status("Speed: %.1f km/h  Incline: %.1f%%\nMode: $modeName  Dist: %dm  Time: %ds\nStart: ${state.startRequested}  Zwift: $clients\n\nReads: $readCount  Errors: $errorCount"
                        .format(state.speedKPH, state.inclinePct, fp.lastSnapshot.distanceM, elapsed))
                }
            }

            // Start Dircon server — reads cached snapshot, never touches USB
            val server = DirconServer(DIRCON_PORT) { fp.lastSnapshot }
            server.onControlCommand = { opcode, params -> handleControlCommand(fp, opcode, params) }
            server.start()
            dirconServer = server

            // Start USB loop (single thread owns USB from here on)
            fp.startUsbLoop()

            handler.post {
                startMdns()
                status("Ready — Dircon :$DIRCON_PORT")
            }
        }.start()
    }

    private fun handleControlCommand(fp: FitPro1, opcode: Int, params: ByteArray): Boolean {
        return try {
            when (opcode) {
                0x02 -> { // Set target speed
                    if (params.size >= 2) {
                        val speed = ((params[0].toInt() and 0xFF) or ((params[1].toInt() and 0xFF) shl 8)) / 100.0
                        Log.d(TAG, "Zwift set speed: $speed km/h")
                        fp.setSpeed(speed).get(2, TimeUnit.SECONDS) // block for result
                    } else false
                }
                0x03 -> { // Set target incline
                    if (params.size >= 2) {
                        val incline = ((params[0].toInt() and 0xFF) or ((params[1].toInt() and 0xFF) shl 8)).toShort() / 10.0
                        Log.d(TAG, "Zwift set incline: $incline%")
                        fp.setIncline(incline).get(2, TimeUnit.SECONDS)
                    } else false
                }
                0x07 -> { Log.d(TAG, "Zwift: start"); fp.startWorkout(FitPro1.MIN_SPEED_KPH, 0.0).get(2, TimeUnit.SECONDS) }
                0x08 -> { Log.d(TAG, "Zwift: stop"); fp.stopWorkout().get(2, TimeUnit.SECONDS) }
                else -> { Log.d(TAG, "Unknown opcode: $opcode"); false }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Control command error", e)
            false
        }
    }

    private fun startMdns() {
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val wifiInfo = wifiManager.connectionInfo
        val ip = wifiInfo.ipAddress
        val ipBytes = byteArrayOf(
            (ip and 0xFF).toByte(),
            ((ip shr 8) and 0xFF).toByte(),
            ((ip shr 16) and 0xFF).toByte(),
            ((ip shr 24) and 0xFF).toByte()
        )
        val mac = wifiInfo.macAddress ?: "00:00:00:00:00:00"
        Log.d(TAG, "WiFi IP: ${ipBytes.joinToString(".") { (it.toInt() and 0xFF).toString() }}, MAC: $mac")

        mdnsAdvertiser = MdnsAdvertiser(this, "Treadmill Bridge", DIRCON_PORT, ipBytes, mac).also { it.start() }
    }

    private fun status(msg: String) { Log.d(TAG, msg); statusText.text = msg }
}
