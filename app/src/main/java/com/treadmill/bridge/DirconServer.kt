package com.treadmill.bridge

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Dircon (Wahoo Direct Connect) TCP server.
 * Serves FTMS treadmill data to Zwift over TCP port 36866.
 *
 * Protocol from qdomyos-zwift dirconpacket.cpp / dirconprocessor.cpp.
 */
class DirconServer(
    private val port: Int = 36866,
    private val dataProvider: () -> TreadmillSnapshot
) {
    companion object {
        private const val TAG = "Dircon"
        internal const val VERSION: Byte = 0x01

        // Message types
        internal const val MSG_DISCOVER_SERVICES: Byte = 0x01
        internal const val MSG_DISCOVER_CHARS: Byte = 0x02
        internal const val MSG_READ_CHAR: Byte = 0x03
        internal const val MSG_WRITE_CHAR: Byte = 0x04
        internal const val MSG_ENABLE_NOTIFY: Byte = 0x05
        internal const val MSG_UNSOLICITED_NOTIFY: Byte = 0x06
        private const val MSG_UNKNOWN_07: Byte = 0x07

        // Response codes
        internal const val RESP_SUCCESS: Byte = 0x00
        internal const val RESP_SERVICE_NOT_FOUND: Byte = 0x03
        internal const val RESP_CHAR_NOT_FOUND: Byte = 0x04

        // Characteristic properties
        private const val PROP_READ = 0x01
        private const val PROP_WRITE = 0x02
        private const val PROP_NOTIFY = 0x04
        private const val PROP_INDICATE = 0x08

        // BLE Base UUID template
        private val BLE_BASE_UUID = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x10, 0x00,
            0x80.toByte(), 0x00, 0x00, 0x80.toByte(), 0x5F, 0x9B.toByte(), 0x34, 0xFB.toByte()
        )

        internal fun uuidToBytes(short: Int): ByteArray {
            val uuid = BLE_BASE_UUID.copyOf()
            uuid[2] = ((short shr 8) and 0xFF).toByte()
            uuid[3] = (short and 0xFF).toByte()
            return uuid
        }

        internal fun uuidFromBytes(bytes: ByteArray, offset: Int = 0): Int {
            return ((bytes[offset + 2].toInt() and 0xFF) shl 8) or (bytes[offset + 3].toInt() and 0xFF)
        }
    }

    /** Dircon protocol packet — shared between server and tests. */
    data class DirconPacket(
        val msgType: Byte,
        val seq: Int,
        val respCode: Byte,
        val payload: ByteArray
    ) {
        fun toBytes(): ByteArray {
            val pkt = ByteArray(6 + payload.size)
            pkt[0] = VERSION
            pkt[1] = msgType
            pkt[2] = seq.toByte()
            pkt[3] = respCode
            pkt[4] = ((payload.size shr 8) and 0xFF).toByte()
            pkt[5] = (payload.size and 0xFF).toByte()
            payload.copyInto(pkt, 6)
            return pkt
        }

        companion object {
            fun parse(bytes: ByteArray): DirconPacket {
                val payloadLen = ((bytes[4].toInt() and 0xFF) shl 8) or (bytes[5].toInt() and 0xFF)
                return DirconPacket(
                    msgType = bytes[1],
                    seq = bytes[2].toInt() and 0xFF,
                    respCode = bytes[3],
                    payload = if (payloadLen > 0) bytes.copyOfRange(6, 6 + payloadLen) else ByteArray(0)
                )
            }
        }
    }

    data class TreadmillSnapshot(
        val speedKPH: Double = 0.0,
        val inclinePct: Double = 0.0,
        val heartRate: Int = 0,
        val distanceM: Int = 0,
        val elapsedSec: Int = 0
    )

    // Characteristic definition
    data class CharDef(val uuid: Int, val props: Int, val readValue: ByteArray)

    // Service definition
    data class ServiceDef(val uuid: Int, val chars: List<CharDef>)

    // Treadmill FTMS service (from qdomyos-zwift dirconmanager.cpp lines 53-66)
    private val services = listOf(
        ServiceDef(0x1826, listOf(
            CharDef(0x2ACC, PROP_READ, byteArrayOf(0x08, 0x14, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)),
            CharDef(0x2AD6, PROP_READ, byteArrayOf(0x0A, 0x00, 0x96.toByte(), 0x00, 0x0A, 0x00)),
            CharDef(0x2AD3, PROP_READ, byteArrayOf(0x00, 0x01)),
            CharDef(0x2AD9, PROP_WRITE or PROP_INDICATE, byteArrayOf(0x00)),
            CharDef(0x2ACD, PROP_NOTIFY, byteArrayOf(0x00)),
            CharDef(0x2ADA, PROP_NOTIFY, byteArrayOf(0x00))
        )),
        ServiceDef(0x180D, listOf(
            CharDef(0x2A37, PROP_NOTIFY, byteArrayOf(0x00))
        ))
    )

    private val clients = CopyOnWriteArrayList<ClientState>()
    private var running = false
    var onControlCommand: ((opcode: Int, params: ByteArray) -> Boolean)? = null

    class ClientState(
        val socket: Socket,
        val output: OutputStream,
        var seq: Int = 0,
        val notifyUuids: MutableList<Int> = mutableListOf()
    )

    fun start() {
        running = true
        thread(name = "dircon-accept") {
            try {
                val server = ServerSocket(port)
                Log.d(TAG, "Listening on TCP :$port")
                while (running) {
                    val socket = server.accept()
                    Log.d(TAG, "Client connected: ${socket.inetAddress}")
                    val client = ClientState(socket, socket.getOutputStream())
                    clients.add(client)
                    thread(name = "dircon-client") { handleClient(client) }
                }
            } catch (e: Exception) {
                if (running) Log.e(TAG, "Server error", e)
            }
        }

        // Notification push loop
        thread(name = "dircon-notify") {
            while (running) {
                try {
                    pushNotifications()
                } catch (e: Exception) {
                    Log.e(TAG, "Notify error", e)
                }
                Thread.sleep(250)
            }
        }
    }

    fun stop() {
        running = false
        clients.forEach { it.socket.close() }
        clients.clear()
    }

    val clientCount get() = clients.size

    private fun handleClient(client: ClientState) {
        try {
            val input = client.socket.getInputStream()
            val buf = ByteArray(256)
            val accumulator = mutableListOf<Byte>()

            while (running && !client.socket.isClosed) {
                val n = input.read(buf)
                if (n <= 0) break
                for (i in 0 until n) accumulator.add(buf[i])

                while (accumulator.size >= 6) {
                    val payloadLen = ((accumulator[4].toInt() and 0xFF) shl 8) or
                                    (accumulator[5].toInt() and 0xFF)
                    val totalLen = 6 + payloadLen
                    if (accumulator.size < totalLen) break

                    val packet = accumulator.subList(0, totalLen).toByteArray()
                    accumulator.subList(0, totalLen).clear()

                    val response = processPacket(client, packet)
                    if (response != null) {
                        synchronized(client.output) {
                            client.output.write(response)
                            client.output.flush()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (running) Log.d(TAG, "Client disconnected: ${e.message}")
        } finally {
            clients.remove(client)
            client.socket.close()
        }
    }

    private fun processPacket(client: ClientState, pkt: ByteArray): ByteArray? {
        val req = DirconPacket.parse(pkt)
        client.seq = req.seq

        return when (req.msgType) {
            MSG_DISCOVER_SERVICES -> handleDiscoverServices(req.seq)
            MSG_DISCOVER_CHARS -> handleDiscoverChars(req.seq, req.payload)
            MSG_READ_CHAR -> handleReadChar(req.seq, req.payload)
            MSG_WRITE_CHAR -> handleWriteChar(req.seq, req.payload)
            MSG_ENABLE_NOTIFY -> handleEnableNotify(client, req.seq, req.payload)
            MSG_UNKNOWN_07 -> respond(MSG_UNKNOWN_07, req.seq, RESP_SUCCESS, ByteArray(0))
            else -> {
                Log.w(TAG, "Unknown msg type: ${req.msgType}")
                null
            }
        }
    }

    private fun handleDiscoverServices(seq: Int): ByteArray {
        val uuids = ByteArray(services.size * 16)
        services.forEachIndexed { i, svc -> uuidToBytes(svc.uuid).copyInto(uuids, i * 16) }
        return respond(MSG_DISCOVER_SERVICES, seq, RESP_SUCCESS, uuids)
    }

    private fun handleDiscoverChars(seq: Int, payload: ByteArray): ByteArray {
        if (payload.size < 16) return respond(MSG_DISCOVER_CHARS, seq, RESP_SERVICE_NOT_FOUND, ByteArray(0))
        val svcUuid = uuidFromBytes(payload)
        val svc = services.find { it.uuid == svcUuid }
            ?: return respond(MSG_DISCOVER_CHARS, seq, RESP_SERVICE_NOT_FOUND, ByteArray(0))

        // Response: service UUID (16) + for each char: UUID (16) + props (1)
        val data = ByteArray(16 + svc.chars.size * 17)
        uuidToBytes(svcUuid).copyInto(data, 0)
        svc.chars.forEachIndexed { i, ch ->
            uuidToBytes(ch.uuid).copyInto(data, 16 + i * 17)
            data[16 + i * 17 + 16] = ch.props.toByte()
        }
        return respond(MSG_DISCOVER_CHARS, seq, RESP_SUCCESS, data)
    }

    private fun handleReadChar(seq: Int, payload: ByteArray): ByteArray {
        if (payload.size < 16) return respond(MSG_READ_CHAR, seq, RESP_CHAR_NOT_FOUND, ByteArray(0))
        val charUuid = uuidFromBytes(payload)
        val charDef = services.flatMap { it.chars }.find { it.uuid == charUuid }
            ?: return respond(MSG_READ_CHAR, seq, RESP_CHAR_NOT_FOUND, ByteArray(0))

        val data = ByteArray(16 + charDef.readValue.size)
        uuidToBytes(charUuid).copyInto(data, 0)
        charDef.readValue.copyInto(data, 16)
        return respond(MSG_READ_CHAR, seq, RESP_SUCCESS, data)
    }

    private fun handleWriteChar(seq: Int, payload: ByteArray): ByteArray {
        if (payload.size < 16) return respond(MSG_WRITE_CHAR, seq, RESP_CHAR_NOT_FOUND, ByteArray(0))
        val charUuid = uuidFromBytes(payload)
        val writeData = if (payload.size > 16) payload.copyOfRange(16, payload.size) else ByteArray(0)

        Log.d(TAG, "Write 0x${charUuid.toString(16)}: ${writeData.joinToString(" ") { "%02X".format(it) }}")

        if (charUuid == 0x2AD9 && writeData.isNotEmpty()) {
            // FTMS Control Point
            val opcode = writeData[0].toInt() and 0xFF
            val params = if (writeData.size > 1) writeData.copyOfRange(1, writeData.size) else ByteArray(0)
            val ok = onControlCommand?.invoke(opcode, params) ?: false

            // Send indication response: UUID + [0x80, opcode, result]
            val indication = ByteArray(16 + 3)
            uuidToBytes(charUuid).copyInto(indication, 0)
            indication[16] = 0x80.toByte()
            indication[17] = opcode.toByte()
            indication[18] = if (ok) 0x01 else 0x02
            return respond(MSG_WRITE_CHAR, seq, RESP_SUCCESS, indication)
        }

        // Generic write response
        val respData = ByteArray(16)
        uuidToBytes(charUuid).copyInto(respData, 0)
        return respond(MSG_WRITE_CHAR, seq, RESP_SUCCESS, respData)
    }

    private fun handleEnableNotify(client: ClientState, seq: Int, payload: ByteArray): ByteArray {
        if (payload.size < 16) return respond(MSG_ENABLE_NOTIFY, seq, RESP_CHAR_NOT_FOUND, ByteArray(0))
        val charUuid = uuidFromBytes(payload)
        val enable = payload.size >= 17 && payload[16].toInt() != 0

        if (enable && charUuid !in client.notifyUuids) {
            client.notifyUuids.add(charUuid)
            Log.d(TAG, "Notifications enabled for 0x${charUuid.toString(16)}")
        } else if (!enable) {
            client.notifyUuids.remove(charUuid)
            Log.d(TAG, "Notifications disabled for 0x${charUuid.toString(16)}")
        }

        val respData = ByteArray(16)
        uuidToBytes(charUuid).copyInto(respData, 0)
        return respond(MSG_ENABLE_NOTIFY, seq, RESP_SUCCESS, respData)
    }

    private fun pushNotifications() {
        val snapshot = dataProvider()

        for (client in clients) {
            try {
                if (0x2ACD in client.notifyUuids) {
                    val treadmillData = FtmsEncoder.encodeTreadmillData(
                        snapshot.speedKPH, snapshot.inclinePct, snapshot.heartRate,
                        snapshot.distanceM, snapshot.elapsedSec
                    )
                    val payload = ByteArray(16 + treadmillData.size)
                    uuidToBytes(0x2ACD).copyInto(payload, 0)
                    treadmillData.copyInto(payload, 16)

                    val pkt = respond(MSG_UNSOLICITED_NOTIFY, 0, RESP_SUCCESS, payload)
                    synchronized(client.output) {
                        client.output.write(pkt)
                        client.output.flush()
                    }
                }

                if (0x2A37 in client.notifyUuids && snapshot.heartRate > 0) {
                    val hrData = FtmsEncoder.encodeHRMeasurement(snapshot.heartRate)
                    val payload = ByteArray(16 + hrData.size)
                    uuidToBytes(0x2A37).copyInto(payload, 0)
                    hrData.copyInto(payload, 16)

                    val pkt = respond(MSG_UNSOLICITED_NOTIFY, 0, RESP_SUCCESS, payload)
                    synchronized(client.output) {
                        client.output.write(pkt)
                        client.output.flush()
                    }
                }
            } catch (e: Exception) {
                // Client disconnected — will be cleaned up in handleClient
            }
        }
    }

    // --- Helpers ---

    private fun respond(msgType: Byte, seq: Int, respCode: Byte, payload: ByteArray): ByteArray =
        DirconPacket(msgType, seq, respCode, payload).toBytes()

}
