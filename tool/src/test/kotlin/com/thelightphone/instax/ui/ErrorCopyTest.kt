package com.thelightphone.instax.ui

import com.thelightphone.instax.protocol.InstaxError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ErrorCopyTest {

    private val allFailures: List<PrintFailure> = listOf(
        PrintFailure.BridgeUnreachable,
        PrintFailure.NoPrinterFound,
        PrintFailure.RetryableTransfer,
        PrintFailure.PrintTriggeredUnconfirmed,
        PrintFailure.LowBattery,
        PrintFailure.ImageTooLarge,
    ) + InstaxError.entries.map { PrintFailure.Printer(it) }

    @Test
    fun `every failure has non-blank distinct copy`() {
        val entries = allFailures.map { ErrorCopy.entryFor(it) }
        entries.forEach {
            assertTrue(it.headline.isNotBlank())
            assertTrue(it.detail.isNotBlank())
        }
        assertEquals(entries.size, entries.map { it.headline to it.detail }.distinct().size)
    }

    @Test
    fun `post print-image loss requires confirmed retry`() {
        assertEquals(
            ErrorCopy.Action.RETRY_CONFIRM,
            ErrorCopy.entryFor(PrintFailure.PrintTriggeredUnconfirmed).action,
        )
    }

    @Test
    fun `out of film has no retry action`() {
        assertEquals(null, ErrorCopy.entryFor(PrintFailure.Printer(InstaxError.NO_FILM)).action)
    }
}
