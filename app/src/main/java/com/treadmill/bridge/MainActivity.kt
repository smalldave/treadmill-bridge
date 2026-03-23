package com.treadmill.bridge

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.app.Activity
import android.widget.TextView
import android.util.Log

/**
 * Minimal test app: connects to the treadmill motor controller via USB,
 * reads speed and incline using the FitPro1 protocol, and displays them.
 */
class MainActivity : Activity() {
    companion object {
        private const val TAG = "TreadmillBridge"
        private const val ACTION_USB_PERMISSION = "com.treadmill.bridge.USB_PERMISSION"
        private const val POLL_INTERVAL_MS = 500L
    }

    private lateinit var statusText: TextView
    private var fitPro1: FitPro1? = null
    private val handler = Handler(Looper.getMainLooper())
    private var polling = false
    private var usbInfo = ""
    private var readCount = 0
    private var errorCount = 0

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION) {
                val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted && device != null) {
                    connectToDevice(device)
                } else {
                    status("USB permission denied")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        statusText = TextView(this).apply {
            textSize = 20f
            setPadding(32, 32, 32, 32)
        }
        setContentView(statusText)

        registerReceiver(usbReceiver, IntentFilter(ACTION_USB_PERMISSION))

        status("Looking for motor controller...")
        findAndConnect()
    }

    override fun onDestroy() {
        super.onDestroy()
        polling = false
        unregisterReceiver(usbReceiver)
    }

    private fun findAndConnect() {
        val usbManager = getSystemService(USB_SERVICE) as UsbManager

        // Find FitPro1 motor controller by VID/PID
        val device = usbManager.deviceList.values.firstOrNull { dev ->
            dev.vendorId == 8508 && dev.productId == 2
        } ?: usbManager.deviceList.values.firstOrNull { dev ->
            // Fallback: any non-hub device
            dev.interfaceCount > 0 && dev.getInterface(0).interfaceClass != UsbConstants.USB_CLASS_HUB
        }

        if (device == null) {
            status("No motor controller found (VID=8508 PID=2)\n\n" +
                "Devices: ${usbManager.deviceList.values.joinToString { "${it.vendorId}:${it.productId}" }}")
            return
        }

        status("Found: ${device.deviceName} (${device.vendorId}:${device.productId})")

        if (usbManager.hasPermission(device)) {
            connectToDevice(device)
        } else {
            val pi = PendingIntent.getBroadcast(this, 0,
                Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE)
            usbManager.requestPermission(device, pi)
            status("Requesting USB permission...")
        }
    }

    private fun connectToDevice(device: UsbDevice) {
        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        val connection = usbManager.openDevice(device)
        if (connection == null) {
            status("Failed to open USB device")
            return
        }

        val intf = device.getInterface(0)
        connection.claimInterface(intf, true)

        // Wolf hardcodes: endpoint 0 = read (IN), endpoint 1 = write (OUT)
        if (intf.endpointCount < 2) {
            status("Need 2 endpoints, got ${intf.endpointCount}")
            return
        }
        val readEp = intf.getEndpoint(0)
        val writeEp = intf.getEndpoint(1)

        usbInfo = "USB: ${device.deviceName} vid=${device.vendorId} pid=${device.productId}\n" +
               "Write: ep1 addr=${writeEp.address}  Read: ep0 addr=${readEp.address}"

        status("$usbInfo\nHandshaking...")

        Thread {
            val fp = FitPro1(connection, writeEp, readEp)

            if (!fp.handshake()) {
                handler.post { status("$usbInfo\n\nHandshake FAILED") }
                return@Thread
            }
            handler.post { status("$usbInfo\n\nHandshake OK, initializing...") }

            fp.initialize()
            fitPro1 = fp
            handler.post {
                status("$usbInfo\n\nInitialized, polling...")
                startPolling()
            }
        }.start()
    }

    private fun startPolling() {
        polling = true
        Thread {
            while (polling) {
                try {
                    val fp = fitPro1 ?: break
                    val state = fp.readState()
                    readCount++
                    if (state != null) {
                        fp.handleStartButton(state)
                        handler.post {
                            status("$usbInfo\n\nSpeed: %.1f km/h\nIncline: %.1f%%\nStart: ${state.startRequested}\n\nReads: $readCount  Errors: $errorCount"
                                .format(state.speedKPH, state.inclinePct))
                        }
                    } else {
                        errorCount++
                        handler.post {
                            status("$usbInfo\n\nRead failed\n\nReads: $readCount  Errors: $errorCount")
                        }
                    }
                } catch (e: Exception) {
                    errorCount++
                    handler.post {
                        status("$usbInfo\n\nException: ${e.message}\n\nReads: $readCount  Errors: $errorCount")
                    }
                    Log.e(TAG, "poll error", e)
                }
                Thread.sleep(POLL_INTERVAL_MS)
            }
        }.start()
    }

    private fun status(msg: String) {
        Log.d(TAG, msg)
        statusText.text = msg
    }
}
