package com.treadmill.bridge

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * FitPro1 protocol for NordicTrack/iFit treadmills.
 * Single USB thread — all access serialized through command channel.
 *
 * Protocol: docs/fitpro1-protocol.md
 */
class FitPro1(
    private val transport: UsbTransport
) {
    companion object {
        private const val TAG = "FitPro1"
        private const val HANDSHAKE_SIZE = 64
        private const val MAX_READ_RETRIES = 5
        private const val DEVICE_MAIN: Byte = 2
        private const val CMD_STATUS_DONE = 2
        private const val POLL_INTERVAL_MS = 500L

        private const val CMD_READ_WRITE_DATA: Byte = 2
        private const val CMD_DEVICE_INFO: Byte = 0x81.toByte()
        private const val CMD_SYSTEM_INFO: Byte = 0x82.toByte()
        private const val CMD_VERIFY_SECURITY: Byte = 0x90.toByte()

        const val BF_KPH = 0
        const val BF_GRADE = 1
        const val BF_ACTUAL_KPH = 16
        const val BF_ACTUAL_INCLINE = 17
        const val BF_WORKOUT_MODE = 12
        const val BF_START_REQUESTED = 96
        const val BF_IDLE_MODE_LOCKOUT = 95
        const val BF_REQUIRE_START_REQUESTED = 108

        const val WORKOUT_MODE_IDLE = 1
        const val WORKOUT_MODE_RUNNING = 2
        const val WORKOUT_MODE_RESULTS = 4
    }

    // ========== Command Channel ==========

    data class UsbCommand(
        val request: ByteArray,
        val readDelayMs: Long = 80,
        val label: String = "",
        val callerTrace: Throwable = Throwable("enqueued at"),
        val deferred: CompletableDeferred<ByteArray?> = CompletableDeferred()
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val usbDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val commandChannel = Channel<UsbCommand>(Channel.BUFFERED)
    private var usbScope: CoroutineScope? = null

    /** Enqueue a command for USB execution. Returns deferred with response. */
    fun enqueue(request: ByteArray, readDelayMs: Long = 80, label: String = ""): Deferred<ByteArray?> {
        val cmd = UsbCommand(request, readDelayMs, label)
        if (commandChannel.trySend(cmd).isFailure) {
            cmd.deferred.complete(null)
        }
        return cmd.deferred
    }

    // ========== Shared State ==========

    data class TreadmillState(val speedKPH: Double, val inclinePct: Double, val workoutMode: Int, val startRequested: Boolean)

    fun workoutModeName(mode: Int) = when(mode) {
        0->"Unknown"; 1->"Idle"; 2->"Running"; 3->"Pause"; 4->"Results"
        5->"Debug"; 6->"Log"; 7->"Maintenance"; 8->"SafetyKey"; 9->"Demo"
        10->"WarmUp"; 11->"CoolDown"; 12->"Sleep"; 13->"Resume"; 14->"Locked"
        20->"PauseOverride"; else->"?($mode)"
    }

    private val _snapshotFlow = MutableStateFlow(DirconServer.TreadmillSnapshot())
    val snapshotFlow: StateFlow<DirconServer.TreadmillSnapshot> get() = _snapshotFlow

    /** Workout session state — owned exclusively by the USB thread (handlePollCycle). */
    private class WorkoutSession(
        var running: Boolean = false,
        var distanceM: Double = 0.0,
        var startTimeMs: Long = 0L,
        var lastPollMs: Long = System.currentTimeMillis()
    )
    private var session = WorkoutSession()
    var onStateUpdate: ((TreadmillState) -> Unit)? = null

    /** Completes when the USB link is terminally dead. Owner should teardown and reconnect. */
    val terminalFailure = CompletableDeferred<Exception>()

    // ========== Handshake (runs on caller's thread, before USB loop starts) ==========

    fun handshake(): Boolean {
        val buf = ByteArray(HANDSHAKE_SIZE) { 0xFF.toByte() }
        var consecutive = 0; var attempts = 0
        while (consecutive < 2 && attempts < 10) {
            if (!transport.write(buf)) { attempts++; Thread.sleep(500); continue }
            val reply = ByteArray(HANDSHAKE_SIZE)
            if (transport.read(reply) < 0) { attempts++; Thread.sleep(500); continue }
            val ok = reply.indices.all { i -> i == 3 || reply[i] == 0xFF.toByte() }
            if (ok) { consecutive++; Log.d(TAG, "handshake ok ($consecutive/2)") }
            else { consecutive = 0; Log.d(TAG, "handshake mismatch") }
            attempts++; Thread.sleep(500)
        }
        return consecutive >= 2
    }

    // ========== Init (runs on caller's thread, before USB loop starts) ==========

    fun initialize(): Boolean {
        Log.d(TAG, "=== DeviceInfo ===")
        val diResp = sendCommandDirect(buildCmd(CMD_DEVICE_INFO), "init:DeviceInfo", 300) ?: return false
        if (!isSuccess(diResp)) { Log.e(TAG, "DeviceInfo failed"); return false }
        val di = diResp.asLEBuffer().apply { position(4) }
        val masterLibVer = di.readU8()
        di.readU8() // padding
        val serialNumber = di.readS32()
        Log.d(TAG, "swVer=$masterLibVer serial=$serialNumber")
        Thread.sleep(200)

        Log.d(TAG, "=== SystemInfo ===")
        val siResp = sendCommandDirect(buildCmd(CMD_SYSTEM_INFO, byteArrayOf(0, 0)), "init:SystemInfo", 300) ?: return false
        if (!isSuccess(siResp)) { Log.e(TAG, "SystemInfo failed"); return false }
        val si = siResp.asLEBuffer().apply { position(7) }
        val model = si.readS32(); val partNumber = si.readS32()
        Log.d(TAG, "model=$model partNumber=$partNumber")
        Thread.sleep(200)

        Log.d(TAG, "=== VerifySecurity ===")
        val hash = calcSecurityHash(serialNumber, partNumber, model)
        val secContent = ByteArray(36); hash.copyInto(secContent, 0)
        val secretKey = 8 * masterLibVer
        secContent[32] = (secretKey and 0xFF).toByte()
        secContent[33] = ((secretKey shr 8) and 0xFF).toByte()
        secContent[34] = ((secretKey shr 16) and 0xFF).toByte()
        secContent[35] = ((secretKey shr 24) and 0xFF).toByte()
        val secResp = sendCommandDirect(buildCmd(CMD_VERIFY_SECURITY, secContent), "init:Security", 300)
        if ((secResp?.get(3)?.toInt()?.and(0xFF) ?: -1) != CMD_STATUS_DONE) {
            Log.e(TAG, "Security verification FAILED"); return false
        }
        Log.d(TAG, "Security: UNLOCKED")
        Thread.sleep(200)

        Log.d(TAG, "=== Unlock console ===")
        writeBoolDirect(BF_REQUIRE_START_REQUESTED, true)
        writeBoolDirect(BF_IDLE_MODE_LOCKOUT, false)
        return true
    }

    // ========== USB Loop (single thread — all runtime USB access here) ==========

    fun startUsbLoop() {
        val scope = CoroutineScope(usbDispatcher + SupervisorJob())
        usbScope = scope

        // Command drain — suspends when no commands (no spin-wait)
        scope.launch {
            for (cmd in commandChannel) {
                executeCommand(cmd)
            }
        }

        // Poll cycle — fixed 500ms interval
        scope.launch {
            Log.d(TAG, "USB loop started")
            var consecutiveFailures = 0
            while (isActive) {
                if (doPoll()) {
                    consecutiveFailures = 0
                } else if (++consecutiveFailures >= MAX_READ_RETRIES) {
                    Log.e(TAG, "USB link dead ($consecutiveFailures consecutive poll failures)")
                    terminalFailure.complete(Exception("USB poll failed $consecutiveFailures times"))
                    cancel()
                    return@launch
                }
                delay(POLL_INTERVAL_MS)
            }
            Log.d(TAG, "USB loop stopped")
        }
    }

    fun stopUsbLoop() {
        usbScope?.cancel()
        usbScope = null
    }

    private fun doPoll(): Boolean {
        val readReq = buildReadRequest(listOf(BF_KPH, BF_WORKOUT_MODE, BF_ACTUAL_INCLINE, BF_START_REQUESTED))
        val resp = sendAndRead(readReq, "poll", 80) ?: return false
        if (!isSuccess(resp)) return false

        // Poll response: header(4) + speed(2) + mode(1) + incline(2) + startReq(1) = 10 bytes min
        if (resp.size < 10) return false
        val buf = resp.asLEBuffer().apply { position(4) }
        val speedKPH = buf.readU16() / 100.0
        val mode = buf.readU8()
        val inclinePct = buf.readS16() / 100.0
        val startReq = buf.readU8() != 0
        val state = TreadmillState(speedKPH, inclinePct, mode, startReq)
        handlePollCycle(state)

        val now = System.currentTimeMillis()
        _snapshotFlow.value = DirconServer.TreadmillSnapshot(
            state.speedKPH, state.inclinePct, 0, session.distanceM.toInt(),
            if (session.startTimeMs > 0) ((now - session.startTimeMs) / 1000).toInt() else 0
        )
        onStateUpdate?.invoke(state)
        return true
    }

    private fun executeCommand(cmd: UsbCommand) {
        try {
            val response = sendAndRead(cmd.request, cmd.label, cmd.readDelayMs)
            cmd.deferred.complete(response)
            if (response == null) {
                Log.w(TAG, "${cmd.label}: no response", cmd.callerTrace)
            }
        } catch (e: Exception) {
            Log.e(TAG, "${cmd.label}: USB error", e)
            Log.e(TAG, "${cmd.label}: enqueued from:", cmd.callerTrace)
            cmd.deferred.complete(null)
        }
    }

    // ========== Public API (non-blocking, enqueue + return deferred) ==========

    fun setSpeed(kph: Double): Deferred<Boolean> {
        val req = buildWriteU16Cmd(BF_KPH, (kph * 100).toInt())
        return enqueueAndMap(req, "setSpeed($kph)")
    }

    fun setIncline(pct: Double): Deferred<Boolean> {
        val req = buildWriteU16Cmd(BF_GRADE, (pct * 100).toInt())
        return enqueueAndMap(req, "setIncline($pct)")
    }

    fun startWorkout(speedKPH: Double, inclinePct: Double): Deferred<Boolean> {
        val speedRaw = (speedKPH * 100).toInt()
        val inclineRaw = (inclinePct * 100).toInt()
        val content = byteArrayOf(
            2, 0x03, 0x10,
            (speedRaw and 0xFF).toByte(), ((speedRaw shr 8) and 0xFF).toByte(),
            (inclineRaw and 0xFF).toByte(), ((inclineRaw shr 8) and 0xFF).toByte(),
            WORKOUT_MODE_RUNNING.toByte(), 0
        )
        return enqueueAndMap(buildCmd(CMD_READ_WRITE_DATA, content), "startWorkout")
    }

    fun stopWorkout(): Deferred<Boolean> {
        val content = byteArrayOf(2, 0x00, 0x10, WORKOUT_MODE_IDLE.toByte(), 0)
        return enqueueAndMap(buildCmd(CMD_READ_WRITE_DATA, content), "stopWorkout")
    }

    private fun enqueueAndMap(request: ByteArray, label: String): Deferred<Boolean> {
        val cmd = UsbCommand(request, label = label)
        if (commandChannel.trySend(cmd).isFailure) {
            cmd.deferred.complete(null)
        }
        val result = CompletableDeferred<Boolean>()
        cmd.deferred.invokeOnCompletion {
            result.complete(isSuccess(cmd.deferred.getCompleted()))
        }
        return result
    }

    private fun isSuccess(resp: ByteArray?): Boolean =
        resp != null && resp.size > 3 && (resp[3].toInt() and 0xFF) == CMD_STATUS_DONE

    // ========== Internal (called only on USB thread or during init) ==========

    private fun handlePollCycle(state: TreadmillState) {
        val now = System.currentTimeMillis()
        val dtSec = (now - session.lastPollMs) / 1000.0
        session.lastPollMs = now
        if (state.speedKPH > 0) session.distanceM += (state.speedKPH / 3.6) * dtSec

        // State transitions — session state is owned exclusively here on the USB thread.
        // stopWorkout()/startWorkout() only enqueue USB commands via commandChannel.trySend().
        // The command drain coroutine shares this single-threaded dispatcher, so
        // enqueued commands are processed on a subsequent poll cycle, not inline.

        if (state.workoutMode == WORKOUT_MODE_RESULTS) {
            Log.d(TAG, "Results mode — dismissing to Idle")
            stopWorkout()
        }

        if (state.startRequested && !session.running) {
            Log.d(TAG, "START pressed")
            startWorkout(TreadmillProfile.MIN_SPEED_KPH, state.inclinePct)
        }

        // Transition running state based on observed device mode
        if (state.workoutMode == WORKOUT_MODE_RUNNING && !session.running) {
            session = WorkoutSession(running = true, startTimeMs = now)
        } else if (state.workoutMode != WORKOUT_MODE_RUNNING && session.running) {
            session.running = false
        }
    }

    /** Direct USB send — used only during init (before USB loop starts). */
    private fun sendCommandDirect(request: ByteArray, label: String = "", readDelayMs: Long = 80): ByteArray? {
        return sendAndRead(request, label, readDelayMs)
    }

    private fun writeBoolDirect(bf: Int, value: Boolean): Boolean {
        val req = buildWriteFieldCmd(bf, byteArrayOf(if (value) 1 else 0))
        val resp = sendCommandDirect(req, "writeBool($bf=$value)")
        return isSuccess(resp)
    }

    /**
     * Low-level USB write+read. Called ONLY from USB thread or init.
     * Uses Thread.sleep rather than coroutine delay because:
     * - bulkTransfer is already a blocking JNI call
     * - the delay is a hardware timing gap, not a cancellation point
     * - this runs on a dedicated single-thread dispatcher, so blocking is safe
     */
    private fun sendAndRead(request: ByteArray, label: String, readDelayMs: Long): ByteArray? {
        Log.d(TAG, "$label TX: ${request.joinToString(" ") { "%02X".format(it) }}")
        if (!transport.write(request)) {
            Log.e(TAG, "$label write failed"); return null
        }
        Thread.sleep(readDelayMs)

        val buf = ByteArray(64); var retries = 0
        while (retries < MAX_READ_RETRIES) {
            if (transport.read(buf) < 0) {
                Log.e(TAG, "$label read failed"); return null
            }
            if (buf[0] != 0xFF.toByte()) break
            retries++
        }
        if (buf[0] == 0xFF.toByte()) { Log.e(TAG, "$label read: all 0xFF"); return null }

        val len = buf[1].toInt() and 0xFF
        val resp = buf.copyOf(maxOf(len, 5))
        Log.d(TAG, "$label RX: ${resp.joinToString(" ") { "%02X".format(it) }}")
        return resp
    }

    // ========== Command Builders ==========

    private fun buildCmd(cmdId: Byte, content: ByteArray = ByteArray(0)): ByteArray {
        val length = 4 + content.size; val msg = ByteArray(length)
        msg[0] = DEVICE_MAIN; msg[1] = length.toByte(); msg[2] = cmdId
        content.copyInto(msg, 3); msg[length - 1] = checksum(msg); return msg
    }

    private fun buildReadRequest(fields: List<Int>): ByteArray {
        val maxField = fields.max(); val numSections = maxField / 8 + 1
        val bitmask = ByteArray(numSections)
        for (bf in fields) bitmask[bf / 8] = (bitmask[bf / 8].toInt() or (1 shl (bf % 8))).toByte()
        val content = ByteArray(1 + 1 + numSections)
        content[0] = 0; content[1] = numSections.toByte(); bitmask.copyInto(content, 2)
        return buildCmd(CMD_READ_WRITE_DATA, content)
    }

    private fun buildWriteU16Cmd(bf: Int, value: Int): ByteArray {
        return buildWriteFieldCmd(bf, leU16Bytes(value))
    }

    private fun buildWriteFieldCmd(bf: Int, data: ByteArray): ByteArray {
        val section = bf / 8; val numSections = section + 1
        val bitmask = ByteArray(numSections); bitmask[section] = (1 shl (bf % 8)).toByte()
        val content = ByteArray(1 + numSections + data.size + 1)
        content[0] = numSections.toByte()
        bitmask.copyInto(content, 1); data.copyInto(content, 1 + numSections)
        content[content.size - 1] = 0
        return buildCmd(CMD_READ_WRITE_DATA, content)
    }

    // ========== Helpers ==========

    private fun checksum(bytes: ByteArray): Byte {
        val len = bytes[1].toInt() and 0xFF; var sum = 0
        for (i in 0 until len - 1) sum += bytes[i].toInt() and 0xFF; return sum.toByte()
    }

    private fun calcSecurityHash(serial: Int, part: Int, model: Int): ByteArray {
        val h = ByteArray(32)
        for (b in 0 until 32) {
            h[b] = (b + 1).toByte()
            if (((serial shr b) and 1) == 1) {
                val rotated = (part shl 16) or (part ushr 16)
                h[b] = (h[b].toInt() xor ((if (b < 16) rotated else part) ushr b).toByte().toInt()).toByte()
            } else {
                h[b] = (h[b].toInt() xor (((h[b].toInt() and 0xFF) * (b + model)) and 0xFF)).toByte()
            }
        }
        return h
    }
}
