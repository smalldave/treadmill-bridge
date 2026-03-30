package com.treadmill.bridge

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Wrap a ByteArray as a little-endian ByteBuffer for sequential reads. */
fun ByteArray.asLEBuffer(): ByteBuffer =
    ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)

/** Wrap a ByteArray as a big-endian ByteBuffer for sequential reads. */
fun ByteArray.asBEBuffer(): ByteBuffer =
    ByteBuffer.wrap(this).order(ByteOrder.BIG_ENDIAN)

fun ByteBuffer.readU8(): Int = get().toInt() and 0xFF
fun ByteBuffer.readS8(): Int = get().toInt()
fun ByteBuffer.readU16(): Int = short.toInt() and 0xFFFF
fun ByteBuffer.readS16(): Int = short.toInt()
fun ByteBuffer.readU32(): Long = int.toLong() and 0xFFFFFFFFL
fun ByteBuffer.readS32(): Int = int

/** Read a little-endian unsigned 16-bit integer from two bytes at the given offset. */
fun ByteArray.leU16At(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

/** Read a little-endian signed 16-bit integer from two bytes at the given offset. */
fun ByteArray.leS16At(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)).toShort().toInt()

/** Encode a 16-bit integer as two little-endian bytes. */
fun leU16Bytes(value: Int): ByteArray =
    byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())
