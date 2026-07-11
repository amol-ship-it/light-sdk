package com.thelightphone.instax.protocol

import com.thelightphone.instax.transport.InstaxTransport
import com.thelightphone.instax.transport.PrinterDevice
import com.thelightphone.instax.transport.TransportException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

sealed class PrintProgress {
    data class Transferring(val chunksSent: Int, val chunksTotal: Int) : PrintProgress()
    data object Printing : PrintProgress()
    data object Done : PrintProgress()
}

/** Transfer failed before PRINT_IMAGE — nothing printed, safe to retry. */
class RetryableTransferError(message: String, cause: Throwable? = null) : Exception(message, cause)

/** PRINT_IMAGE was sent but never confirmed — the sheet MAY be printing.
 *  UI must require re-confirmation before a retry (spec: special error case). */
class PrintTriggeredButUnconfirmed(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The printer itself reported a problem (no film, cover open, …). */
class PrinterReportedError(val error: InstaxError) : Exception("printer reported $error")

class PrinterInfo(
    val imageWidth: Int,
    val imageHeight: Int,
    val batteryPercent: Int,
    val printsRemaining: Int,
    val charging: Boolean,
    val chunkSize: Int,
)

/**
 * The print state machine over an [InstaxTransport]. Protocol per the fixture
 * ground truth: every request is acked by a response echoing its opcode; the
 * next packet goes out only after the ack. Nothing irreversible happens before
 * PRINT_IMAGE.
 */
class InstaxSession(
    private val transport: InstaxTransport,
    private val ackTimeout: Duration = 10.seconds,
) {
    private var info: PrinterInfo? = null
    private val printing = AtomicBoolean(false)

    suspend fun connect(device: PrinterDevice): PrinterInfo {
        transport.connect(device)
        return withNotifications { frames ->
            val support = requestInfo(frames, InfoType.IMAGE_SUPPORT, InstaxMessages::parseImageSupport)
            val battery = requestInfo(frames, InfoType.BATTERY, InstaxMessages::parseBattery)
            val status = requestInfo(frames, InfoType.PRINTER_FUNCTION, InstaxMessages::parsePrinterFunction)
            PrinterInfo(
                imageWidth = support.width,
                imageHeight = support.height,
                batteryPercent = battery.percent,
                printsRemaining = status.printsRemaining,
                charging = status.charging,
                chunkSize = chunkSizeFor(support),
            ).also { info = it }
        }
    }

    suspend fun refreshStatus(): PrinterStatus = withNotifications { frames ->
        requestInfo(frames, InfoType.PRINTER_FUNCTION, InstaxMessages::parsePrinterFunction)
    }

    /**
     * Cold flow: START -> DATA xN -> END -> PRINT_IMAGE, emitting progress.
     * Cancellation before PRINT_IMAGE sends PRINT_IMAGE_DOWNLOAD_CANCEL and
     * propagates as normal coroutine cancellation.
     */
    fun print(jpeg: ByteArray): Flow<PrintProgress> = flow {
        val printerInfo = checkNotNull(info) { "connect() before print()" }
        check(printing.compareAndSet(false, true)) { "a print is already in flight" }
        try {
            withNotifications { frames -> runPrint(frames, printerInfo, jpeg) }
        } finally {
            printing.set(false)
        }
    }

    private suspend fun FlowCollector<PrintProgress>.runPrint(
        frames: Channel<InstaxPacket.Decoded>,
        printerInfo: PrinterInfo,
        jpeg: ByteArray,
    ) {
        // Fresh film check — the reference refuses to print with 0 photos left.
        val status = requestInfo(frames, InfoType.PRINTER_FUNCTION, InstaxMessages::parsePrinterFunction)
        if (status.printsRemaining <= 0) throw PrinterReportedError(InstaxError.NO_FILM)

        val chunkSize = printerInfo.chunkSize
        val chunksTotal = (jpeg.size + chunkSize - 1) / chunkSize
        var printImageSent = false
        var transferStarted = false

        try {
            transferStarted = true
            sendAwaitingAck(frames, InstaxMessages.printStart(jpeg.size), Opcode.PRINT_IMAGE_DOWNLOAD_START)
            for (index in 0 until chunksTotal) {
                val chunk = jpeg.copyOfRange(index * chunkSize, minOf((index + 1) * chunkSize, jpeg.size))
                sendAwaitingAck(
                    frames,
                    InstaxMessages.printData(index, chunk, chunkSize),
                    Opcode.PRINT_IMAGE_DOWNLOAD_DATA,
                )
                emit(PrintProgress.Transferring(index + 1, chunksTotal))
            }
            sendAwaitingAck(frames, InstaxMessages.printEnd(), Opcode.PRINT_IMAGE_DOWNLOAD_END)

            printImageSent = true
            transport.send(InstaxMessages.printImage())
            emit(PrintProgress.Printing)
            awaitAck(frames, Opcode.PRINT_IMAGE)
            emit(PrintProgress.Done)
        } catch (e: TimeoutCancellationException) {
            throw failureFor(printImageSent, e)
        } catch (e: TransportException) {
            throw failureFor(printImageSent, e)
        } catch (e: Throwable) {
            // Cancellation of the collector: abort the download so the printer
            // doesn't sit half-fed. Only before PRINT_IMAGE — after it, the
            // print is already triggered and cancel is meaningless.
            if (transferStarted && !printImageSent) {
                withContext(NonCancellable) {
                    runCatching { transport.send(InstaxMessages.printCancel()) }
                }
            }
            throw e
        }
    }

    private fun failureFor(printImageSent: Boolean, cause: Throwable): Exception =
        if (printImageSent) {
            PrintTriggeredButUnconfirmed("connection lost after PRINT_IMAGE", cause)
        } else {
            RetryableTransferError("transfer failed before PRINT_IMAGE", cause)
        }

    // --- notification plumbing ---

    /** Runs [block] with a channel fed by transport notifications. The collector
     *  is started UNDISPATCHED so it is subscribed before any send happens. */
    private suspend fun <T> withNotifications(
        block: suspend (Channel<InstaxPacket.Decoded>) -> T,
    ): T = coroutineScope {
        val frames = Channel<InstaxPacket.Decoded>(Channel.UNLIMITED)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                transport.notifications.collect { bytes ->
                    InstaxPacket.decode(bytes)?.let { frames.send(it) }
                }
                frames.close(TransportException("notifications completed"))
            } catch (e: Throwable) {
                frames.close(TransportException("notifications failed", e))
                throw e
            }
        }
        try {
            block(frames)
        } finally {
            collector.cancel()
        }
    }

    private suspend fun sendAwaitingAck(
        frames: Channel<InstaxPacket.Decoded>,
        packet: ByteArray,
        opcode: Opcode,
    ): InstaxPacket.Decoded {
        transport.send(packet)
        return awaitAck(frames, opcode)
    }

    private suspend fun awaitAck(
        frames: Channel<InstaxPacket.Decoded>,
        opcode: Opcode,
    ): InstaxPacket.Decoded = withTimeout(ackTimeout) {
        while (true) {
            val decoded = try {
                frames.receive()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // collector cancellation / outer timeout — not a transport failure
            } catch (e: Exception) {
                throw TransportException("connection lost awaiting ack for $opcode", e)
            }
            if (InstaxMessages.isAckFor(decoded, opcode)) return@withTimeout decoded
            // frames for other opcodes are stale/unsolicited — skip
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    private suspend fun <T : Any> requestInfo(
        frames: Channel<InstaxPacket.Decoded>,
        type: InfoType,
        parse: (ByteArray) -> T?,
    ): T {
        transport.send(InstaxMessages.infoQuery(type))
        return withTimeout(ackTimeout) {
            while (true) {
                val decoded = try {
                    frames.receive()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw TransportException("connection lost awaiting $type info", e)
                }
                if (decoded.opcode == Opcode.SUPPORT_FUNCTION_INFO) {
                    parse(decoded.payload)?.let { return@withTimeout it }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }
    }

    private companion object {
        /** Reference PrinterSettings: chunk size is looked up from the reported
         *  image geometry (fixtures pin the wide value). */
        fun chunkSizeFor(support: ImageSupport): Int = when (support.width to support.height) {
            1260 to 840 -> 900   // wide
            800 to 800 -> 1808   // square
            600 to 800 -> 900    // mini
            else -> 900
        }
    }
}
