package com.treadmill.bridge

/**
 * Hardware profile for the NordicTrack Commercial 2450.
 * All treadmill-specific constants in one place.
 */
object TreadmillProfile {
    const val NAME = "NordicTrack Commercial 2450"

    // USB identity
    const val USB_VENDOR_ID = 8508
    const val USB_PRODUCT_ID = 2

    // Speed limits (km/h)
    const val MIN_SPEED_KPH = 1.6
    const val MAX_SPEED_KPH = 15.0
    const val SPEED_STEP_KPH = 1.0

    // FTMS advertised speed range (0.1 km/h units)
    const val FTMS_SPEED_MIN = 10    // 1.0 km/h
    const val FTMS_SPEED_MAX = 150   // 15.0 km/h
    const val FTMS_SPEED_STEP = 10   // 1.0 km/h

    // FTMS feature flags
    const val FEATURE_INCLINE_SUPPORTED = 0x08
    const val FEATURE_SPEED_TARGET = 0x04
    const val FEATURE_INCLINE_TARGET = 0x10
    val FTMS_FEATURE_FLAGS = byteArrayOf(
        FEATURE_INCLINE_SUPPORTED.toByte(),
        (FEATURE_SPEED_TARGET or FEATURE_INCLINE_TARGET).toByte(),
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    )

    val FTMS_SPEED_RANGE =
        leU16Bytes(FTMS_SPEED_MIN) + leU16Bytes(FTMS_SPEED_MAX) + leU16Bytes(FTMS_SPEED_STEP)
}
