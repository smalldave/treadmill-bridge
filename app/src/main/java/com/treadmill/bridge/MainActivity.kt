package com.treadmill.bridge

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.app.Activity
import android.widget.TextView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * UI-only Activity. Binds to BridgeService to display status and metrics.
 * Can be destroyed/recreated without affecting the treadmill connection.
 */
class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var metricsText: TextView
    private lateinit var statsText: TextView
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var service: BridgeService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as BridgeService.LocalBinder).service
            observeStatus()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)
        metricsText = findViewById(R.id.metricsText)
        statsText = findViewById(R.id.statsText)

        val intent = Intent(this, BridgeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(intent)
        else
            startService(intent)
        bound = bindService(intent, connection, BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        if (bound) {
            unbindService(connection)
            bound = false
        }
    }

    private fun observeStatus() {
        scope.launch {
            service?.status?.collectLatest { s ->
                statusText.text = s.statusMsg
                statusText.setTextColor(getColor(s.statusColor))
                if (s.metricsMsg.isNotEmpty()) metricsText.text = s.metricsMsg
                statsText.text = "Reads: ${s.readCount}  Errors: ${s.errorCount}"
            }
        }
    }
}
