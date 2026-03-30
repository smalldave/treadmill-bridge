package com.treadmill.bridge

import com.treadmill.bridge.DirconServer.Companion.MSG_DISCOVER_CHARS
import com.treadmill.bridge.DirconServer.Companion.MSG_DISCOVER_SERVICES
import com.treadmill.bridge.DirconServer.Companion.MSG_ENABLE_NOTIFY
import com.treadmill.bridge.DirconServer.Companion.MSG_READ_CHAR
import com.treadmill.bridge.DirconServer.Companion.MSG_UNSOLICITED_NOTIFY
import com.treadmill.bridge.DirconServer.Companion.MSG_WRITE_CHAR
import com.treadmill.bridge.DirconServer.Companion.RESP_CHAR_NOT_FOUND
import com.treadmill.bridge.DirconServer.Companion.RESP_SERVICE_NOT_FOUND
import com.treadmill.bridge.DirconServer.Companion.RESP_SUCCESS
import com.treadmill.bridge.DirconServer.Companion.uuidFromBytes
import com.treadmill.bridge.DirconServer.Companion.uuidToBytes
import com.treadmill.bridge.DirconServer.DirconPacket
import java.io.InputStream
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DirconServerTest {

    // --- Helpers ---

    private fun request(msgType: Byte, seq: Int, payload: ByteArray = ByteArray(0)) =
        DirconPacket(msgType, seq, 0x00, payload).toBytes()

    /** Read raw bytes for one Dircon frame from a socket, then parse. */
    private fun readPacket(input: InputStream): DirconPacket {
        val header = ByteArray(6)
        var read = 0
        while (read < 6) {
            val n = input.read(header, read, 6 - read)
            if (n <= 0) throw RuntimeException("Connection closed reading header")
            read += n
        }
        val payloadLen = ((header[4].toInt() and 0xFF) shl 8) or (header[5].toInt() and 0xFF)
        if (payloadLen > 0) {
            val buf = ByteArray(payloadLen); var pr = 0
            while (pr < payloadLen) {
                val n = input.read(buf, pr, payloadLen - pr)
                if (n <= 0) throw RuntimeException("Connection closed reading payload")
                pr += n
            }
            return DirconPacket.parse(header + buf)
        }
        return DirconPacket.parse(header)
    }

    private fun startServer(
        snapshot: DirconServer.TreadmillSnapshot = DirconServer.TreadmillSnapshot(),
        onControlCommand: suspend (DirconServer.FtmsCommand, ByteArray) -> Boolean = { _, _ -> false }
    ): Pair<DirconServer, Int> {
        // Use port 0 to let the OS assign an ephemeral port, avoiding collisions
        val ss = java.net.ServerSocket(0)
        val port = ss.localPort
        ss.close()
        val server = DirconServer(port, { snapshot }, onControlCommand)
        server.start()
        Thread.sleep(200)
        return server to port
    }

    private fun connectClient(port: Int): Socket {
        val socket = Socket("127.0.0.1", port)
        socket.soTimeout = 3000
        return socket
    }

    // ========== Service Discovery ==========

    @Test fun `discover services returns FTMS and Heart Rate`() {
        val (server, port) = startServer()
        try {
            val client = connectClient(port)
            client.getOutputStream().apply {
                write(request(MSG_DISCOVER_SERVICES, 1)); flush()
            }

            val resp = readPacket(client.getInputStream())
            assertEquals(MSG_DISCOVER_SERVICES, resp.msgType)
            assertEquals(1, resp.seq)
            assertEquals(RESP_SUCCESS, resp.respCode)
            assertEquals(32, resp.payload.size)
            assertEquals(0x1826, uuidFromBytes(resp.payload, 0))
            assertEquals(0x180D, uuidFromBytes(resp.payload, 16))

            client.close()
        } finally {
            server.stop()
        }
    }

    // ========== Characteristic Discovery ==========

    @Test fun `discover chars for FTMS returns 6 characteristics`() {
        val (server, port) = startServer()
        try {
            val client = connectClient(port)
            client.getOutputStream().apply {
                write(request(MSG_DISCOVER_CHARS, 2, uuidToBytes(0x1826))); flush()
            }

            val resp = readPacket(client.getInputStream())
            assertEquals(RESP_SUCCESS, resp.respCode)
            assertEquals(118, resp.payload.size)
            assertEquals(0x1826, uuidFromBytes(resp.payload, 0))
            assertEquals(0x2ACC, uuidFromBytes(resp.payload, 16))
            assertEquals(0x01, resp.payload[32].toInt() and 0xFF)

            client.close()
        } finally {
            server.stop()
        }
    }

    @Test fun `discover chars for unknown service returns SERVICE_NOT_FOUND`() {
        val (server, port) = startServer()
        try {
            val client = connectClient(port)
            client.getOutputStream().apply {
                write(request(MSG_DISCOVER_CHARS, 3, uuidToBytes(0xFFFF))); flush()
            }

            val resp = readPacket(client.getInputStream())
            assertEquals(RESP_SERVICE_NOT_FOUND, resp.respCode)

            client.close()
        } finally {
            server.stop()
        }
    }

    // ========== Read Characteristic ==========

    @Test fun `read FTMS Feature char returns expected bytes`() {
        val (server, port) = startServer()
        try {
            val client = connectClient(port)
            client.getOutputStream().apply {
                write(request(MSG_READ_CHAR, 4, uuidToBytes(0x2ACC))); flush()
            }

            val resp = readPacket(client.getInputStream())
            assertEquals(RESP_SUCCESS, resp.respCode)
            assertEquals(24, resp.payload.size)
            assertEquals(0x2ACC, uuidFromBytes(resp.payload, 0))
            assertEquals(0x08, resp.payload[16].toInt() and 0xFF)

            client.close()
        } finally {
            server.stop()
        }
    }

    @Test fun `read unknown char returns CHAR_NOT_FOUND`() {
        val (server, port) = startServer()
        try {
            val client = connectClient(port)
            client.getOutputStream().apply {
                write(request(MSG_READ_CHAR, 5, uuidToBytes(0xBEEF))); flush()
            }

            val resp = readPacket(client.getInputStream())
            assertEquals(RESP_CHAR_NOT_FOUND, resp.respCode)

            client.close()
        } finally {
            server.stop()
        }
    }

    // ========== Write / FTMS Control Point ==========

    @Test fun `write FTMS control point fires callback with opcode and params`() {
        var receivedCommand: DirconServer.FtmsCommand? = null
        var receivedParams = ByteArray(0)
        val (server, port) = startServer(onControlCommand = { command, params ->
            receivedCommand = command
            receivedParams = params
            true
        })
        try {

            val client = connectClient(port)
            val writePayload = uuidToBytes(0x2AD9) + byteArrayOf(0x02, 0x52, 0x03)
            client.getOutputStream().apply {
                write(request(MSG_WRITE_CHAR, 6, writePayload)); flush()
            }

            val resp = readPacket(client.getInputStream())
            assertEquals(RESP_SUCCESS, resp.respCode)
            assertEquals(DirconServer.FtmsCommand.SetSpeed, receivedCommand)
            assertEquals(2, receivedParams.size)
            assertEquals(0x52, receivedParams[0].toInt() and 0xFF)
            assertEquals(0x03, receivedParams[1].toInt() and 0xFF)

            assertEquals(0x2AD9, uuidFromBytes(resp.payload, 0))
            assertEquals(0x80.toByte(), resp.payload[16])
            assertEquals(0x02.toByte(), resp.payload[17])
            assertEquals(0x01.toByte(), resp.payload[18]) // success

            client.close()
        } finally {
            server.stop()
        }
    }

    @Test fun `write control point returns failure when callback returns false`() {
        val (server, port) = startServer(onControlCommand = { _, _ -> false })
        try {
            val client = connectClient(port)
            val writePayload = uuidToBytes(0x2AD9) + byteArrayOf(0x08)
            client.getOutputStream().apply {
                write(request(MSG_WRITE_CHAR, 7, writePayload)); flush()
            }

            val resp = readPacket(client.getInputStream())
            assertEquals(0x02.toByte(), resp.payload[18]) // failure

            client.close()
        } finally {
            server.stop()
        }
    }

    // ========== Notifications ==========

    @Test fun `notifications push FTMS data after enable`() {
        val snapshot = DirconServer.TreadmillSnapshot(
            speedKPH = 10.0, inclinePct = -1.5, heartRate = 0, distanceM = 500, elapsedSec = 120
        )
        val (server, port) = startServer(snapshot)
        try {
            val client = connectClient(port)
            val input = client.getInputStream()
            client.getOutputStream().apply {
                write(request(MSG_ENABLE_NOTIFY, 10, uuidToBytes(0x2ACD) + byteArrayOf(0x01))); flush()
            }

            val enableResp = readPacket(input)
            assertEquals(RESP_SUCCESS, enableResp.respCode)

            val notify = readPacket(input)
            assertEquals(MSG_UNSOLICITED_NOTIFY, notify.msgType)
            assertEquals(RESP_SUCCESS, notify.respCode)
            assertEquals(32, notify.payload.size)
            assertEquals(0x2ACD, uuidFromBytes(notify.payload, 0))

            // Verify FTMS encoding within the notification
            val ftms = notify.payload.copyOfRange(16, 32)
            val speed = (ftms[2].toInt() and 0xFF) or ((ftms[3].toInt() and 0xFF) shl 8)
            assertEquals(1000, speed)
            val incline = ((ftms[9].toInt() and 0xFF) or ((ftms[10].toInt() and 0xFF) shl 8)).toShort().toInt()
            assertEquals(-15, incline)

            client.close()
        } finally {
            server.stop()
        }
    }

    @Test fun `HR notification sent when heartRate is nonzero`() {
        val snapshot = DirconServer.TreadmillSnapshot(
            speedKPH = 5.0, inclinePct = 0.0, heartRate = 145, distanceM = 0, elapsedSec = 0
        )
        val (server, port) = startServer(snapshot)
        try {
            val client = connectClient(port)
            val input = client.getInputStream()
            client.getOutputStream().apply {
                write(request(MSG_ENABLE_NOTIFY, 20, uuidToBytes(0x2A37) + byteArrayOf(0x01))); flush()
            }
            readPacket(input) // enable response

            val notify = readPacket(input)
            assertEquals(MSG_UNSOLICITED_NOTIFY, notify.msgType)
            assertEquals(0x2A37, uuidFromBytes(notify.payload, 0))
            assertEquals(0x00, notify.payload[16].toInt() and 0xFF)
            assertEquals(145, notify.payload[17].toInt() and 0xFF)

            client.close()
        } finally {
            server.stop()
        }
    }

    @Test fun `HR notification skipped when heartRate is zero`() {
        val snapshot = DirconServer.TreadmillSnapshot(
            speedKPH = 5.0, inclinePct = 0.0, heartRate = 0, distanceM = 0, elapsedSec = 0
        )
        val (server, port) = startServer(snapshot)
        try {
            val client = connectClient(port)
            val input = client.getInputStream()
            val output = client.getOutputStream()

            output.apply {
                write(request(MSG_ENABLE_NOTIFY, 30, uuidToBytes(0x2ACD) + byteArrayOf(0x01))); flush()
            }
            readPacket(input)
            output.apply {
                write(request(MSG_ENABLE_NOTIFY, 31, uuidToBytes(0x2A37) + byteArrayOf(0x01))); flush()
            }
            readPacket(input)

            val notify1 = readPacket(input)
            assertEquals(MSG_UNSOLICITED_NOTIFY, notify1.msgType)
            assertEquals(0x2ACD, uuidFromBytes(notify1.payload, 0))

            val notify2 = readPacket(input)
            assertEquals(0x2ACD, uuidFromBytes(notify2.payload, 0))

            client.close()
        } finally {
            server.stop()
        }
    }

    @Test fun `disable notifications stops delivery`() {
        val snapshot = DirconServer.TreadmillSnapshot(speedKPH = 5.0)
        val (server, port) = startServer(snapshot)
        try {
            val client = connectClient(port)
            client.soTimeout = 1000
            val input = client.getInputStream()
            val output = client.getOutputStream()

            output.apply {
                write(request(MSG_ENABLE_NOTIFY, 40, uuidToBytes(0x2ACD) + byteArrayOf(0x01))); flush()
            }
            readPacket(input)

            val notify = readPacket(input)
            assertEquals(MSG_UNSOLICITED_NOTIFY, notify.msgType)

            output.apply {
                write(request(MSG_ENABLE_NOTIFY, 41, uuidToBytes(0x2ACD) + byteArrayOf(0x00))); flush()
            }
            readPacket(input) // disable response

            try {
                readPacket(input)
                assertTrue(false, "Should not receive notifications after disable")
            } catch (_: Exception) {
                // Expected — socket timeout
            }

            client.close()
        } finally {
            server.stop()
        }
    }
}
