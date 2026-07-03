package com.amolpurohit.tesla.ui

import com.amolpurohit.tesla.vehicle.CommandResult
import com.amolpurohit.tesla.vehicle.ErrorKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Per-screen command dispatcher shared by the Home/Charge/Climate view models.
 *
 * Enforces a SINGLE in-flight command: while one command is pending, further
 * [launch] calls are ignored (spec §6 — every control carries its own in-flight
 * indicator; a single pending slot means overlapping commands cannot clobber
 * each other's pending/error state). Screens render other buttons disabled
 * while one runs.
 *
 * Not thread-safe: [launch] must always be called from a single thread. The
 * check-then-set on the pending slot is only race-free because view-model
 * methods are invoked exclusively from Compose's main-thread click handlers.
 */
class CommandTracker<C : Any> {
    private val _pending = MutableStateFlow<C?>(null)
    val pending: StateFlow<C?> = _pending.asStateFlow()

    private val _error = MutableStateFlow<TrackedError<C>?>(null)
    val error: StateFlow<TrackedError<C>?> = _error.asStateFlow()

    /**
     * Runs [block] for [command]. Pending is set synchronously (before any
     * coroutine runs) so the tapped button shows its spinner immediately.
     * Ignored when another command is already in flight.
     */
    fun launch(scope: CoroutineScope, command: C, block: suspend () -> CommandResult) {
        if (_pending.value != null) return
        _pending.value = command
        scope.launch {
            when (val result = block()) {
                is CommandResult.Success -> _error.value = null
                is CommandResult.Rejected -> _error.value = TrackedError(command, result.reason)
                is CommandResult.Failed -> _error.value = TrackedError(command, errorMessage(result.kind))
            }
            _pending.value = null
        }
    }
}

data class TrackedError<C>(val command: C, val message: String)

/** Inline-error lookup for a specific button: non-null only for the command that failed. */
fun <C> TrackedError<C>?.messageFor(command: C): String? =
    this?.takeIf { it.command == command }?.message

/** Short human strings (spec §8); Task 26 polishes copy later. */
fun errorMessage(kind: ErrorKind): String = when (kind) {
    ErrorKind.Offline -> "No connection"
    ErrorKind.AuthExpired -> "Sign-in expired"
    ErrorKind.KeyNotEnrolled -> "Key not enrolled"
    ErrorKind.RateLimited -> "Try again later"
    ErrorKind.WakeTimeout -> "Car didn't wake"
    ErrorKind.Unknown -> "Something went wrong"
}
