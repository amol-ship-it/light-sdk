package com.amolpurohit.tesla.fleet

/**
 * Sealed hierarchy of failures the Fleet API client can surface. Kept
 * separate from [com.amolpurohit.tesla.auth.AuthExpiredException] — that
 * exception is thrown by [com.amolpurohit.tesla.fleet.TokenSource]
 * implementations (e.g. TokenManager) and passes through FleetClient
 * untouched; it is not part of this hierarchy.
 */
sealed class FleetException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Vehicle is asleep and the requested endpoint needs it awake. Fleet API
 * signals this with HTTP 408 on vehicle_data/command endpoints.
 */
class VehicleAsleepException : FleetException("Vehicle is asleep")

/** Fleet API returned HTTP 429; caller should back off. */
class RateLimitedException : FleetException("Rate limited by Fleet API")

/**
 * Network-level failure (no response at all) — DNS, timeout, connection
 * reset, etc. Wraps the underlying [java.io.IOException].
 */
class FleetOfflineException(cause: Throwable) : FleetException("Network error reaching Fleet API", cause)

/**
 * Any other non-2xx HTTP response. [briefBody] is truncated to at most 200
 * characters and must never include request contents (headers, body, URL
 * params) — only the response body, so no bearer tokens or vehicle command
 * payloads can leak into logs or crash reports.
 */
class FleetHttpException(val code: Int, val briefBody: String) :
    FleetException("Fleet API HTTP $code: $briefBody")
