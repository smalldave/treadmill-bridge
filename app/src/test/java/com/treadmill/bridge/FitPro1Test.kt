package com.treadmill.bridge

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fake USB transport that records writes and replays scripted responses.
 */
class FakeUsbTransport : UsbTransport {
    val written = ConcurrentLinkedQueue<ByteArray>()
    private val responses = ConcurrentLinkedQueue<ByteArray>()

    fun scriptResponse(vararg bytes: Int) {
        responses.add(ByteArray(bytes.size) { bytes[it].toByte() })
    }

    fun scriptResponse(bytes: ByteArray) {
        responses.add(bytes)
    }

    override fun write(data: ByteArray): Boolean {
        written.add(data.copyOf())
        return true
    }

    override fun read(buf: ByteArray): Int {
        val resp = responses.poll() ?: return -1
        resp.copyInto(buf, 0, 0, minOf(resp.size, buf.size))
        return resp.size
    }
}

class FitPro1Test {

    // --- Helpers ---

    /** Build a valid handshake reply: all 0xFF except byte[3] which can be anything. */
    private fun handshakeReply(): ByteArray {
        val buf = ByteArray(64) { 0xFF.toByte() }
        buf[3] = 0x00 // byte 3 is excluded from check
        return buf
    }

    /** Build a minimal "Done" response for a command. */
    private fun doneResponse(cmdId: Int, payloadSize: Int = 0): ByteArray {
        val len = 5 + payloadSize
        val resp = ByteArray(len)
        resp[0] = 2 // DEVICE_MAIN
        resp[1] = len.toByte()
        resp[2] = cmdId.toByte()
        resp[3] = 2 // CMD_STATUS_DONE
        return resp
    }

    /** Build a DeviceInfo Done response with swVer and serial. */
    private fun deviceInfoResponse(swVer: Int = 10, serial: Int = 12345): ByteArray {
        val resp = ByteArray(12)
        resp[0] = 2; resp[1] = 12; resp[2] = 0x81.toByte(); resp[3] = 2 // Done
        resp[4] = swVer.toByte() // masterLibVer
        resp[5] = 0
        // serial at offset 6 (u32 LE)
        resp[6] = (serial and 0xFF).toByte()
        resp[7] = ((serial shr 8) and 0xFF).toByte()
        resp[8] = ((serial shr 16) and 0xFF).toByte()
        resp[9] = ((serial shr 24) and 0xFF).toByte()
        return resp
    }

    /** Build a SystemInfo Done response with model and partNumber. */
    private fun systemInfoResponse(model: Int = 100, partNumber: Int = 200): ByteArray {
        val resp = ByteArray(16)
        resp[0] = 2; resp[1] = 16; resp[2] = 0x82.toByte(); resp[3] = 2 // Done
        // model at offset 7 (u32 LE)
        resp[7] = (model and 0xFF).toByte()
        resp[8] = ((model shr 8) and 0xFF).toByte()
        resp[9] = ((model shr 16) and 0xFF).toByte()
        resp[10] = ((model shr 24) and 0xFF).toByte()
        // partNumber at offset 11 (u32 LE)
        resp[11] = (partNumber and 0xFF).toByte()
        resp[12] = ((partNumber shr 8) and 0xFF).toByte()
        resp[13] = ((partNumber shr 16) and 0xFF).toByte()
        resp[14] = ((partNumber shr 24) and 0xFF).toByte()
        return resp
    }

    /** Build a poll response: speed(u16) + mode(u8) + incline(s16) + startReq(u8). */
    private fun pollResponse(speedKPH: Double, mode: Int, inclinePct: Double, startRequested: Boolean): ByteArray {
        val len = 11 // header(4) + speed(2) + mode(1) + incline(2) + startReq(1) + padding
        val resp = ByteArray(len)
        resp[0] = 2; resp[1] = len.toByte(); resp[2] = 2; resp[3] = 2 // CMD_READ_WRITE_DATA, Done
        val speed100 = (speedKPH * 100).toInt()
        resp[4] = (speed100 and 0xFF).toByte()
        resp[5] = ((speed100 shr 8) and 0xFF).toByte()
        resp[6] = mode.toByte()
        val incline100 = (inclinePct * 100).toInt()
        resp[7] = (incline100 and 0xFF).toByte()
        resp[8] = ((incline100 shr 8) and 0xFF).toByte()
        resp[9] = if (startRequested) 1 else 0
        return resp
    }

    // ========== Handshake Tests ==========

    @Test fun `handshake succeeds with two valid replies`() {
        val fake = FakeUsbTransport()
        fake.scriptResponse(handshakeReply())
        fake.scriptResponse(handshakeReply())
        val fp = FitPro1(fake)
        assertTrue(fp.handshake())
        assertEquals(2, fake.written.size) // wrote handshake bytes twice
    }

