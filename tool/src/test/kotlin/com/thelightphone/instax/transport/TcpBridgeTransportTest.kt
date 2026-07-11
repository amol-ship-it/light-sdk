package com.thelightphone.instax.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** In-process stand-in for bridge.py: accepts one client, scripts responses. */
private class FakeBridgeServer(
    private val onCommand: (cmd: String, json: Map<String, String?>, out: PrintWriter) -> Unit,
) {
    private val server = ServerSocket(0)
    val port: Int get() = server.localPort
    val received = mutableListOf<String>()
    @Volatile private var clientOut: PrintWriter? = null
    @Volatile private var clientSocket: java.net.Socket? = null

    private val thread = Thread {
        try {
            val client = server.accept()
            clientSocket = client
            val reader = BufferedReader(client.getInputStream().reader())
            val out = PrintWriter(client.getOutputStream(), true)
            clientOut = out
            while (true) {
                val line = reader.readLine() ?: break
                received += line
                val obj = Json.parseToJsonElement(line).jsonObject
                val map = obj.mapValues { it.value.jsonPrimitive.content }
                onCommand(map["cmd"] ?: "", map, out)
            }
        } catch (_: Exception) {
        }
    }.apply { isDaemon = true; start() }

    fun push(line: String) {
        clientOut?.println(line)
    }

    fun closeAll() {
        runCatching { clientSocket?.close() }
        runCatching { server.close() }
    }
}

class TcpBridgeTransportTest {

    private var server: FakeBridgeServer? = null
    private var transport: TcpBridgeTransport? = null

    @AfterTest
    fun tearDown() = runBlocking {
        transport?.close()
        server?.closeAll()
        Unit
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun standardServer() = FakeBridgeServer { cmd, json, out ->
        when (cmd) {
            "scan" -> out.println("""{"event":"device","name":"INSTAX-FAKE(IOS)","address":"FA:KE:00:00:00:01"}""")
            "connect" -> out.println(
                if (json["address"] == "FA:KE:00:00:00:01") """{"event":"connected"}"""
                else """{"event":"error","message":"unknown address"}"""
            )
            "send" -> out.println("""{"event":"notify","data":"${json["data"]}"}""") // echo
        }
    }

    private fun start(s: FakeBridgeServer = standardServer()): TcpBridgeTransport {
        server = s
        return TcpBridgeTransport(host = "127.0.0.1", port = s.port).also { transport = it }
    }

    @Test
    fun `scan emits discovered devices`() = runBlocking {
        val t = start()
        val device = withTimeout(5000) { t.scan().first() }
        assertEquals(PrinterDevice("INSTAX-FAKE(IOS)", "FA:KE:00:00:00:01"), device)
    }

    @Test
    fun `connect handshake succeeds against fake address`() = runBlocking {
        val t = start()
        withTimeout(5000) { t.connect(PrinterDevice("INSTAX-FAKE(IOS)", "FA:KE:00:00:00:01")) }
        val sent = server!!.received.map { Json.parseToJsonElement(it).jsonObject }
        assertTrue(sent.any { it["cmd"]?.jsonPrimitive?.content == "connect" &&
            it["address"]?.jsonPrimitive?.content == "FA:KE:00:00:00:01" })
    }

    @Test
    fun `connect to unknown address fails with TransportException`() = runBlocking {
        val t = start()
        assertFailsWith<TransportException> {
            withTimeout(5000) { t.connect(PrinterDevice("X", "AA:BB:CC:DD:EE:FF")) }
        }
        Unit
    }

    @Test
    fun `send writes base64 and notify events surface as frames`() = runBlocking {
        val t = start()
        t.connect(PrinterDevice("INSTAX-FAKE(IOS)", "FA:KE:00:00:00:01"))
        val packet = byteArrayOf(0x61, 0x42, 0, 8, 16, 0, 0, 0x29)
        val frame = withTimeout(5000) {
            // UNDISPATCHED: subscribe to notifications BEFORE the send triggers the echo
            val deferred = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                t.notifications.first()
            }
            t.send(packet)
            deferred.await()
        }
        assertContentEquals(packet, frame) // server echoes our b64 back as a notify
        val sendLine = server!!.received.map { Json.parseToJsonElement(it).jsonObject }
            .first { it["cmd"]?.jsonPrimitive?.content == "send" }
        assertEquals(b64(packet), sendLine["data"]?.jsonPrimitive?.content)
    }

    @Test
    fun `server closing the socket fails notifications with TransportException`() = runBlocking {
        val t = start()
        t.connect(PrinterDevice("INSTAX-FAKE(IOS)", "FA:KE:00:00:00:01"))
        val collecting = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            runCatching { t.notifications.toList() }
        }
        server!!.closeAll()
        val result = withTimeout(5000) { collecting.await() }
        assertTrue(result.exceptionOrNull() is TransportException)

        // sticky closed state: a LATE subscriber must fail fast, not hang
        val late = withTimeout(5000) { runCatching { t.notifications.toList() } }
        assertTrue(late.exceptionOrNull() is TransportException)
    }

    @Test
    fun `bridge error event fails in-flight operations`() = runBlocking {
        val t = start()
        t.connect(PrinterDevice("INSTAX-FAKE(IOS)", "FA:KE:00:00:00:01"))
        val collecting = launch { runCatching { t.notifications.toList() } }
        server!!.push("""{"event":"error","message":"BLE failure"}""")
        // after an error the bridge closes the socket; sends must fail
        server!!.closeAll()
        withTimeout(5000) {
            assertFailsWith<TransportException> {
                repeat(50) { t.send(byteArrayOf(1)); kotlinx.coroutines.delay(50) }
            }
        }
        collecting.cancel()
        Unit
    }

    @Test
    fun `bridge unreachable surfaces as TransportException`() = runBlocking {
        val dead = ServerSocket(0).also { it.close() } // grab a free, closed port
        val t = TcpBridgeTransport(host = "127.0.0.1", port = dead.localPort).also { transport = it }
        assertFailsWith<TransportException> { t.scan().first() }
        Unit
    }
}
