package com.treadmill.bridge

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbRequest
import android.util.Log
import java.nio.ByteBuffer

/**
 * FitPro1 protocol for NordicTrack/iFit treadmills.
 * Talks directly to motor controller over USB (raw commands, no BLE wrapper).
 *
 * Protocol: docs/projects/fitpro1-protocol.md
 */
class FitPro1(
    private val connection: UsbDeviceConnection,
    private val writeEndpoint: UsbEndpoint,
    private val readEndpoint: UsbEndpoint
) {
    companion object {
        private const val TAG = "FitPro1"
        private const val HANDSHAKE_SIZE = 64
        private const val USB_TIMEOUT_MS = 50
        private const val MAX_READ_RETRIES = 5
        private const val DEVICE_MAIN: Byte = 2
        private const val CMD_STATUS_DONE = 2

        // Commands
        private const val CMD_READ_WRITE_DATA: Byte = 2
        private const val CMD_DEVICE_INFO: Byte = 0x81.toByte()
        private const val CMD_SYSTEM_INFO: Byte = 0x82.toByte()
        private const val CMD_VERIFY_SECURITY: Byte = 0x90.toByte()

        // BitFields
        const val BF_KPH = 0
        const val BF_GRADE = 1
        const val BF_ACTUAL_KPH = 16
        const val BF_ACTUAL_INCLINE = 17
        const val BF_WORKOUT_MODE = 12
        const val BF_KEY_OBJECT = 7
        const val BF_START_REQUESTED = 96
        const val BF_IDLE_MODE_LOCKOUT = 95
        const val BF_REQUIRE_START_REQUESTED = 108

        const val WORKOUT_MODE_IDLE = 1
        const val WORKOUT_MODE_RUNNING = 2

        const val MIN_SPEED_KPH = 1.6
    }

    // ========== Handshake (UsbRequest.Queue, 64 bytes) ==========

    fun handshake(): Boolean {
        val buf = ByteArray(HANDSHAKE_SIZE) { 0xFF.toByte() }
        var consecutive = 0
        var attempts = 0
        while (consecutive < 2 && attempts < 10) {
            val wr = UsbRequest(); wr.initialize(connection, writeEndpoint)
            if (!wr.queue(ByteBuffer.wrap(buf), HANDSHAKE_SIZE)) { wr.close(); attempts++; Thread.sleep(500); continue }
            connection.requestWait(); wr.close()

            val rr = UsbRequest(); rr.initialize(connection, readEndpoint)
            val rb = ByteBuffer.allocate(HANDSHAKE_SIZE)
            if (!rr.queue(rb, HANDSHAKE_SIZE)) { rr.close(); attempts++; Thread.sleep(500); continue }
            connection.requestWait(); rr.close()

            val reply = ByteArray(HANDSHAKE_SIZE); rb.position(0); rb.get(reply)
            val ok = reply.indices.all { i -> i == 3 || reply[i] == 0xFF.toByte() }
            if (ok) { consecutive++; Log.d(TAG, "handshake ok ($consecutive/2)") }
            else { consecutive = 0; Log.d(TAG, "handshake mismatch") }
            attempts++; Thread.sleep(500)
        }
        return consecutive >= 2
    }

    // ========== Commands (bulkTransfer, raw — no wrapper) ==========

    private fun sendCommand(request: ByteArray, readDelayMs: Long = 80): ByteArray? {
        Log.d(TAG, "TX: ${request.joinToString(" ") { "%02X".format(it) }}")
        if (connection.bulkTransfer(writeEndpoint, request, request.size, USB_TIMEOUT_MS) < 0) {
            Log.e(TAG, "write failed"); return null
        }
        Thread.sleep(readDelayMs)

        val buf = ByteArray(64)
        var retries = 0
        while (retries < MAX_READ_RETRIES) {
            if (connection.bulkTransfer(readEndpoint, buf, 64, USB_TIMEOUT_MS) < 0) {
                Log.e(TAG, "read failed"); return null
            }
            if (buf[0] != 0xFF.toByte()) break
            retries++
        }
        if (buf[0] == 0xFF.toByte()) { Log.e(TAG, "read: all 0xFF"); return null }

        val len = buf[1].toInt() and 0xFF
        val resp = buf.copyOf(maxOf(len, 5))
        Log.d(TAG, "RX: ${resp.joinToString(" ") { "%02X".format(it) }}")
        Log.d(TAG, "  Device=${resp[0].toInt() and 0xFF} Len=$len Cmd=${resp[2].toInt() and 0xFF} Status=${statusName(resp[3].toInt() and 0xFF)}")
        return resp
    }

    private fun buildCmd(cmdId: Byte, content: ByteArray = ByteArray(0)): ByteArray {
        val length = 4 + content.size
        val msg = ByteArray(length)
        msg[0] = DEVICE_MAIN; msg[1] = length.toByte(); msg[2] = cmdId
        content.copyInto(msg, 3)
        msg[length - 1] = checksum(msg)
        return msg
    }

    // ========== Initialize ==========

    fun initialize(): Boolean {
        // 1. DeviceInfo → serial number, master library version
        Log.d(TAG, "=== DeviceInfo ===")
        val diResp = sendCommand(buildCmd(CMD_DEVICE_INFO), 300) ?: return false
        if ((diResp[3].toInt() and 0xFF) != CMD_STATUS_DONE) { Log.e(TAG, "DeviceInfo failed"); return false }
        val masterLibVer = diResp[4].toInt() and 0xFF
        val serialNumber = u32(diResp, 6)
        Log.d(TAG, "swVer=$masterLibVer serial=$serialNumber")
        Thread.sleep(200)

        // 2. SystemInfo → model, part number
        Log.d(TAG, "=== SystemInfo ===")
        val siResp = sendCommand(buildCmd(CMD_SYSTEM_INFO, byteArrayOf(0, 0)), 300) ?: return false
        if ((siResp[3].toInt() and 0xFF) != CMD_STATUS_DONE) { Log.e(TAG, "SystemInfo failed"); return false }
        // skip header(4) + configSize(2) + configuration(1) = offset 7
        val model = u32(siResp, 7)
        val partNumber = u32(siResp, 11)
        Log.d(TAG, "model=$model partNumber=$partNumber")
        Thread.sleep(200)

        // 3. VerifySecurity → unlock writes
        Log.d(TAG, "=== VerifySecurity ===")
        val hash = calcSecurityHash(serialNumber, partNumber, model)
        val secretKey = 8 * masterLibVer
        val secContent = ByteArray(36)
        hash.copyInto(secContent, 0)
        secContent[32] = (secretKey and 0xFF).toByte()
        secContent[33] = ((secretKey shr 8) and 0xFF).toByte()
        secContent[34] = ((secretKey shr 16) and 0xFF).toByte()
        secContent[35] = ((secretKey shr 24) and 0xFF).toByte()
        val secResp = sendCommand(buildCmd(CMD_VERIFY_SECURITY, secContent), 300)
        val secOk = (secResp?.get(3)?.toInt()?.and(0xFF) ?: -1) == CMD_STATUS_DONE
        Log.d(TAG, "Security: ${if (secOk) "UNLOCKED" else "FAILED"}")
        Thread.sleep(200)

        // 4. Unlock console
        Log.d(TAG, "=== Unlock console ===")
        val rsr = writeBool(BF_REQUIRE_START_REQUESTED, true)
        val iml = writeBool(BF_IDLE_MODE_LOCKOUT, false)
        Log.d(TAG, "RequireStartRequested=$rsr IdleModeLockout=$iml")
        return true
    }

    // ========== Read/Write ==========

    data class TreadmillState(val speedKPH: Double, val inclinePct: Double, val startRequested: Boolean)

    private var running = false

    fun readState(): TreadmillState? {
        val request = buildReadRequest(listOf(BF_ACTUAL_KPH, BF_ACTUAL_INCLINE, BF_START_REQUESTED))
        val resp = sendCommand(request) ?: return null
        if ((resp[3].toInt() and 0xFF) != CMD_STATUS_DONE) return null

        // Data at offset 4: speed(2) + incline(2) + startRequested(1)
        val speedRaw = u16(resp, 4)
        val inclineRaw = u16s(resp, 6)
        val startReq = resp[8].toInt() != 0
        return TreadmillState(speedRaw / 100.0, inclineRaw / 100.0, startReq)
    }

    /** Call this each poll cycle to handle start button. */
    fun handleStartButton(state: TreadmillState) {
        if (state.startRequested && !running) {
            Log.d(TAG, "START pressed — starting workout at $MIN_SPEED_KPH km/h")
            val ok = startWorkout(MIN_SPEED_KPH, state.inclinePct)
            Log.d(TAG, "startWorkout: ${if (ok) "OK" else "FAILED"}")
            running = true
        } else if (!state.startRequested && running && state.speedKPH == 0.0) {
            Log.d(TAG, "Stopped")
            running = false
        }
    }

    /** Start workout: writes WorkoutMode=Running + Kph + Grade in a single command. */
    fun startWorkout(speedKPH: Double, inclinePct: Double): Boolean {
        // BitFields: Kph=0 (2 bytes), Grade=1 (2 bytes), WorkoutMode=12 (1 byte)
        // Section 0: bits 0+1 = 0x03 (Kph + Grade)
        // Section 1: bit 4 (12%8) = 0x10 (WorkoutMode)
        val speedRaw = (speedKPH * 100).toInt()
        val inclineRaw = (inclinePct * 100).toInt()

        val content = byteArrayOf(
            2,            // numWriteSections = 2
            0x03,         // section 0 bitmask: Kph(bit0) + Grade(bit1)
            0x10,         // section 1 bitmask: WorkoutMode(bit4, 12%8=4)
            (speedRaw and 0xFF).toByte(), ((speedRaw shr 8) and 0xFF).toByte(),     // Kph data (2 bytes LE)
            (inclineRaw and 0xFF).toByte(), ((inclineRaw shr 8) and 0xFF).toByte(), // Grade data (2 bytes LE)
            WORKOUT_MODE_RUNNING.toByte(),  // WorkoutMode = Running (2)
            0             // numReadSections = 0
        )

        val resp = sendCommand(buildCmd(CMD_READ_WRITE_DATA, content)) ?: return false
        return (resp[3].toInt() and 0xFF) == CMD_STATUS_DONE
    }

    // Keep old method for compatibility
    fun readSpeedAndIncline(): Pair<Double, Double>? {
        val state = readState() ?: return null
        return Pair(state.speedKPH, state.inclinePct)
    }

    fun setSpeed(kph: Double) = writeU16(BF_KPH, (kph * 100).toInt())
    fun setIncline(pct: Double) = writeU16(BF_GRADE, (pct * 100).toInt())

    private fun buildReadRequest(fields: List<Int>): ByteArray {
        val maxField = fields.max()
        val numSections = maxField / 8 + 1
        val bitmask = ByteArray(numSections)
        for (bf in fields) bitmask[bf / 8] = (bitmask[bf / 8].toInt() or (1 shl (bf % 8))).toByte()
        val content = ByteArray(1 + 1 + numSections)
        content[0] = 0; content[1] = numSections.toByte()
        bitmask.copyInto(content, 2)
        return buildCmd(CMD_READ_WRITE_DATA, content)
    }

    private fun writeU16(bf: Int, value: Int): Boolean {
        return writeField(bf, byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte()))
    }

    private fun writeBool(bf: Int, value: Boolean) = writeField(bf, byteArrayOf(if (value) 1 else 0))

    private fun writeField(bf: Int, data: ByteArray): Boolean {
        val section = bf / 8; val numSections = section + 1
        val bitmask = ByteArray(numSections); bitmask[section] = (1 shl (bf % 8)).toByte()
        val content = ByteArray(1 + numSections + data.size + 1)
        content[0] = numSections.toByte()
        bitmask.copyInto(content, 1); data.copyInto(content, 1 + numSections)
        content[content.size - 1] = 0
        val resp = sendCommand(buildCmd(CMD_READ_WRITE_DATA, content)) ?: return false
        return (resp[3].toInt() and 0xFF) == CMD_STATUS_DONE
    }

    // ========== Helpers ==========

    private fun checksum(bytes: ByteArray): Byte {
        val len = bytes[1].toInt() and 0xFF
        var sum = 0; for (i in 0 until len - 1) sum += bytes[i].toInt() and 0xFF
        return sum.toByte()
    }

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o+1].toInt() and 0xFF) shl 8)
    private fun u16s(b: ByteArray, o: Int) = u16(b, o).toShort().toDouble()
    private fun u32(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o+1].toInt() and 0xFF) shl 8) or
            ((b[o+2].toInt() and 0xFF) shl 16) or ((b[o+3].toInt() and 0xFF) shl 24)

    private fun statusName(s: Int) = when(s) {
        0->"DevNotSupported"; 1->"CmdNotSupported"; 2->"Done"; 3->"InProgress"
        4->"Failed"; 8->"SecurityBlock"; 9->"CommFailed"; else->"Unknown($s)"
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