    @Test fun `handshake fails when transport read fails`() {
        val fake = object : UsbTransport {
            var writeCount = 0
            override fun write(data: ByteArray): Boolean { writeCount++; return true }
            override fun read(buf: ByteArray) = -1 // always fail
        }
        val fp = FitPro1(fake)
        assertFalse(fp.handshake())
    }

    // ========== Initialize Tests ==========

    @Test fun `initialize succeeds with valid device responses`() {
        val fake = FakeUsbTransport()
        // DeviceInfo response
        fake.scriptResponse(deviceInfoResponse())
        // SystemInfo response
        fake.scriptResponse(systemInfoResponse())
        // VerifySecurity response
        fake.scriptResponse(doneResponse(0x90))
        // writeBool(BF_REQUIRE_START_REQUESTED, true)
        fake.scriptResponse(doneResponse(2))
        // writeBool(BF_IDLE_MODE_LOCKOUT, false)
        fake.scriptResponse(doneResponse(2))

        val fp = FitPro1(fake)
        assertTrue(fp.initialize())
        assertEquals(5, fake.written.size) // 5 commands sent
    }

    @Test fun `initialize fails when DeviceInfo returns non-Done status`() {
        val fake = FakeUsbTransport()
        val badResp = deviceInfoResponse()
        badResp[3] = 4 // Failed status
        fake.scriptResponse(badResp)

        val fp = FitPro1(fake)
        assertFalse(fp.initialize())
        assertEquals(1, fake.written.size) // only DeviceInfo sent
    }

    @Test fun `initialize fails when DeviceInfo gets no response`() {
        val fake = FakeUsbTransport()
        // No responses scripted → read returns -1

        val fp = FitPro1(fake)
        assertFalse(fp.initialize())
    }

    // ========== setSpeed / setIncline via USB Loop ==========

    /**
     * Find a specific command in the written queue by matching byte length.
     * This handles the race where poll commands may interleave with test commands.
     */
    private fun findWritten(fake: FakeUsbTransport, expectedLength: Int): ByteArray {
        // Drain the queue and find the command with matching length
        val all = mutableListOf<ByteArray>()
        while (true) {
            val msg = fake.written.poll() ?: break
            all.add(msg)
        }
        return all.first { it[1].toInt() == expectedLength }
    }

    @Test fun `setSpeed sends correct protocol bytes`() {
        val fake = FakeUsbTransport()
        val fp = FitPro1(fake)

        // Enqueue command BEFORE starting loop so it's processed first
        val future = fp.setSpeed(8.5)
        // Script enough responses: one for the command, extras for polls
        repeat(5) { fake.scriptResponse(doneResponse(2)) }

        fp.startUsbLoop()
        try {
            val result = future.get(3, TimeUnit.SECONDS)
            assertTrue(result)
            Thread.sleep(100) // let writes settle

            // buildWriteFieldCmd(BF_KPH=0, [0x52, 0x03]):
            //   content = [1, 0x01, 0x52, 0x03, 0x00] → buildCmd → length = 9
            val sent = findWritten(fake, 9)
            assertEquals(2, sent[0].toInt()) // DEVICE_MAIN
            assertEquals(2, sent[2].toInt()) // CMD_READ_WRITE_DATA
            assertEquals(1, sent[3].toInt()) // numSections
            assertEquals(0x01, sent[4].toInt() and 0xFF) // bitmask: bit 0 = BF_KPH
            assertEquals(0x52, sent[5].toInt() and 0xFF) // 850 & 0xFF
            assertEquals(0x03, sent[6].toInt() and 0xFF) // 850 >> 8
        } finally {
            fp.stopUsbLoop()
        }
    }

    @Test fun `setIncline with negative value sends signed encoding`() {
        val fake = FakeUsbTransport()
        val fp = FitPro1(fake)

        val future = fp.setIncline(-2.0)
        repeat(5) { fake.scriptResponse(doneResponse(2)) }

        fp.startUsbLoop()
        try {
            val result = future.get(3, TimeUnit.SECONDS)
            assertTrue(result)
            Thread.sleep(100)

            // buildWriteFieldCmd(BF_GRADE=1, [-200 as u16]):
            //   content = [1, 0x02, 0x38, 0xFF, 0x00] → buildCmd → length = 9
            val sent = findWritten(fake, 9)
            assertEquals(0x02, sent[4].toInt() and 0xFF) // bitmask: bit 1 = BF_GRADE
            assertEquals(0x38, sent[5].toInt() and 0xFF) // -200 & 0xFF
            assertEquals(0xFF, sent[6].toInt() and 0xFF) // -200 >> 8 & 0xFF
        } finally {
            fp.stopUsbLoop()
        }
    }

