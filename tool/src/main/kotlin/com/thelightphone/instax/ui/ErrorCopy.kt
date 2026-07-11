package com.thelightphone.instax.ui

import com.thelightphone.instax.protocol.InstaxError

/** Every way the print flow can fail, as the UI sees it. The viewmodel maps
 *  raw exceptions (TransportException, session errors, prep errors) into this. */
sealed class PrintFailure {
    data object BridgeUnreachable : PrintFailure()
    data object NoPrinterFound : PrintFailure()
    data object RetryableTransfer : PrintFailure()
    data object PrintTriggeredUnconfirmed : PrintFailure()
    data class Printer(val error: InstaxError) : PrintFailure()
    data object LowBattery : PrintFailure() // copy-only this milestone
    data object ImageTooLarge : PrintFailure()
}

/** One consolidated place for user-facing failure copy (Tesla-tool pattern). */
object ErrorCopy {

    enum class Action { RETRY, RETRY_CONFIRM }

    data class Entry(val headline: String, val detail: String, val action: Action?)

    fun entryFor(failure: PrintFailure): Entry = when (failure) {
        PrintFailure.BridgeUnreachable -> Entry(
            "Can't reach the print bridge",
            "Start it on your computer: scripts/instax/bridge.py",
            Action.RETRY,
        )
        PrintFailure.NoPrinterFound -> Entry(
            "No printer found",
            "Turn the printer on and keep it close.",
            Action.RETRY,
        )
        PrintFailure.RetryableTransfer -> Entry(
            "Connection lost",
            "Nothing was printed. Try again.",
            Action.RETRY,
        )
        PrintFailure.PrintTriggeredUnconfirmed -> Entry(
            "Print may have started",
            "Check the printer before retrying — a retry uses another sheet.",
            Action.RETRY_CONFIRM,
        )
        is PrintFailure.Printer -> when (failure.error) {
            InstaxError.NO_FILM -> Entry("Out of film", "Load a new WIDE cartridge.", null)
            InstaxError.COVER_OPEN -> Entry("Film cover open", "Close the cover.", null)
            InstaxError.BUSY -> Entry("Printer is busy", "Wait for it to finish, then retry.", Action.RETRY)
            InstaxError.UNKNOWN -> Entry("Printer refused", "Check the printer and try again.", Action.RETRY)
        }
        PrintFailure.LowBattery -> Entry(
            "Printer battery low",
            "Charge the printer, then retry.",
            null,
        )
        PrintFailure.ImageTooLarge -> Entry(
            "Can't prepare this photo",
            "It can't be compressed enough to print.",
            null,
        )
    }
}
