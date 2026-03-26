package com.treadmill.bridge

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * DNS record types used in mDNS response packets.
 */
sealed class DnsRecord(val name: String, val ttl: Int) {
    class Ptr(name: String, ttl: Int, val target: String) : DnsRecord(name, ttl)
    class Srv(name: String, ttl: Int, val priority: Int, val weight: Int,
              val port: Int, val target: String) : DnsRecord(name, ttl)
    class Txt(name: String, ttl: Int, val entries: List<String>) : DnsRecord(name, ttl)
    class A(name: String, ttl: Int, val address: ByteArray) : DnsRecord(name, ttl)
}

/**
 * DNS packet serialization and parsing for mDNS.
 */
object DnsPacket {

    // Record types
    private const val TYPE_A: Short = 1
    private const val TYPE_PTR: Short = 12
    private const val TYPE_TXT: Short = 16
    private const val TYPE_SRV: Short = 33

    // Classes
    private const val CLASS_IN: Short = 1
    private const val CLASS_CACHE_FLUSH = 0x8001.toShort()

    /** Build an mDNS response packet from a list of records. */
    fun buildResponse(records: List<DnsRecord>): ByteArray {
        val bos = ByteArrayOutputStream(512)
        val dos = DataOutputStream(bos)
        val nameOffsets = mutableMapOf<String, Int>()

        // DNS header
        dos.writeShort(0x0000)  // ID
        dos.writeShort(0x8400)  // Flags: response, authoritative
        dos.writeShort(0)       // Questions
        dos.writeShort(records.size) // Answers
        dos.writeShort(0)       // Authority
        dos.writeShort(0)       // Additional

        for (record in records) {
            when (record) {
                is DnsRecord.Ptr -> {
                    writeDnsName(dos, bos, record.name, nameOffsets)
                    dos.writeShort(TYPE_PTR.toInt())
                    dos.writeShort(CLASS_IN.toInt()) // PTR doesn't use cache-flush
                    dos.writeInt(record.ttl)
                    val rdata = buildNameBytes(record.target, nameOffsets, bos.size() + 2)
                    dos.writeShort(rdata.size)
                    nameOffsets[record.target] = bos.size()
                    dos.write(rdata)
                }
                is DnsRecord.Srv -> {
                    writeDnsName(dos, bos, record.name, nameOffsets)
                    dos.writeShort(TYPE_SRV.toInt())
                    dos.writeShort(CLASS_CACHE_FLUSH.toInt())
                    dos.writeInt(record.ttl)
                    val rdata = ByteArrayOutputStream().let { srv ->
                        val srvDos = DataOutputStream(srv)
                        srvDos.writeShort(record.priority)
                        srvDos.writeShort(record.weight)
                        srvDos.writeShort(record.port)
                        // Target hostname uncompressed in SRV RDATA per RFC
                        for (part in record.target.split(".")) {
                            srvDos.writeByte(part.length)
                            srvDos.writeBytes(part)
                        }
                        srvDos.writeByte(0)
                        srv.toByteArray()
                    }
                    dos.writeShort(rdata.size)
                    dos.write(rdata)
                }
                is DnsRecord.Txt -> {
                    writeDnsName(dos, bos, record.name, nameOffsets)
                    dos.writeShort(TYPE_TXT.toInt())
                    dos.writeShort(CLASS_CACHE_FLUSH.toInt())
                    dos.writeInt(record.ttl)
                    val rdata = ByteArrayOutputStream().let { txt ->
                        for (entry in record.entries) {
                            txt.write(entry.length)
                            txt.write(entry.toByteArray())
                        }
                        txt.toByteArray()
                    }
                    dos.writeShort(rdata.size)
                    dos.write(rdata)
                }
                is DnsRecord.A -> {
                    writeDnsName(dos, bos, record.name, nameOffsets)
                    dos.writeShort(TYPE_A.toInt())
                    dos.writeShort(CLASS_CACHE_FLUSH.toInt())
                    dos.writeInt(record.ttl)
                    dos.writeShort(4)
                    dos.write(record.address)
                }
            }
        }

        return bos.toByteArray()
    }

