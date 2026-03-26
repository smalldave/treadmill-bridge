package com.treadmill.bridge

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MdnsAdvertiserTest {

    private val testIp = byteArrayOf(192.toByte(), 168.toByte(), 1, 42)
    private val testMac = "AA:BB:CC:DD:EE:FF"

    private fun buildPacket() =
        MdnsAdvertiser.buildResponsePacket("Treadmill Bridge", 36866, testIp, testMac)

    private fun parseRecords() = DnsPacket.parseResponse(buildPacket())

    // ========== Response Packet Structure ==========

    @Test fun `response packet has correct DNS header`() {
        val pkt = buildPacket()
        // Flags at bytes 2-3 (big-endian)
        val flags = ((pkt[2].toInt() and 0xFF) shl 8) or (pkt[3].toInt() and 0xFF)
        assertEquals(0x8400, flags) // response, authoritative
        // ANCOUNT at bytes 6-7
        val anCount = ((pkt[6].toInt() and 0xFF) shl 8) or (pkt[7].toInt() and 0xFF)
        assertEquals(4, anCount)
    }

    @Test fun `response contains 4 records of correct types`() {
        val records = parseRecords()
        assertEquals(4, records.size)
        assertTrue(records[0] is DnsRecord.Ptr)
        assertTrue(records[1] is DnsRecord.Srv)
        assertTrue(records[2] is DnsRecord.Txt)
        assertTrue(records[3] is DnsRecord.A)
    }

    @Test fun `PTR record points service type to instance name`() {
        val ptr = parseRecords()[0] as DnsRecord.Ptr
        assertEquals("_wahoo-fitness-tnp._tcp.local", ptr.name)
        assertEquals("Treadmill Bridge._wahoo-fitness-tnp._tcp.local", ptr.target)
        assertEquals(120, ptr.ttl)
    }

    @Test fun `SRV record has correct port and target`() {
        val srv = parseRecords()[1] as DnsRecord.Srv
        assertEquals("Treadmill Bridge._wahoo-fitness-tnp._tcp.local", srv.name)
        assertEquals(0, srv.priority)
        assertEquals(0, srv.weight)
        assertEquals(36866, srv.port)
        assertEquals("treadmill-bridge.local", srv.target)
    }

    @Test fun `TXT record contains service UUIDs and MAC address`() {
        val txt = parseRecords()[2] as DnsRecord.Txt
        assertEquals("Treadmill Bridge._wahoo-fitness-tnp._tcp.local", txt.name)
        assertTrue(txt.entries.contains("ble-service-uuids=0x1826,0x180D"))
        assertTrue(txt.entries.contains("serial-number=treadmill-bridge-1"))
        assertTrue(txt.entries.contains("mac-address=AA:BB:CC:DD:EE:FF"))
    }

    @Test fun `A record has correct hostname and IP`() {
        val a = parseRecords()[3] as DnsRecord.A
        assertEquals("treadmill-bridge.local", a.name)
        assertContentEquals(testIp, a.address)
    }

    // ========== Query Parsing ==========

    private fun buildQuery(name: String): ByteArray {
        val bos = ByteArrayOutputStream()
        val dos = DataOutputStream(bos)
        dos.writeShort(0x0000) // ID
        dos.writeShort(0x0000) // Flags: query
        dos.writeShort(1)      // QDCOUNT
        dos.writeShort(0); dos.writeShort(0); dos.writeShort(0)
        for (label in name.split(".")) {
            dos.writeByte(label.length); dos.writeBytes(label)
        }
        dos.writeByte(0)
        dos.writeShort(12) // QTYPE PTR
        dos.writeShort(1)  // QCLASS IN
        return bos.toByteArray()
    }

    @Test fun `isQueryForOurService matches wahoo service type`() {
        val q = buildQuery("_wahoo-fitness-tnp._tcp.local")
        assertTrue(MdnsAdvertiser.isQueryForOurService(q, q.size))
    }

    @Test fun `isQueryForOurService matches dns-sd browse`() {
        val q = buildQuery("_services._dns-sd._udp.local")
        assertTrue(MdnsAdvertiser.isQueryForOurService(q, q.size))
    }

    @Test fun `isQueryForOurService rejects unrelated service`() {
        val q = buildQuery("_http._tcp.local")
        assertFalse(MdnsAdvertiser.isQueryForOurService(q, q.size))
    }

    @Test fun `isQueryForOurService rejects response packets`() {
        val q = buildQuery("_wahoo-fitness-tnp._tcp.local")
        q[2] = (q[2].toInt() or 0x80).toByte()
        assertFalse(MdnsAdvertiser.isQueryForOurService(q, q.size))
    }

    @Test fun `isQueryForOurService rejects truncated packets`() {
        assertFalse(MdnsAdvertiser.isQueryForOurService(ByteArray(5), 5))
    }

    // ========== DNS Name Parsing ==========

    @Test fun `parseName parses uncompressed labels`() {
        val q = buildQuery("_wahoo-fitness-tnp._tcp.local")
        val (name, _) = DnsPacket.parseName(q, 12, q.size)
        assertEquals("_wahoo-fitness-tnp._tcp.local", name)
    }

    @Test fun `parseName follows compression pointer`() {
        val buf = ByteArray(30)
        var off = 0
        buf[off++] = 4; "_tcp".toByteArray().copyInto(buf, off); off += 4
        buf[off++] = 5; "local".toByteArray().copyInto(buf, off); off += 5
        buf[off++] = 0
        val pointerStart = off
        buf[off++] = 6; "_wahoo".toByteArray().copyInto(buf, off); off += 6
        buf[off++] = 0xC0.toByte(); buf[off++] = 0x00

        val (name, _) = DnsPacket.parseName(buf, pointerStart, off)
        assertEquals("_wahoo._tcp.local", name)
    }

    // ========== Golden Snapshot ==========

    @Test fun `buildResponsePacket produces byte-identical output`() {
        val hex = buildPacket().joinToString("") { "%02x".format(it) }
        assertEquals(
            "000084000000000400000000125f7761686f6f2d6669746e6573732d746e70045f746370056c6f63616c00000c00010000007800131054726561646d696c6c20427269646765c00cc0350021800100000078001e0000000090021074726561646d696c6c2d627269646765056c6f63616c00c0350010800100000078005f1f626c652d736572766963652d75756964733d3078313832362c3078313830442073657269616c2d6e756d6265723d74726561646d696c6c2d6272696467652d311d6d61632d616464726573733d41413a42423a43433a44443a45453a46461074726561646d696c6c2d627269646765056c6f63616c0000018001000000780004c0a8012a",
            hex
        )
    }
}
