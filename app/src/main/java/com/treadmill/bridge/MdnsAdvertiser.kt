package com.treadmill.bridge

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import kotlin.concurrent.thread

/**
 * Raw multicast mDNS service advertiser.
 * Replaces Android's NsdManager which has cross-platform discovery issues.
 *
 * Advertises a _wahoo-fitness-tnp._tcp.local service for Zwift Dircon discovery.
 */
class MdnsAdvertiser(
    private val context: Context,
    private val serviceName: String = "Treadmill Bridge",
    private val port: Int = 36866,
    private val ipAddress: ByteArray,
    private val macAddress: String
) {
    companion object {
        private const val TAG = "mDNS"
        private const val MDNS_PORT = 5353
        private val MDNS_ADDR = InetAddress.getByName("224.0.0.251")
        private const val TTL = 120
        private const val REANNOUNCE_MS = 60_000L

        // DNS record types
        private const val TYPE_A: Short = 1
        private const val TYPE_PTR: Short = 12
        private const val TYPE_TXT: Short = 16
        private const val TYPE_SRV: Short = 33

        // DNS class
        private const val CLASS_IN: Short = 1
        private const val CLASS_CACHE_FLUSH = 0x8001.toShort()
    }

    private var running = false
    private var multicastLock: WifiManager.MulticastLock? = null
    private var socket: MulticastSocket? = null

    // Pre-built response packet
    private val responsePacket: ByteArray by lazy { buildResponsePacket() }

    fun start() {
        running = true
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("mdns-advertiser").apply {
            setReferenceCounted(false)
            acquire()
        }
        Log.d(TAG, "MulticastLock acquired")

        thread(name = "mdns") {
            try {
                val sock = MulticastSocket(MDNS_PORT)
                sock.reuseAddress = true
                sock.joinGroup(MDNS_ADDR)
                sock.timeToLive = 255
                sock.soTimeout = 1000
                socket = sock
                Log.d(TAG, "Joined multicast group, listening on :$MDNS_PORT")

                // Initial announcement
                announce(sock)
                var lastAnnounce = System.currentTimeMillis()

                val buf = ByteArray(1500)
                while (running) {
                    try {
                        val pkt = DatagramPacket(buf, buf.size)
                        sock.receive(pkt)
                        if (isQueryForOurService(buf, pkt.length)) {
                            Log.d(TAG, "Query received, responding")
                            announce(sock)
                            lastAnnounce = System.currentTimeMillis()
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                        // Normal — check if re-announce needed
                    }

                    // Periodic re-announce
                    if (System.currentTimeMillis() - lastAnnounce > REANNOUNCE_MS) {
                        announce(sock)
                        lastAnnounce = System.currentTimeMillis()
                    }
                }

                sock.leaveGroup(MDNS_ADDR)
                sock.close()
            } catch (e: Exception) {
                if (running) Log.e(TAG, "mDNS error", e)
            }
        }
    }

    fun stop() {
        running = false
        socket?.close()
        multicastLock?.release()
        Log.d(TAG, "Stopped")
    }

    private fun announce(sock: MulticastSocket) {
        val pkt = DatagramPacket(responsePacket, responsePacket.size, MDNS_ADDR, MDNS_PORT)
        sock.send(pkt)
        Log.d(TAG, "Announced $serviceName (${responsePacket.size} bytes)")
    }

    // ========== Query Parsing ==========

    private fun isQueryForOurService(buf: ByteArray, len: Int): Boolean {
        if (len < 12) return false
        val flags = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
        if (flags and 0x8000 != 0) return false // Not a query (QR bit set = response)
        val qdCount = ((buf[4].toInt() and 0xFF) shl 8) or (buf[5].toInt() and 0xFF)
        if (qdCount == 0) return false

        // Parse question names looking for our service type
        var offset = 12
        for (i in 0 until qdCount) {
            val name = readDnsName(buf, offset, len)
            if (name == null) return false
            offset = skipDnsName(buf, offset, len)
            if (offset < 0 || offset + 4 > len) return false
            offset += 4 // skip QTYPE + QCLASS

            if (name.equals("_wahoo-fitness-tnp._tcp.local", ignoreCase = true) ||
                name.equals("_services._dns-sd._udp.local", ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    private fun readDnsName(buf: ByteArray, startOffset: Int, len: Int): String? {
        val parts = mutableListOf<String>()
        var offset = startOffset
        var followed = false
        var safety = 0
        while (offset < len && safety++ < 50) {
            val b = buf[offset].toInt() and 0xFF
            if (b == 0) break
            if (b and 0xC0 == 0xC0) {
                // Pointer
                if (offset + 1 >= len) return null
                offset = ((b and 0x3F) shl 8) or (buf[offset + 1].toInt() and 0xFF)
                followed = true
                continue
            }
            if (offset + 1 + b > len) return null
            parts.add(String(buf, offset + 1, b))
            offset += 1 + b
        }
        return parts.joinToString(".")
    }

    private fun skipDnsName(buf: ByteArray, startOffset: Int, len: Int): Int {
        var offset = startOffset
        while (offset < len) {
            val b = buf[offset].toInt() and 0xFF
            if (b == 0) return offset + 1
            if (b and 0xC0 == 0xC0) return offset + 2
            offset += 1 + b
        }
        return -1
    }

    // ========== Response Building ==========

    private fun buildResponsePacket(): ByteArray {
        val bos = ByteArrayOutputStream(512)
        val dos = DataOutputStream(bos)

        // Track name offsets for compression
        val nameOffsets = mutableMapOf<String, Int>()

        // === DNS Header ===
        dos.writeShort(0x0000)  // ID
        dos.writeShort(0x8400)  // Flags: response, authoritative
        dos.writeShort(0)       // Questions
        dos.writeShort(4)       // Answers: PTR + SRV + TXT + A
        dos.writeShort(0)       // Authority
        dos.writeShort(0)       // Additional

        val serviceType = "_wahoo-fitness-tnp._tcp.local"
        val instanceName = "$serviceName.$serviceType"
        val hostname = "treadmill-bridge.local"

        // === PTR Record: serviceType → instanceName ===
        writeDnsName(dos, bos, serviceType, nameOffsets)
        dos.writeShort(TYPE_PTR.toInt())
        dos.writeShort(CLASS_IN.toInt())  // PTR doesn't use cache-flush
        dos.writeInt(TTL)
        // RDATA = instanceName
        val ptrRdata = buildNameBytes(instanceName, nameOffsets, bos.size() + 2)
        dos.writeShort(ptrRdata.size)
        // Record the instance name offset before writing
        nameOffsets[instanceName] = bos.size()
        dos.write(ptrRdata)

        // === SRV Record: instanceName → hostname:port ===
        writeDnsName(dos, bos, instanceName, nameOffsets)
        dos.writeShort(TYPE_SRV.toInt())
        dos.writeShort(CLASS_CACHE_FLUSH.toInt())
        dos.writeInt(TTL)
        val srvRdata = ByteArrayOutputStream().let { srv ->
            val srvDos = DataOutputStream(srv)
            srvDos.writeShort(0) // priority
            srvDos.writeShort(0) // weight
            srvDos.writeShort(port)
            // Target hostname (uncompressed in SRV RDATA per RFC)
            for (part in hostname.split(".")) {
                srvDos.writeByte(part.length)
                srvDos.writeBytes(part)
            }
            srvDos.writeByte(0) // terminator
            srv.toByteArray()
        }
        dos.writeShort(srvRdata.size)
        dos.write(srvRdata)

        // === TXT Record: instanceName → key=value pairs ===
        writeDnsName(dos, bos, instanceName, nameOffsets)
        dos.writeShort(TYPE_TXT.toInt())
        dos.writeShort(CLASS_CACHE_FLUSH.toInt())
        dos.writeInt(TTL)
        val txtEntries = listOf(
            "ble-service-uuids=0x1826,0x180D",
            "serial-number=treadmill-bridge-1",
            "mac-address=$macAddress"
        )
        val txtRdata = ByteArrayOutputStream().let { txt ->
            for (entry in txtEntries) {
                txt.write(entry.length)
                txt.write(entry.toByteArray())
            }
            txt.toByteArray()
        }
        dos.writeShort(txtRdata.size)
        dos.write(txtRdata)

        // === A Record: hostname → IP ===
        writeDnsName(dos, bos, hostname, nameOffsets)
        dos.writeShort(TYPE_A.toInt())
        dos.writeShort(CLASS_CACHE_FLUSH.toInt())
        dos.writeInt(TTL)
        dos.writeShort(4) // RDLENGTH
        dos.write(ipAddress)

        val packet = bos.toByteArray()
        Log.d(TAG, "Built response packet: ${packet.size} bytes")
        return packet
    }

    private fun writeDnsName(dos: DataOutputStream, bos: ByteArrayOutputStream, name: String, offsets: MutableMap<String, Int>) {
        // Check if we can use a pointer to a previously written name
        val existing = offsets[name]
        if (existing != null) {
            dos.writeShort(0xC000 or existing)
            return
        }

        // Check for suffix match
        val parts = name.split(".")
        for (i in parts.indices) {
            val suffix = parts.subList(i, parts.size).joinToString(".")
            val suffixOffset = offsets[suffix]
            if (suffixOffset != null) {
                // Write prefix labels then pointer
                offsets[name] = bos.size()
                for (j in 0 until i) {
                    dos.writeByte(parts[j].length)
                    dos.writeBytes(parts[j])
                }
                dos.writeShort(0xC000 or suffixOffset)
                return
            }
        }

        // Write full name
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
