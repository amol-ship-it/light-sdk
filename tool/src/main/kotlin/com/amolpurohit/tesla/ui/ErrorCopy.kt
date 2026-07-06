package com.amolpurohit.tesla.ui

import com.amolpurohit.tesla.vehicle.ErrorKind

/**
 * Canonical, ONE-source-of-truth copy for [ErrorKind] (spec §8: "No raw
 * HTTP/protocol errors are ever shown; every failure names the next user
 * action"). [CommandTracker.errorMessage] and all three command screens'
 * whole-screen `Error` branches resolve through [errorMessage] rather than
 * inventing their own wording.
 *
 * [com.amolpurohit.tesla.vehicle.CommandResult.Rejected.reason] is NOT
 * covered here — it is the vehicle's own plain-text rejection reason and
 * passes through verbatim wherever it's rendered (see `CommandTracker.launch`
 * and `SignedCommandService.parseInfotainmentOutcome`/`parseVcsecOutcome`).
 */
object ErrorCopy {
    fun errorMessage(kind: ErrorKind): String = when (kind) {
        ErrorKind.Offline -> "No connection — check your network and retry"
        ErrorKind.AuthExpired -> "Sign-in expired — re-link your account"
        ErrorKind.KeyNotEnrolled -> "Key not approved — approve it in the Tesla app"
        ErrorKind.RateLimited -> "Tesla is rate-limiting — try again shortly"
        ErrorKind.WakeTimeout -> "Car didn't wake — try again"
        ErrorKind.Unknown -> "Something went wrong — try again"
    }
}
