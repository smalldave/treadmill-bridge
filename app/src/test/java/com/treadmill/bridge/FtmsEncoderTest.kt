package com.treadmill.bridge

import kotlin.math.atan
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals

class FtmsEncoderTest {

    // --- Helper to read little-endian values from encoded bytes ---

    private fun u16(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 0xFF) or ((buf[off + 1].toInt() and 0xFF) shl 8)

    private fun s16(buf: ByteArray, off: Int): Int =
        u16(buf, off).toShort().toInt()

    private fun u24(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 0xFF) or
        ((buf[off + 1].toInt() and 0xFF) shl 8) or
        ((buf[off + 2].toInt() and 0xFF) shl 16)

    // ========== encodeTreadmillData ==========

    @Test fun `normal values encode correctly`() {
        val buf = FtmsEncoder.encodeTreadmillData(
            speedKPH = 8.5, inclinePct = 3.0, heartRate = 142,
            distanceM = 1500, elapsedSec = 600
        )
        assertEquals(16, buf.size)

        // Flags
        assertEquals(0x0E, buf[0].toInt() and 0xFF)
        assertEquals(0x05, buf[1].toInt() and 0xFF)

        // Speed: 8.5 * 100 = 850
        assertEquals(850, u16(buf, 2))
        // Average speed == instantaneous
        assertEquals(u16(buf, 2), u16(buf, 4))

        // Distance: 1500m
        assertEquals(1500, u24(buf, 6))

        // Incline: 3.0 * 10 = 30
        assertEquals(30, s16(buf, 9))

        // Ramp angle: atan(3.0/100) in degrees * 10
        val expectedRamp = (Math.toDegrees(atan(3.0 / 100.0)) * 10).roundToInt()
        assertEquals(expectedRamp, s16(buf, 11))

        // Heart rate
        assertEquals(142, buf[13].toInt() and 0xFF)

        // Elapsed time
        assertEquals(600, u16(buf, 14))
    }

    @Test fun `zero state encodes as all zeros except flags`() {
        val buf = FtmsEncoder.encodeTreadmillData(0.0, 0.0, 0, 0, 0)
        assertEquals(0x0E, buf[0].toInt() and 0xFF)
        assertEquals(0x05, buf[1].toInt() and 0xFF)
        // Everything else should be zero
        for (i in 2 until 16) assertEquals(0, buf[i].toInt() and 0xFF, "byte[$i] should be 0")
    }

    @Test fun `negative incline encodes as signed int16`() {
        val buf = FtmsEncoder.encodeTreadmillData(5.0, -2.0, 0, 0, 0)

        // Incline: -2.0 * 10 = -20, encoded as signed int16 LE
        assertEquals(-20, s16(buf, 9))

        // Ramp angle should also be negative
        val expectedRamp = (Math.toDegrees(atan(-2.0 / 100.0)) * 10).roundToInt()
        assertEquals(expectedRamp, s16(buf, 11))
    }

    @Test fun `speed clamps to uint16 max`() {
        val buf = FtmsEncoder.encodeTreadmillData(700.0, 0.0, 0, 0, 0)
        // 700 * 100 = 70000 > 65535, should clamp to 65535
        assertEquals(65535, u16(buf, 2))
    }

    @Test fun `negative speed clamps to zero`() {
        val buf = FtmsEncoder.encodeTreadmillData(-1.0, 0.0, 0, 0, 0)
        assertEquals(0, u16(buf, 2))
    }

    @Test fun `distance clamps to uint24 max`() {
        val buf = FtmsEncoder.encodeTreadmillData(0.0, 0.0, 0, 0x1FFFFFF, 0)
        assertEquals(0xFFFFFF, u24(buf, 6))
    }

    @Test fun `average speed equals instantaneous speed`() {
        val buf = FtmsEncoder.encodeTreadmillData(12.3, 0.0, 0, 0, 0)
        assertContentEquals(buf.sliceArray(2..3), buf.sliceArray(4..5))
    }

    // ========== encodeHRMeasurement ==========

    @Test fun `HR measurement normal value`() {
        val buf = FtmsEncoder.encodeHRMeasurement(72)
        assertEquals(2, buf.size)
        assertEquals(0x00, buf[0].toInt())
        assertEquals(72, buf[1].toInt() and 0xFF)
    }

    @Test fun `HR measurement zero`() {
        val buf = FtmsEncoder.encodeHRMeasurement(0)
        assertEquals(0, buf[1].toInt() and 0xFF)
    }

    @Test fun `HR measurement clamps at 255`() {
        val buf = FtmsEncoder.encodeHRMeasurement(300)
        assertEquals(255, buf[1].toInt() and 0xFF)
    }

    @Test fun `HR measurement clamps negative to zero`() {
        val buf = FtmsEncoder.encodeHRMeasurement(-10)
        assertEquals(0, buf[1].toInt() and 0xFF)
    }
}