    @Test fun `setSpeed returns false on failed response`() {
        val fake = FakeUsbTransport()
        val fp = FitPro1(fake)

        val failResp = doneResponse(2)
        failResp[3] = 4 // Failed status
        val future = fp.setSpeed(5.0)
        fake.scriptResponse(failResp)
        repeat(4) { fake.scriptResponse(doneResponse(2)) }

        fp.startUsbLoop()
        try {
            val result = future.get(3, TimeUnit.SECONDS)
            assertFalse(result)
        } finally {
            fp.stopUsbLoop()
        }
    }

    // ========== Poll Cycle & State Machine ==========

    @Test fun `poll cycle parses speed and incline into snapshot`() {
        val fake = FakeUsbTransport()
        val fp = FitPro1(fake)

        // Script multiple poll responses so the loop has data
        repeat(5) { fake.scriptResponse(pollResponse(8.5, 2, -1.5, false)) }

        var receivedState: FitPro1.TreadmillState? = null
        fp.onStateUpdate = { receivedState = it }

        fp.startUsbLoop()
        try {
            // Wait for at least one poll cycle
            Thread.sleep(1500)

            val state = receivedState
            assertTrue(state != null, "Should have received a state update")
            assertEquals(8.5, state!!.speedKPH, 0.01)
            assertEquals(-1.5, state.inclinePct, 0.01)
            assertEquals(2, state.workoutMode) // Running
            assertFalse(state.startRequested)

            // Verify snapshot
            assertEquals(8.5, fp.lastSnapshot.speedKPH, 0.01)
            assertEquals(-1.5, fp.lastSnapshot.inclinePct, 0.01)
        } finally {
            fp.stopUsbLoop()
        }
    }

    @Test fun `startWorkout sends correct command bytes`() {
        val fake = FakeUsbTransport()
        val fp = FitPro1(fake)

        val future = fp.startWorkout(1.6, 0.0)
        repeat(5) { fake.scriptResponse(doneResponse(2)) }

        fp.startUsbLoop()
        try {
            val result = future.get(3, TimeUnit.SECONDS)
            assertTrue(result)
            Thread.sleep(100)

            // startWorkout content = [2, 0x03, 0x10, speedLo, speedHi, incLo, incHi, mode, 0]
            // buildCmd → length = 4 + 9 = 13
            val sent = findWritten(fake, 13)
            assertEquals(2, sent[0].toInt()) // DEVICE_MAIN
            assertEquals(2, sent[2].toInt()) // CMD_READ_WRITE_DATA
            assertEquals(2, sent[3].toInt())    // content[0]
            assertEquals(0x03, sent[4].toInt()) // content[1]
            assertEquals(0x10, sent[5].toInt()) // content[2]
            // speed = 1.6 * 100 = 160 = 0x00A0
            assertEquals(0xA0.toByte(), sent[6]) // speedLo
            assertEquals(0x00.toByte(), sent[7]) // speedHi
            // incline = 0
            assertEquals(0x00.toByte(), sent[8])
            assertEquals(0x00.toByte(), sent[9])
            // mode = WORKOUT_MODE_RUNNING = 2
            assertEquals(2, sent[10].toInt())
        } finally {
            fp.stopUsbLoop()
        }
    }

    @Test fun `stopWorkout sends idle mode command`() {
        val fake = FakeUsbTransport()
        val fp = FitPro1(fake)

        val future = fp.stopWorkout()
        repeat(5) { fake.scriptResponse(doneResponse(2)) }

        fp.startUsbLoop()
        try {
            val result = future.get(3, TimeUnit.SECONDS)
            assertTrue(result)
            Thread.sleep(100)

            // stopWorkout content = [2, 0x00, 0x10, 1, 0] → buildCmd → length = 4 + 5 = 9
            val sent = findWritten(fake, 9)
            assertEquals(2, sent[2].toInt()) // CMD_READ_WRITE_DATA
            assertEquals(2, sent[3].toInt())
            assertEquals(0x00, sent[4].toInt())
            assertEquals(0x10, sent[5].toInt())
            assertEquals(1, sent[6].toInt()) // WORKOUT_MODE_IDLE
        } finally {
            fp.stopUsbLoop()
        }
    }

    // ========== Bug: initialize ignores VerifySecurity failure ==========

    @Test fun `initialize should fail when VerifySecurity is rejected`() {
        val fake = FakeUsbTransport()
        // DeviceInfo — success
        fake.scriptResponse(deviceInfoResponse())
        // SystemInfo — success
        fake.scriptResponse(systemInfoResponse())
        // VerifySecurity — SecurityBlock (status 8)
        val secFail = doneResponse(0x90)
        secFail[3] = 8 // SecurityBlock
        fake.scriptResponse(secFail)

        val fp = FitPro1(fake)
        // BUG: initialize() currently returns true here because it never checks
        // the VerifySecurity response status. It should return false.
        assertFalse(fp.initialize(), "initialize() should fail when security verification is rejected")
    }

}
