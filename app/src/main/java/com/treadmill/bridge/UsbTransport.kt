package com.treadmill.bridge

/**
 * Byte-level USB transport abstraction.
 * Production: wraps UsbDeviceConnection.bulkTransfer.
 * Tests: fake implementation with scripted responses.
 */
interface UsbTransport {
    /** Write data to the device. Returns true on success. */
    fun write(data: ByteArray): Boolean

    /** Read into buf. Returns bytes read, or -1 on failure. */
    fun read(buf: ByteArray): Int
}
