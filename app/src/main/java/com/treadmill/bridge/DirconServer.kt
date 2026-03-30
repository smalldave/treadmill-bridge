package com.treadmill.bridge

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Dircon (Wahoo Direct Connect) TCP server.
 * Serves FTMS treadmill data to Zwift over TCP port 36866.
 *
 * Protocol from qdomyos-zwift dirconpacket.cpp / dirconprocessor.cpp.
 */
class DirconServer(
    private val port: Int = 36866,
    private val dataProvider: () -> TreadmillSnapshot,
    private val onControlCommand: suspend (command: FtmsCommand, params: ByteArray) -> Boolean
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

        // BLE service UUIDs
        private const val SVC_FTMS = 0x1826
        private const val SVC_HEART_RATE = 0x180D

        // Training status: idle
        private val TRAINING_STATUS_IDLE = byteArrayOf(0x00, 0x01)

        // BLE characteristic UUIDs
        private const val CHAR_FTMS_FEATURE = 0x2ACC
        private const val CHAR_SUPPORTED_SPEED_RANGE = 0x2AD6
        private const val CHAR_TRAINING_STATUS = 0x2AD3
        private const val CHAR_FTMS_CONTROL_POINT = 0x2AD9
        private const val CHAR_TREADMILL_DATA = 0x2ACD
        private const val CHAR_FTMS_STATUS = 0x2ADA
        private const val CHAR_HR_MEASUREMENT = 0x2A37

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

        // Treadmill FTMS service (from qdomyos-zwift dirconmanager.cpp lines 53-66)
        private val SERVICES = listOf(
            ServiceDef(SVC_FTMS, listOf(
                CharDef(CHAR_FTMS_FEATURE, PROP_READ, TreadmillProfile.FTMS_FEATURE_FLAGS),
                CharDef(CHAR_SUPPORTED_SPEED_RANGE, PROP_READ, TreadmillProfile.FTMS_SPEED_RANGE),
                CharDef(CHAR_TRAINING_STATUS, PROP_READ, TRAINING_STATUS_IDLE),
                CharDef(CHAR_FTMS_CONTROL_POINT, PROP_WRITE or PROP_INDICATE),
                CharDef(CHAR_TREADMILL_DATA, PROP_NOTIFY),
                CharDef(CHAR_FTMS_STATUS, PROP_NOTIFY)
            )),
            ServiceDef(SVC_HEART_RATE, listOf(
                CharDef(CHAR_HR_MEASUREMENT, PROP_NOTIFY)
            ))
        )
    }

    /** FTMS Control Point opcodes as a typed enum. */
    enum class FtmsCommand(val opcode: Int) {
        SetSpeed(0x02),
        SetIncline(0x03),
        Start(0x07),
        Stop(0x08);

        companion object {
            fun fromOpcode(value: Int): FtmsCommand? =
                values().find { it.opcode == value }
        }
    }

    /** Dircon protocol packet — shared between server and tests.
     *  Note: payload is ByteArray so auto-generated equals/hashCode compare by reference. */
    data class DirconPacket(
        val msgType: Byte,
        val seq: Int,
        val respCode: Byte,
        val payload: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DirconPacket) return false
            return msgType == other.msgType && seq == other.seq &&
                respCode == other.respCode && payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var result = msgType.hashCode()
            result = 31 * result + seq
            result = 31 * result + respCode.hashCode()
            result = 31 * result + payload.contentHashCode()
            return result
        }

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
    data class CharDef(val uuid: Int, val props: Int, val readValue: ByteArray = byteArrayOf())

    // Service definition
    data class ServiceDef(val uuid: Int, val chars: List<CharDef>)

    private val clients = CopyOnWriteArrayList<ClientState>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    class ClientState(
        val socket: Socket,
        val output: OutputStream,
        var seq: Int = 0,
        val notifyUuids: MutableList<Int> = CopyOnWriteArrayList(),
        val outputMutex: Mutex = Mutex()
    )

    fun start() {
        // Accept loop
        scope.launch {
            try {
                val ss = ServerSocket(port)
                Log.d(TAG, "Listening on TCP :$port")
                try {
                    while (isActive) {
                        val socket = ss.accept()
                        Log.d(TAG, "Client connected: ${socket.inetAddress}")
                        val client = ClientState(socket, socket.getOutputStream())
                        clients.add(client)
                        launch { handleClient(client) }
                    }
                } finally {
                    ss.close()
                }
            } catch (e: Exception) {
                if (isActive) Log.e(TAG, "Server error", e)
            }
        }

        // Notification push loop
        scope.launch {
            while (isActive) {
                try {
                    pushNotifications()
                } catch (e: Exception) {
                    Log.e(TAG, "Notify error", e)
                }
                delay(250)
            }
        }
    }

    fun stop() {
        scope.cancel()
        clients.forEach { it.socket.close() }
        clients.clear()
    }

    val clientCount get() = clients.size

    private suspend fun handleClient(client: ClientState) {
        try {
            val input = client.socket.getInputStream()
            val readBuf = ByteArray(4096)
            val buf = java.nio.ByteBuffer.allocate(4096)

            while (currentCoroutineContext().isActive && !client.socket.isClosed) {
                val n = input.read(readBuf)
                if (n <= 0) break
                buf.put(readBuf, 0, n)
                buf.flip()

                while (buf.remaining() >= 6) {
                    val payloadLen = ((buf.get(buf.position() + 4).toInt() and 0xFF) shl 8) or
                                    (buf.get(buf.position() + 5).toInt() and 0xFF)
                    val totalLen = 6 + payloadLen
                    if (buf.remaining() < totalLen) break

                    val packet = ByteArray(totalLen)
                    buf.get(packet)

                    val response = processPacket(client, packet)
                    if (response != null) {
                        client.outputMutex.withLock {
                            client.output.write(response.toBytes())
                            client.output.flush()
                        }
                    }
                }
                buf.compact()
            }
        } catch (e: Exception) {
            if (currentCoroutineContext().isActive) Log.d(TAG, "Client disconnected: ${e.message}")
        } finally {
            clients.remove(client)
            client.socket.close()
        }
    }

    private suspend fun processPacket(client: ClientState, pkt: ByteArray): DirconPacket? {
        val req = DirconPacket.parse(pkt)
        client.seq = req.seq

        return when (req.msgType) {
            MSG_DISCOVER_SERVICES -> handleDiscoverServices(req.seq)
            MSG_DISCOVER_CHARS -> handleDiscoverChars(req.seq, req.payload)
            MSG_READ_CHAR -> handleReadChar(req.seq, req.payload)
            MSG_WRITE_CHAR -> handleWriteChar(req.seq, req.payload)
            MSG_ENABLE_NOTIFY -> handleEnableNotify(client, req.seq, req.payload)
            MSG_UNKNOWN_07 -> DirconPacket(MSG_UNKNOWN_07, req.seq, RESP_SUCCESS, ByteArray(0))
            else -> {
                Log.w(TAG, "Unknown msg type: ${req.msgType}")
                null
            }
        }
    }

    private fun handleDiscoverServices(seq: Int): DirconPacket {
        val uuids = ByteArray(SERVICES.size * 16)
        SERVICES.forEachIndexed { i, svc -> uuidToBytes(svc.uuid).copyInto(uuids, i * 16) }
        return DirconPacket(MSG_DISCOVER_SERVICES, seq, RESP_SUCCESS, uuids)
    }

    private fun handleDiscoverChars(seq: Int, payload: ByteArray): DirconPacket {
        if (payload.size < 16) return DirconPacket(MSG_DISCOVER_CHARS, seq, RESP_SERVICE_NOT_FOUND, ByteArray(0))
        val svcUuid = uuidFromBytes(payload)
        val svc = SERVICES.find { it.uuid == svcUuid }
            ?: return DirconPacket(MSG_DISCOVER_CHARS, seq, RESP_SERVICE_NOT_FOUND, ByteArray(0))

        // Response: service UUID (16) + for each char: UUID (16) + props (1)
        val data = ByteArray(16 + svc.chars.size * 17)
        uuidToBytes(svcUuid).copyInto(data, 0)
        svc.chars.forEachIndexed { i, ch ->
            uuidToBytes(ch.uuid).copyInto(data, 16 + i * 17)
            data[16 + i * 17 + 16] = ch.props.toByte()
        }
        return DirconPacket(MSG_DISCOVER_CHARS, seq, RESP_SUCCESS, data)
    }

    private fun handleReadChar(seq: Int, payload: ByteArray): DirconPacket {
        if (payload.size < 16) return DirconPacket(MSG_READ_CHAR, seq, RESP_CHAR_NOT_FOUND, ByteArray(0))
        val charUuid = uuidFromBytes(payload)
        val charDef = SERVICES.flatMap { it.chars }.find { it.uuid == charUuid }
            ?: return DirconPacket(MSG_READ_CHAR, seq, RESP_CHAR_NOT_FOUND, ByteArray(0))

        val data = ByteArray(16 + charDef.readValue.size)
        uuidToBytes(charUuid).copyInto(data, 0)
        charDef.readValue.copyInto(data, 16)
        return DirconPacket(MSG_READ_CHAR, seq, RESP_SUCCESS, data)
    }

    private suspend fun handleWriteChar(seq: Int, payload: ByteArray): DirconPacket {
        if (payload.size < 16) return DirconPacket(MSG_WRITE_CHAR, seq, RESP_CHAR_NOT_FOUND, ByteArray(0))
        val charUuid = uuidFromBytes(payload)
        val writeData = if (payload.size > 16) payload.copyOfRange(16, payload.size) else ByteArray(0)

        Log.d(TAG, "Write 0x${charUuid.toString(16)}: ${writeData.joinToString(" ") { "%02X".format(it) }}")

        if (charUuid == CHAR_FTMS_CONTROL_POINT && writeData.isNotEmpty()) {
            val rawOpcode = writeData[0].toInt() and 0xFF
            val command = FtmsCommand.fromOpcode(rawOpcode)
            val params = if (writeData.size > 1) writeData.copyOfRange(1, writeData.size) else ByteArray(0)

            val ok = if (command != null) {
                onControlCommand(command, params)
            } else {
                Log.d(TAG, "Unknown FTMS opcode: 0x${rawOpcode.toString(16)}")
                false
            }

            // Send indication response: UUID + [0x80, opcode, result]
            val indication = ByteArray(16 + 3)
            uuidToBytes(charUuid).copyInto(indication, 0)
            indication[16] = 0x80.toByte()
            indication[17] = rawOpcode.toByte()
            indication[18] = if (ok) 0x01 else 0x02
            return DirconPacket(MSG_WRITE_CHAR, seq, RESP_SUCCESS, indication)
        }

        // Generic write response
        val respData = ByteArray(16)
        uuidToBytes(charUuid).copyInto(respData, 0)
        return DirconPacket(MSG_WRITE_CHAR, seq, RESP_SUCCESS, respData)
    }

    private fun handleEnableNotify(client: ClientState, seq: Int, payload: ByteArray): DirconPacket {
        if (payload.size < 16) return DirconPacket(MSG_ENABLE_NOTIFY, seq, RESP_CHAR_NOT_FOUND, ByteArray(0))
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
        return DirconPacket(MSG_ENABLE_NOTIFY, seq, RESP_SUCCESS, respData)
    }

    private suspend fun pushNotifications() {
        val snapshot = dataProvider()

        for (client in clients) {
            try {
                if (CHAR_TREADMILL_DATA in client.notifyUuids) {
                    val treadmillData = FtmsEncoder.encodeTreadmillData(
                        snapshot.speedKPH, snapshot.inclinePct, snapshot.heartRate,
                        snapshot.distanceM, snapshot.elapsedSec
                    )
                    val payload = ByteArray(16 + treadmillData.size)
                    uuidToBytes(CHAR_TREADMILL_DATA).copyInto(payload, 0)
                    treadmillData.copyInto(payload, 16)

                    val pkt = DirconPacket(MSG_UNSOLICITED_NOTIFY, 0, RESP_SUCCESS, payload).toBytes()
                    client.outputMutex.withLock {
                        client.output.write(pkt)
                        client.output.flush()
                    }
                }

                if (CHAR_HR_MEASUREMENT in client.notifyUuids && snapshot.heartRate > 0) {
                    val hrData = FtmsEncoder.encodeHRMeasurement(snapshot.heartRate)
                    val payload = ByteArray(16 + hrData.size)
                    uuidToBytes(CHAR_HR_MEASUREMENT).copyInto(payload, 0)
                    hrData.copyInto(payload, 16)

                    val pkt = DirconPacket(MSG_UNSOLICITED_NOTIFY, 0, RESP_SUCCESS, payload).toBytes()
                    client.outputMutex.withLock {
                        client.output.write(pkt)
                        client.output.flush()
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Notify failed for client: ${e.message}")
            }
        }
    }

}