    /** Parse an mDNS response packet into a list of records. */
    fun parseResponse(bytes: ByteArray): List<DnsRecord> {
        val len = bytes.size
        val buf = bytes.asBEBuffer()
        buf.position(6)
        val anCount = buf.readU16()
        buf.position(12) // skip rest of header

        val records = mutableListOf<DnsRecord>()
        for (i in 0 until anCount) {
            val (name, nameLen) = parseName(bytes, buf.position(), len)
            buf.position(buf.position() + nameLen)
            val type = buf.readU16()
            buf.readU16() // class
            val ttl = buf.readS32()
            val rdLength = buf.readU16()
            val rdStart = buf.position()

            when (type.toShort()) {
                TYPE_PTR -> {
                    val (target, _) = parseName(bytes, buf.position(), len)
                    records.add(DnsRecord.Ptr(name, ttl, target))
                }
                TYPE_SRV -> {
                    val priority = buf.readU16()
                    val weight = buf.readU16()
                    val port = buf.readU16()
                    val (target, _) = parseName(bytes, buf.position(), len)
                    records.add(DnsRecord.Srv(name, ttl, priority, weight, port, target))
                }
                TYPE_TXT -> {
                    val entries = mutableListOf<String>()
                    var pos = buf.position()
                    while (pos < rdStart + rdLength) {
                        val entryLen = bytes[pos].toInt() and 0xFF
                        entries.add(String(bytes, pos + 1, entryLen))
                        pos += 1 + entryLen
                    }
                    records.add(DnsRecord.Txt(name, ttl, entries))
                }
                TYPE_A -> {
                    records.add(DnsRecord.A(name, ttl, bytes.copyOfRange(buf.position(), buf.position() + 4)))
                }
            }

            buf.position(rdStart + rdLength)
        }
        return records
    }

    /** Parse a DNS name from bytes, returning (name, bytesConsumed). */
    fun parseName(bytes: ByteArray, startOffset: Int, len: Int): Pair<String, Int> {
        val parts = mutableListOf<String>()
        var offset = startOffset
        var bytesConsumed = 0
        var followedPointer = false

        while (offset < len) {
            val b = bytes[offset].toInt() and 0xFF
            if (b == 0) {
                if (!followedPointer) bytesConsumed = offset - startOffset + 1
                break
            }
            if (b and 0xC0 == 0xC0) {
                if (!followedPointer) bytesConsumed = offset - startOffset + 2
                followedPointer = true
                offset = ((b and 0x3F) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
                continue
            }
            parts.add(String(bytes, offset + 1, b))
            offset += 1 + b
        }
        if (!followedPointer) bytesConsumed = offset - startOffset + 1
        return parts.joinToString(".") to bytesConsumed
    }

    // --- Name writing (compression) ---

    private fun writeDnsName(dos: DataOutputStream, bos: ByteArrayOutputStream, name: String, offsets: MutableMap<String, Int>) {
        val existing = offsets[name]
        if (existing != null) {
            dos.writeShort(0xC000 or existing)
            return
        }

        val parts = name.split(".")
        for (i in parts.indices) {
            val suffix = parts.subList(i, parts.size).joinToString(".")
            val suffixOffset = offsets[suffix]
            if (suffixOffset != null) {
                offsets[name] = bos.size()
                for (j in 0 until i) {
                    dos.writeByte(parts[j].length)
                    dos.writeBytes(parts[j])
                }
                dos.writeShort(0xC000 or suffixOffset)
                return
            }
        }

        offsets[name] = bos.size()
        for (part in parts) {
            dos.writeByte(part.length)
            dos.writeBytes(part)
        }
        dos.writeByte(0)
    }

    private fun buildNameBytes(name: String, offsets: Map<String, Int>, currentOffset: Int): ByteArray {
        val bos2 = ByteArrayOutputStream()
        val dos2 = DataOutputStream(bos2)
        val parts = name.split(".")

        for (i in parts.indices) {
            val suffix = parts.subList(i, parts.size).joinToString(".")
            val suffixOffset = offsets[suffix]
            if (suffixOffset != null) {
                for (j in 0 until i) {
                    dos2.writeByte(parts[j].length)
                    dos2.writeBytes(parts[j])
                }
                dos2.writeShort(0xC000 or suffixOffset)
                return bos2.toByteArray()
            }
        }

        for (part in parts) {
            dos2.writeByte(part.length)
            dos2.writeBytes(part)
        }
        dos2.writeByte(0)
        return bos2.toByteArray()
    }

}
