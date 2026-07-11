package com.thelightphone.instax

import com.thelightphone.instax.imaging.ImageTooLargeException
import com.thelightphone.instax.imaging.PrintImagePrep
import com.thelightphone.instax.photos.FilesDirPhotoRepository
import com.thelightphone.instax.photos.Photo
import com.thelightphone.instax.photos.PhotoRepository
import com.thelightphone.instax.protocol.InstaxSession
import com.thelightphone.instax.protocol.PrintProgress
import com.thelightphone.instax.protocol.PrintTriggeredButUnconfirmed
import com.thelightphone.instax.protocol.PrinterReportedError
import com.thelightphone.instax.protocol.RetryableTransferError
import com.thelightphone.instax.transport.TcpBridgeTransport
import com.thelightphone.instax.transport.TransportException
import com.thelightphone.instax.ui.PrintFailure
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** Connection + printer status as the UI sees it. */
sealed class PrinterUiStatus {
    data object Idle : PrinterUiStatus()
    data object Connecting : PrinterUiStatus()
    data class Connected(
        val batteryPercent: Int,
        val printsRemaining: Int,
        val charging: Boolean,
    ) : PrinterUiStatus()

    data class Failed(val failure: PrintFailure) : PrinterUiStatus()
}

sealed class PrintUiState {
    data object Idle : PrintUiState()
    data object Preparing : PrintUiState()
    data class Transferring(val percent: Int) : PrintUiState()
    data object Printing : PrintUiState()
    data object Done : PrintUiState()
    data class Failed(val failure: PrintFailure) : PrintUiState()
}

/**
 * App-level singleton owning all hot state. Each LightScreen is its own
 * ViewModelStoreOwner, so per-screen viewmodels CANNOT share state — a print
 * started on the Preview screen must still be observable from the Progress
 * screen. Everything long-lived therefore lives here; viewmodels are façades.
 */
object InstaxApp {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val printerStatus = MutableStateFlow<PrinterUiStatus>(PrinterUiStatus.Idle)
    val printState = MutableStateFlow<PrintUiState>(PrintUiState.Idle)
    val photos = MutableStateFlow<List<Photo>>(emptyList())
    val selectedPhoto = MutableStateFlow<Photo?>(null)

    private var repository: PhotoRepository? = null
    private var session: InstaxSession? = null
    private var connectJob: Job? = null
    private var printJob: Job? = null

    /** Idempotent; called from every screen's createViewModel with lightContext.filesDir. */
    fun init(filesDir: File) {
        if (repository == null) repository = FilesDirPhotoRepository(filesDir)
    }

    fun refreshPhotos() {
        val repo = repository ?: return
        scope.launch { photos.value = repo.photos() }
    }

    suspend fun thumbnail(photo: Photo) = repository?.thumbnail(photo)
    suspend fun fullImage(photo: Photo) = repository?.fullImage(photo)

    fun connectIfNeeded() {
        if (printerStatus.value is PrinterUiStatus.Connected ||
            printerStatus.value is PrinterUiStatus.Connecting
        ) return
        connectJob?.cancel()
        printerStatus.value = PrinterUiStatus.Connecting
        connectJob = scope.launch {
            try {
                // fresh transport per attempt: a dead bridge socket is sticky
                val transport = TcpBridgeTransport()
                val newSession = InstaxSession(transport)
                val device = withTimeoutOrNull(SCAN_TIMEOUT_MS) {
                    transport.scan().firstOrNull()
                } ?: run {
                    printerStatus.value = PrinterUiStatus.Failed(PrintFailure.NoPrinterFound)
                    return@launch
                }
                val info = newSession.connect(device)
                session = newSession
                printerStatus.value = PrinterUiStatus.Connected(
                    batteryPercent = info.batteryPercent,
                    printsRemaining = info.printsRemaining,
                    charging = info.charging,
                )
            } catch (e: TransportException) {
                Log.w(TAG, "connect failed", e)
                printerStatus.value = PrinterUiStatus.Failed(PrintFailure.BridgeUnreachable)
            } catch (e: Exception) {
                Log.w(TAG, "connect failed", e)
                printerStatus.value = PrinterUiStatus.Failed(PrintFailure.NoPrinterFound)
            }
        }
    }

    fun retryConnection() {
        printerStatus.value = PrinterUiStatus.Idle
        connectIfNeeded()
    }

    fun startPrint(photo: Photo) {
        val current = printState.value
        if (current is PrintUiState.Preparing || current is PrintUiState.Transferring ||
            current is PrintUiState.Printing
        ) return
        val repo = repository ?: return
        val activeSession = session ?: run {
            printState.value = PrintUiState.Failed(PrintFailure.NoPrinterFound)
            return
        }
        printState.value = PrintUiState.Preparing
        printJob = scope.launch {
            try {
                val bitmap = repo.fullImage(photo) ?: throw ImageTooLargeException()
                val jpeg = PrintImagePrep.prepare(bitmap)
                activeSession.print(jpeg).collect { progress ->
                    printState.value = when (progress) {
                        is PrintProgress.Transferring ->
                            PrintUiState.Transferring(100 * progress.chunksSent / progress.chunksTotal)
                        PrintProgress.Printing -> PrintUiState.Printing
                        PrintProgress.Done -> PrintUiState.Done
                    }
                }
                refreshPrinterStatus()
            } catch (e: kotlinx.coroutines.CancellationException) {
                printState.value = PrintUiState.Idle // user cancelled — no film used
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "print failed", e)
                printState.value = PrintUiState.Failed(failureFor(e))
            }
        }
    }

    fun cancelPrint() {
        printJob?.cancel()
    }

    fun acknowledgeResult() {
        printState.value = PrintUiState.Idle
    }

    private suspend fun refreshPrinterStatus() {
        val activeSession = session ?: return
        runCatching { activeSession.refreshStatus() }.onSuccess { status ->
            val connected = printerStatus.value as? PrinterUiStatus.Connected ?: return
            printerStatus.value = connected.copy(
                printsRemaining = status.printsRemaining,
                charging = status.charging,
            )
        }
    }

    private fun failureFor(e: Exception): PrintFailure = when (e) {
        is PrinterReportedError -> PrintFailure.Printer(e.error)
        is PrintTriggeredButUnconfirmed -> PrintFailure.PrintTriggeredUnconfirmed
        is RetryableTransferError -> PrintFailure.RetryableTransfer
        is ImageTooLargeException -> PrintFailure.ImageTooLarge
        is TransportException -> PrintFailure.BridgeUnreachable
        else -> PrintFailure.RetryableTransfer
    }

    private const val TAG = "InstaxApp"
    private const val SCAN_TIMEOUT_MS = 15_000L
}
