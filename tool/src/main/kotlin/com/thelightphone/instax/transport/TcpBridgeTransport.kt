package com.thelightphone.instax.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.PrintWriter
import java.net.Socket
import java.util.Base64

/**
 * Dev transport: relays whole protocol frames to scripts/instax/bridge.py over
 * one TCP connection (JSON lines, binary as base64). The bridge is a dumb pipe;
 * every protocol byte is composed on this side. Wire protocol:
 *
 *   -> {"cmd":"scan"} | {"cmd":"connect","address":..} | {"cmd":"send","data":b64} | {"cmd":"disconnect"}
 *   <- {"event":"device",name,address} | {"event":"connected"} | {"event":"notify","data":b64}
 *      | {"event":"disconnected"} | {"event":"error","message":..}
 *
 * 10.0.2.2 is the emulator's alias for the host machine.
 */
class TcpBridgeTransport(
    private val host: String = "10.0.2.2",
    private val port: Int = 47845,
) : InstaxTransport {

    @Serializable
    private data class BridgeMsg(
        val cmd: String? = null,
        val event: String? = null,
        val name: String? = null,
        val address: String? = null,
        val data: String? = null,
        val message: String? = null,
    )

    private sealed interface Event {
        data class Msg(val msg: BridgeMsg) : Event
        data class Closed(val cause: Throwable?) : Event
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val events = MutableSharedFlow<Event>(extraBufferCapacity = 256)
    private val socketLock = Mutex()
    private var socket: Socket? = null
    private var writer: PrintWriter? = null

    /** Sticky closed marker so collectors that subscribe AFTER the socket died
     *  still fail fast instead of hanging (SharedFlow has no replay). */
    @Volatile
    private var lastClose: Throwable? = null
    @Volatile
    private var everConnected = false

    private suspend fun ensureConnected(): PrintWriter = socketLock.withLock {
        writer?.let { return it }
        val s = try {
            withContext(Dispatchers.IO) { Socket(host, port) }
        } catch (e: Exception) {
            throw TransportException("Can't reach the print bridge at $host:$port", e)
        }
        socket = s
        lastClose = null
        everConnected = true
        val w = PrintWriter(s.getOutputStream(), true)
        writer = w
        scope.launch { readLoop(s) }
        w
    }

    private suspend fun readLoop(s: Socket) {
        val cause = try {
            val reader = BufferedReader(s.getInputStream().reader())
            while (true) {
                val line = reader.readLine() ?: break
                val msg = try {
                    json.decodeFromString<BridgeMsg>(line)
                } catch (e: Exception) {
                    continue // tolerate junk lines from the bridge
                }
                events.emit(Event.Msg(msg))
            }
            null
        } catch (e: Exception) {
            e
        }
        lastClose = cause ?: TransportException("bridge closed the connection")
        events.emit(Event.Closed(cause))
        socketLock.withLock {
            runCatching { s.close() }
            if (socket === s) {
                socket = null
                writer = null
            }
        }
    }

    private suspend fun writeLine(line: String) {
        val w = ensureConnected()
        withContext(Dispatchers.IO) {
            w.println(line)
            if (w.checkError()) throw TransportException("bridge connection lost while writing")
        }
    }

    override fun scan(): Flow<PrinterDevice> = flow {
        events.onSubscription { writeLine("""{"cmd":"scan"}""") }
            .collect { event ->
                when (event) {
                    is Event.Closed -> throw TransportException("bridge connection lost during scan", event.cause)
                    is Event.Msg -> when (event.msg.event) {
                        "device" -> emit(
                            PrinterDevice(event.msg.name ?: "INSTAX", event.msg.address ?: return@collect)
                        )
                        "error" -> throw TransportException("bridge error: ${event.msg.message}")
                        else -> Unit
                    }
                }
            }
    }

    override suspend fun connect(device: PrinterDevice) {
        val response = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            events
                .onSubscription {
                    writeLine("""{"cmd":"connect","address":"${device.address}"}""")
                }
                .filterIsInstance<Event.Msg>()
                .first { it.msg.event == "connected" || it.msg.event == "error" }
        } ?: throw TransportException("bridge connect timed out")
        if (response.msg.event == "error") {
            throw TransportException("bridge error: ${response.msg.message}")
        }
    }

    override suspend fun send(packet: ByteArray) {
        val b64 = Base64.getEncoder().encodeToString(packet)
        writeLine("""{"cmd":"send","data":"$b64"}""")
    }

    override val notifications: Flow<ByteArray> = flow {
        lastClose?.let { if (everConnected) throw TransportException("bridge connection lost", it) }
        events.collect { event ->
            when (event) {
                is Event.Closed -> throw TransportException("bridge connection lost", event.cause)
                is Event.Msg -> when (event.msg.event) {
                    "notify" -> event.msg.data?.let { emit(Base64.getDecoder().decode(it)) }
                    "disconnected" -> throw TransportException("printer disconnected")
                    "error" -> throw TransportException("bridge error: ${event.msg.message}")
                    else -> Unit
                }
            }
        }
    }

    override suspend fun close() {
        runCatching { writer?.println("""{"cmd":"disconnect"}""") }
        socketLock.withLock {
            runCatching { socket?.close() }
            socket = null
            writer = null
        }
        scope.cancel()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 20_000L
    }
}
