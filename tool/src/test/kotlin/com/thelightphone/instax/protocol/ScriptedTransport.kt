package com.thelightphone.instax.protocol

import com.thelightphone.instax.transport.InstaxTransport
import com.thelightphone.instax.transport.PrinterDevice
import com.thelightphone.instax.transport.TransportException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Test transport driven by a script: for each sent packet, the script decides
 * which notification frames come back (or whether the connection "drops").
 * Records every sent packet for assertions.
 */
class ScriptedTransport(
    private val script: suspend ScriptedTransport.(sent: InstaxPacket.Decoded?) -> Unit,
) : InstaxTransport {

    val sentPackets = mutableListOf<ByteArray>()
    val sentOpcodes: List<Opcode>
        get() = sentPackets.map { requireNotNull(decodeRequest(it)).opcode }

    var connected = false
        private set
    var closed = false
        private set

    private val notify = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private var dropped = false

    private val failMarker = ByteArray(0)

    override fun scan(): Flow<PrinterDevice> = flowOf(PrinterDevice("INSTAX-TEST(IOS)", "00:11:22:33:44:55"))

    override suspend fun connect(device: PrinterDevice) {
        connected = true
    }

    override suspend fun send(packet: ByteArray) {
        if (dropped) throw TransportException("connection dropped")
        sentPackets += packet
        script(decodeRequest(packet))
    }

    override val notifications: Flow<ByteArray> = kotlinx.coroutines.flow.flow {
        notify.collect {
            if (it === failMarker) throw TransportException("notifications stream failed")
            emit(it)
        }
    }

    override suspend fun close() {
        closed = true
    }

    // --- script helpers ---

    /** Emit a printer->client response mirroring [opcode] with a 0x00 status payload. */
    suspend fun ack(opcode: Opcode, payload: ByteArray = byteArrayOf(0)) {
        emitResponse(opcode, payload)
    }

    suspend fun emitResponse(opcode: Opcode, payload: ByteArray) {
        notify.emit(responsePacket(opcode, payload))
    }

    /** Simulate the connection dropping: subsequent sends throw. */
    fun dropConnection() {
        dropped = true
    }

    /** Simulate the notifications stream dying (e.g. the bridge socket closed). */
    suspend fun failNotifications() {
        notify.emit(failMarker)
    }

    companion object {
        /** Decode a CLIENT->printer packet (request direction) for assertions. */
        fun decodeRequest(bytes: ByteArray): InstaxPacket.Decoded? {
            if (bytes.size < 7) return null
            if (bytes[0].toInt() != 0x41 || bytes[1].toInt() != 0x62) return null
            val opcode = Opcode.from(bytes[4].toInt() and 0xFF, bytes[5].toInt() and 0xFF) ?: return null
            return InstaxPacket.Decoded(opcode, bytes.copyOfRange(6, bytes.size - 1))
        }

        /** Build a printer->client packet the way the fixtures do. */
        fun responsePacket(opcode: Opcode, payload: ByteArray): ByteArray {
            val total = 7 + payload.size
            val out = ByteArray(total)
            out[0] = 0x61
            out[1] = 0x42
            out[2] = (total ushr 8).toByte()
            out[3] = total.toByte()
            out[4] = opcode.op1.toByte()
            out[5] = opcode.op2.toByte()
            payload.copyInto(out, 6)
            out[total - 1] = InstaxPacket.checksum(out, total - 1)
            return out
        }
    }
}
