package com.thelightphone.instax.protocol

import com.thelightphone.instax.transport.PrinterDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InstaxSessionTest {

    private val device = PrinterDevice("INSTAX-TEST(IOS)", "00:11:22:33:44:55")

    /** Standard well-behaved wide printer: answers info queries, acks everything. */
    private fun happyScript(
        printsLeft: Int = 7,
        width: Int = 1260,
        height: Int = 840,
    ): suspend ScriptedTransport.(InstaxPacket.Decoded?) -> Unit = { sent ->
        when (sent?.opcode) {
            Opcode.SUPPORT_FUNCTION_INFO -> when (sent.payload[0].toInt()) {
                InfoType.IMAGE_SUPPORT.value -> emitResponse(
                    Opcode.SUPPORT_FUNCTION_INFO,
                    byteArrayOf(
                        0, 0,
                        (width ushr 8).toByte(), width.toByte(),
                        (height ushr 8).toByte(), height.toByte(),
                    ),
                )
                InfoType.BATTERY.value -> emitResponse(
                    Opcode.SUPPORT_FUNCTION_INFO, byteArrayOf(0, 1, 2, 82),
                )
                InfoType.PRINTER_FUNCTION.value -> emitResponse(
                    Opcode.SUPPORT_FUNCTION_INFO, byteArrayOf(0, 2, printsLeft.toByte()),
                )
                else -> error("unexpected info type")
            }
            Opcode.PRINT_IMAGE_DOWNLOAD_START,
            Opcode.PRINT_IMAGE_DOWNLOAD_DATA,
            Opcode.PRINT_IMAGE_DOWNLOAD_END,
            Opcode.PRINT_IMAGE,
            -> ack(sent.opcode)
            else -> Unit
        }
    }

    private val jpeg = ByteArray(1800) { (it % 251).toByte() }

    @Test
    fun `connect queries info and exposes PrinterInfo`() = runTest {
        val transport = ScriptedTransport(happyScript())
        val session = InstaxSession(transport)
        val info = session.connect(device)
        assertTrue(transport.connected)
        assertEquals(1260, info.imageWidth)
        assertEquals(840, info.imageHeight)
        assertEquals(82, info.batteryPercent)
        assertEquals(7, info.printsRemaining)
        assertEquals(900, info.chunkSize)
    }

    @Test
    fun `print streams start, data chunks with indices, end, print image`() = runTest {
        val transport = ScriptedTransport(happyScript())
        val session = InstaxSession(transport)
        session.connect(device)
        session.print(jpeg).toList()

        val flowOps = transport.sentOpcodes.filter { it != Opcode.SUPPORT_FUNCTION_INFO }
        assertEquals(
            listOf(
                Opcode.PRINT_IMAGE_DOWNLOAD_START,
                Opcode.PRINT_IMAGE_DOWNLOAD_DATA,
                Opcode.PRINT_IMAGE_DOWNLOAD_DATA,
                Opcode.PRINT_IMAGE_DOWNLOAD_END,
                Opcode.PRINT_IMAGE,
            ),
            flowOps,
        )
        // byte-exact vs fixtures: this is the same 1800-byte payload
        val sentByOp = transport.sentPackets.filter {
            ScriptedTransport.decodeRequest(it)?.opcode != Opcode.SUPPORT_FUNCTION_INFO
        }
        assertTrue(Fixtures.bytes("print_start_1800_bytes").contentEquals(sentByOp[0]))
        assertTrue(Fixtures.bytes("print_data_chunk0").contentEquals(sentByOp[1]))
        assertTrue(Fixtures.bytes("print_data_chunk1").contentEquals(sentByOp[2]))
        assertTrue(Fixtures.bytes("print_end").contentEquals(sentByOp[3]))
        assertTrue(Fixtures.bytes("print_image").contentEquals(sentByOp[4]))
    }

    @Test
    fun `print emits transferring progress then printing then done`() = runTest {
        val transport = ScriptedTransport(happyScript())
        val session = InstaxSession(transport)
        session.connect(device)
        val progress = session.print(jpeg).toList()
        assertEquals(
            listOf(
                PrintProgress.Transferring(1, 2),
                PrintProgress.Transferring(2, 2),
                PrintProgress.Printing,
                PrintProgress.Done,
            ),
            progress,
        )
    }

    @Test
    fun `chunk size comes from device info, not a constant`() = runTest {
        // square printer geometry -> 1808-byte chunks -> 1800 bytes fits one chunk
        val transport = ScriptedTransport(happyScript(width = 800, height = 800))
        val session = InstaxSession(transport)
        val info = session.connect(device)
        assertEquals(1808, info.chunkSize)
        session.print(jpeg).toList()
        assertEquals(1, transport.sentOpcodes.count { it == Opcode.PRINT_IMAGE_DOWNLOAD_DATA })
        val dataPacket = transport.sentPackets.first {
            ScriptedTransport.decodeRequest(it)?.opcode == Opcode.PRINT_IMAGE_DOWNLOAD_DATA
        }
        assertEquals(7 + 4 + 1808, dataPacket.size)
    }

    @Test
    fun `cancelling before print image sends cancel and never sends print image`() = runTest {
        // Withhold the ack for chunk 1 so the print suspends mid-transfer.
        val transport = ScriptedTransport { sent ->
            when (sent?.opcode) {
                Opcode.SUPPORT_FUNCTION_INFO -> happyScript()(this, sent)
                Opcode.PRINT_IMAGE_DOWNLOAD_START -> ack(sent.opcode)
                Opcode.PRINT_IMAGE_DOWNLOAD_DATA -> {
                    val index = sent.payload[3].toInt()
                    if (index == 0) ack(sent.opcode) // withhold ack for chunk 1
                }
                else -> Unit
            }
        }
        val session = InstaxSession(transport)
        session.connect(device)
        val job = launch { session.print(jpeg).toList() }
        yield() // let the print reach the withheld ack
        while (transport.sentOpcodes.count { it == Opcode.PRINT_IMAGE_DOWNLOAD_DATA } < 2) yield()
        job.cancelAndJoin()

        assertTrue(Opcode.PRINT_IMAGE_DOWNLOAD_CANCEL in transport.sentOpcodes)
        assertFalse(Opcode.PRINT_IMAGE in transport.sentOpcodes)
    }

    @Test
    fun `disconnect during data phase fails with RetryableTransferError`() = runTest {
        val transport = ScriptedTransport { sent ->
            when (sent?.opcode) {
                Opcode.SUPPORT_FUNCTION_INFO -> happyScript()(this, sent)
                Opcode.PRINT_IMAGE_DOWNLOAD_START -> ack(sent.opcode)
                Opcode.PRINT_IMAGE_DOWNLOAD_DATA -> dropConnection()
                else -> Unit
            }
        }
        val session = InstaxSession(transport)
        session.connect(device)
        assertFailsWith<RetryableTransferError> { session.print(jpeg).toList() }
        assertFalse(Opcode.PRINT_IMAGE in transport.sentOpcodes)
    }

    @Test
    fun `disconnect after print image fails with PrintTriggeredButUnconfirmed`() = runTest {
        val transport = ScriptedTransport { sent ->
            when (sent?.opcode) {
                Opcode.SUPPORT_FUNCTION_INFO -> happyScript()(this, sent)
                Opcode.PRINT_IMAGE -> dropConnection() // PRINT_IMAGE went out; ack never comes
                else -> sent?.let { ack(it.opcode) }
            }
        }
        val session = InstaxSession(transport)
        session.connect(device)
        assertFailsWith<PrintTriggeredButUnconfirmed> { session.print(jpeg).toList() }
        assertTrue(Opcode.PRINT_IMAGE in transport.sentOpcodes)
    }

    @Test
    fun `ack timeout produces RetryableTransferError`() = runTest {
        val transport = ScriptedTransport { sent ->
            when (sent?.opcode) {
                Opcode.SUPPORT_FUNCTION_INFO -> happyScript()(this, sent)
                Opcode.PRINT_IMAGE_DOWNLOAD_START -> Unit // never ack
                else -> Unit
            }
        }
        val session = InstaxSession(transport)
        session.connect(device)
        assertFailsWith<RetryableTransferError> { session.print(jpeg).toList() }
    }

    @Test
    fun `no film aborts before any transfer with typed error`() = runTest {
        val transport = ScriptedTransport(happyScript(printsLeft = 0))
        val session = InstaxSession(transport)
        session.connect(device)
        val failure = assertFailsWith<PrinterReportedError> { session.print(jpeg).toList() }
        assertEquals(InstaxError.NO_FILM, failure.error)
        assertFalse(Opcode.PRINT_IMAGE_DOWNLOAD_START in transport.sentOpcodes)
    }

    @Test
    fun `second print while one is in flight fails immediately`() = runTest {
        val transport = ScriptedTransport { sent ->
            when (sent?.opcode) {
                Opcode.SUPPORT_FUNCTION_INFO -> happyScript()(this, sent)
                Opcode.PRINT_IMAGE_DOWNLOAD_START -> ack(sent.opcode)
                Opcode.PRINT_IMAGE_DOWNLOAD_DATA -> Unit // stall the first print
                else -> Unit
            }
        }
        val session = InstaxSession(transport)
        session.connect(device)
        val first = session.print(jpeg).onEach { }.launchIn(this)
        while (Opcode.PRINT_IMAGE_DOWNLOAD_DATA !in transport.sentOpcodes) yield()
        assertFailsWith<IllegalStateException> { session.print(jpeg).toList() }
        first.cancelAndJoin()
    }

    @Test
    fun `refreshStatus returns current printer function info`() = runTest {
        val transport = ScriptedTransport(happyScript(printsLeft = 3))
        val session = InstaxSession(transport)
        session.connect(device)
        assertEquals(3, session.refreshStatus().printsRemaining)
    }

    @Test
    fun `print before connect fails immediately`() = runTest {
        val session = InstaxSession(ScriptedTransport(happyScript()))
        val e = assertFailsWith<IllegalStateException> { session.print(jpeg).toList() }
        assertIs<IllegalStateException>(e)
    }
}
