package com.treadmill.bridge

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket

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
        private const val REANNOUNCE_MS = 60_000L
        private const val TTL = 120

        // ========== Pure DNS functions ==========

        internal fun isQueryForOurService(buf: ByteArray, len: Int): Boolean {
            if (len < 12) return false
            val flags = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
            if (flags and 0x8000 != 0) return false
            val qdCount = ((buf[4].toInt() and 0xFF) shl 8) or (buf[5].toInt() and 0xFF)
            if (qdCount == 0) return false

            var offset = 12
            for (i in 0 until qdCount) {
                val (name, nameLen) = DnsPacket.parseName(buf, offset, len)
                offset += nameLen
                if (offset + 4 > len) return false
                offset += 4 // skip QTYPE + QCLASS

                if (name.equals("_wahoo-fitness-tnp._tcp.local", ignoreCase = true) ||
                    name.equals("_services._dns-sd._udp.local", ignoreCase = true)) {
                    return true
                }
            }
            return false
        }

        internal fun buildResponsePacket(
            serviceName: String,
            port: Int,
            ipAddress: ByteArray,
            macAddress: String
        ): ByteArray {
            val serviceType = "_wahoo-fitness-tnp._tcp.local"
            val instanceName = "$serviceName.$serviceType"
            val hostname = "treadmill-bridge.local"

            return DnsPacket.buildResponse(listOf(
                DnsRecord.Ptr(serviceType, TTL, instanceName),
                DnsRecord.Srv(instanceName, TTL, 0, 0, port, hostname),
                DnsRecord.Txt(instanceName, TTL, listOf(
                    "ble-service-uuids=0x1826,0x180D",
                    "serial-number=treadmill-bridge-1",
                    "mac-address=$macAddress"
                )),
                DnsRecord.A(hostname, TTL, ipAddress)
            ))
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var multicastLock: WifiManager.MulticastLock

    // Pre-built response packet
    private val responsePacket: ByteArray by lazy {
        buildResponsePacket(serviceName, port, ipAddress, macAddress)
    }

    fun start() {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("mdns-advertiser").apply {
            setReferenceCounted(false)
            acquire()
        }
        Log.d(TAG, "MulticastLock acquired")

        scope.launch {
            try {
                val sock = MulticastSocket(MDNS_PORT)
                sock.reuseAddress = true
                sock.joinGroup(MDNS_ADDR)
                sock.timeToLive = 255
                sock.soTimeout = 1000
                Log.d(TAG, "Joined multicast group, listening on :$MDNS_PORT")

                try {
                    announce(sock)
                    var lastAnnounce = System.currentTimeMillis()

                    val buf = ByteArray(1500)
                    while (isActive) {
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

                        if (System.currentTimeMillis() - lastAnnounce > REANNOUNCE_MS) {
                            announce(sock)
                            lastAnnounce = System.currentTimeMillis()
                        }
                    }
                } finally {
                    sock.leaveGroup(MDNS_ADDR)
                    sock.close()
                }
            } catch (e: Exception) {
                if (isActive) Log.e(TAG, "mDNS error", e)
            }
        }
    }

    fun stop() {
        scope.cancel()
        multicastLock.release()
        Log.d(TAG, "Stopped")
    }

    private fun announce(sock: MulticastSocket) {
        val pkt = DatagramPacket(responsePacket, responsePacket.size, MDNS_ADDR, MDNS_PORT)
        sock.send(pkt)
        Log.d(TAG, "Announced $serviceName (${responsePacket.size} bytes)")
    }
}
