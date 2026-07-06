package com.amolpurohit.tesla.ui

import com.amolpurohit.tesla.vehicle.ErrorKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spec §8: "No raw HTTP/protocol errors are ever shown; every failure names
 * the next user action." Every [ErrorKind] must map to a short, non-empty
 * string that names something the user can actually do next.
 */
class ErrorCopyTest {

    // Lowercase cues that indicate the copy names a next action, per spec §8.
    private val actionCues = listOf("retry", "check", "re-link", "relink", "approve", "try", "wake")

    private fun namesAnAction(message: String): Boolean {
        val lower = message.lowercase()
        return actionCues.any { lower.contains(it) }
    }

    @Test fun `every ErrorKind maps to non-empty copy naming the next action`() {
        for (kind in ErrorKind.values()) {
            val message = ErrorCopy.errorMessage(kind)
            assertTrue(message.isNotBlank(), "expected non-empty copy for $kind")
            assertTrue(
                namesAnAction(message),
                "expected copy for $kind to name a next action (retry/check/re-link/approve/try/wake), got: \"$message\"",
            )
        }
    }

    @Test fun `all six ErrorKind messages are distinct`() {
        val messages = ErrorKind.values().map { ErrorCopy.errorMessage(it) }
        assertEquals(messages.size, messages.toSet().size, "expected no copy collisions between ErrorKinds: $messages")
    }

    @Test fun `Offline names checking the network and retrying`() {
        val message = ErrorCopy.errorMessage(ErrorKind.Offline)
        assertTrue(message.lowercase().contains("check"))
        assertTrue(message.lowercase().contains("retry"))
    }

    @Test fun `AuthExpired names re-linking the account`() {
        val message = ErrorCopy.errorMessage(ErrorKind.AuthExpired)
        assertTrue(message.lowercase().contains("re-link") || message.lowercase().contains("relink"))
    }

    @Test fun `KeyNotEnrolled names approving the key in the Tesla app`() {
        val message = ErrorCopy.errorMessage(ErrorKind.KeyNotEnrolled)
        assertTrue(message.lowercase().contains("approve"))
        assertTrue(message.contains("Tesla app"))
    }

    @Test fun `RateLimited names trying again shortly`() {
        val message = ErrorCopy.errorMessage(ErrorKind.RateLimited)
        assertTrue(message.lowercase().contains("try again"))
    }

    @Test fun `WakeTimeout names the car not waking and trying again`() {
        val message = ErrorCopy.errorMessage(ErrorKind.WakeTimeout)
        assertTrue(message.lowercase().contains("wake"))
        assertTrue(message.lowercase().contains("try again"))
    }

    @Test fun `Unknown is a generic retryable message`() {
        val message = ErrorCopy.errorMessage(ErrorKind.Unknown)
        assertTrue(message.lowercase().contains("try again"))
    }
}
