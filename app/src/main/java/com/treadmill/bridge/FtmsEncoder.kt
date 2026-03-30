package com.treadmill.bridge

import kotlin.math.atan
import kotlin.math.roundToInt

/**
 * FTMS (Fitness Machine Service) data encoding for Dircon.
 * Byte layouts match qdomyos-zwift's tested implementation.
 */
object FtmsEncoder {

    /**
     * Encode FTMS Treadmill Data (characteristic 0x2ACD).
     * 16 bytes, little-endian where specified.
     *
     * @param speedKPH current speed in km/h
     * @param inclinePct current incline in %
     * @param heartRate current HR in bpm (0 if unknown)
     * @param distanceM total distance in meters
     * @param elapsedSec elapsed time in seconds
     */
    fun encodeTreadmillData(
        speedKPH: Double,
        inclinePct: Double,
        heartRate: Int,
        distanceM: Int,
        elapsedSec: Int
    ): ByteArray {
        val buf = ByteArray(16)

        // Flags
        buf[0] = 0x0E.toByte() // avg speed + distance + incline present
        buf[1] = 0x05.toByte() // heart rate + elapsed time present

        // Instantaneous speed (uint16 LE, 0.01 km/h)
        val speed100 = (speedKPH * 100).roundToInt().coerceIn(0, 65535)
        buf.putLeU16(2, speed100)

        // Average speed (same as instantaneous)
        buf.putLeU16(4, speed100)

        // Total distance (uint24 LE, meters)
        val dist = distanceM.coerceIn(0, 0xFFFFFF)
        buf[6] = (dist and 0xFF).toByte()
        buf[7] = ((dist shr 8) and 0xFF).toByte()
        buf[8] = ((dist shr 16) and 0xFF).toByte()

        // Inclination (int16 LE, 0.1%)
        val incline10 = (inclinePct * 10).roundToInt().coerceIn(-3276, 3276)
        buf.putLeU16(9, incline10)

        // Ramp angle (int16 LE, 0.1 degrees) = arctan(incline/100) in degrees * 10
        val rampDeg = Math.toDegrees(atan(inclinePct / 100.0))
        val ramp10 = (rampDeg * 10).roundToInt()
        buf.putLeU16(11, ramp10)

        // Heart rate (uint8)
        buf[13] = heartRate.coerceIn(0, 255).toByte()

        // Elapsed time (uint16 LE, seconds)
        val elapsed = elapsedSec.coerceIn(0, 65535)
        buf.putLeU16(14, elapsed)

        return buf
    }

    /** Encode Heart Rate Measurement (characteristic 0x2A37). */
    fun encodeHRMeasurement(heartRate: Int): ByteArray {
        return byteArrayOf(0x00, heartRate.coerceIn(0, 255).toByte())
    }
}
