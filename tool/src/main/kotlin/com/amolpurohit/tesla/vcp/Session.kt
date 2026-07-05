package com.amolpurohit.tesla.vcp

import java.util.concurrent.atomic.AtomicLong

/**
 * Immutable per-vehicle-domain session state established by the session-info
 * handshake (see `scripts/tesla/vcp-fixtures/README.md` "Session establishment"
 * and "Session-info handshake"). Holds everything [CommandSigner] needs to
 * sign a command that it cannot derive from the command call itself:
 * the session key (already derived via `VcpCrypto.sessionKey(VcpCrypto.ecdhSharedSecretX(...))`),
 * the vehicle-issued epoch, the VIN (`TAG_PERSONALIZATION`), the client's
 * public key and routing address (used in the signed envelope), and the
 * counter/clock state needed to keep anti-replay values monotonic and
 * consistent with the vehicle's clock.
 *
 * [baseCounter] is the last counter value known-good from the handshake (or
 * the most recent successful command); [nextCounter] hands out a strictly
 * increasing sequence starting above it. [clockOffset] is the vehicle's
 * `clock_time` from the session-info response, for computing `expires_at`
 * as a session-relative deadline (`session_clock_time + expiry window`) —
 * see README "expires_at". Note [nextCounter] mutates internal state (an
 * atomic counter) even though the rest of the session data is immutable;
 * this class is otherwise a plain data holder.
 */
data class Session(
    val vin: String,
    val clientPublicKey: ByteArray,
    val fromRoutingAddress: ByteArray,
    val sessionKey: ByteArray,
    val epoch: ByteArray,
    val baseCounter: Long,
    val clockOffset: Int,
) {
    private val counter = AtomicLong(baseCounter)

    /** Returns a strictly increasing counter value each call, starting above [baseCounter]. */
    fun nextCounter(): Long = counter.incrementAndGet()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Session) return false
        return vin == other.vin &&
            clientPublicKey.contentEquals(other.clientPublicKey) &&
            fromRoutingAddress.contentEquals(other.fromRoutingAddress) &&
            sessionKey.contentEquals(other.sessionKey) &&
            epoch.contentEquals(other.epoch) &&
            baseCounter == other.baseCounter &&
            clockOffset == other.clockOffset
    }

    override fun hashCode(): Int {
        var result = vin.hashCode()
        result = 31 * result + clientPublicKey.contentHashCode()
        result = 31 * result + fromRoutingAddress.contentHashCode()
        result = 31 * result + sessionKey.contentHashCode()
        result = 31 * result + epoch.contentHashCode()
        result = 31 * result + baseCounter.hashCode()
        result = 31 * result + clockOffset
        return result
    }
}
