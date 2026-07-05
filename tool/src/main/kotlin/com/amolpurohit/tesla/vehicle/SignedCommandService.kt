package com.amolpurohit.tesla.vehicle

import com.amolpurohit.tesla.auth.AuthExpiredException
import com.amolpurohit.tesla.fleet.FleetApi
import com.amolpurohit.tesla.fleet.FleetOfflineException
import com.amolpurohit.tesla.fleet.RateLimitedException
import com.amolpurohit.tesla.vcp.ClientKeys
import com.amolpurohit.tesla.vcp.CommandSigner
import com.amolpurohit.tesla.vcp.ProtoReader
import com.amolpurohit.tesla.vcp.RoutableMessages
import com.amolpurohit.tesla.vcp.Session
import com.amolpurohit.tesla.vcp.SessionInfoResponses
import com.amolpurohit.tesla.vcp.SessionInfoStatus
import com.amolpurohit.tesla.vcp.VcpCrypto
import com.amolpurohit.tesla.vcp.VcpDomain
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Base64

/**
 * Glue between the VCP signer ([CommandSigner]/[Session]) and the Fleet API
 * ([FleetApi]): turns a [VehicleCommand] into a signed `RoutableMessage`,
 * maintaining one VCP session per [com.amolpurohit.tesla.vcp.VcpDomain]
 * (VCSEC and Infotainment are independently keyed — see
 * `scripts/tesla/vcp-fixtures/README.md` "Session establishment").
 *
 * Not itself a [VehicleRepository] — [RealVehicleRepository] (Task 25) calls
 * [execute] from its command methods and maps [CommandResult] onward.
 */
class SignedCommandService(
    private val api: FleetApi,
    private val keys: ClientKeys,
    private val vin: String,
    private val nowMs: () -> Long,
    private val uuidSource: () -> ByteArray,
) {
    // One routing address per client install would be more realistic, but nothing in the
    // protocol requires it to be stable across sessions/domains; a fixed 16-byte value
    // (distinct from Tesla's own vendored test fixtures' address) is sufficient here.
    private val clientRoutingAddress = ByteArray(16) { 0 }

    private val mutex = Mutex()
    private val sessions = mutableMapOf<VcpDomain, Session>()

    // Local wall-clock time (nowMs()) at which each domain's current session was
    // established, for expiresAtFor's clock-offset math. Keyed alongside `sessions`
    // (both cleared together on re-handshake since sessions[domain] is replaced above).
    private val handshakeAtMs = mutableMapOf<VcpDomain, Long>()

    suspend fun execute(vehicleId: String, command: VehicleCommand): CommandResult {
        return mutex.withLock {
            val session = sessionFor(vehicleId, command.domain)
                ?: return@withLock CommandResult.Failed(ErrorKind.KeyNotEnrolled)
            executeLocked(vehicleId, command, session, alreadyRetried = false)
        }
    }

    /** Must be called while holding [mutex]. */
    private suspend fun executeLocked(
        vehicleId: String,
        command: VehicleCommand,
        session: Session,
        alreadyRetried: Boolean,
    ): CommandResult {
        val expiresAt = expiresAtFor(command.domain, session)
        val signed = CommandSigner.sign(
            session = session,
            domain = command.domain,
            plaintextAction = command.plaintextAction,
            counter = session.nextCounter(),
            expiresAt = expiresAt,
            uuid = uuidSource(),
        )

        return try {
            val response = api.signedCommand(vehicleId, encodeB64(signed))
            val responseBytes = decodeB64(response.responseB64)
            handleResponse(vehicleId, command, responseBytes, alreadyRetried)
        } catch (e: AuthExpiredException) {
            throw e
        } catch (e: FleetOfflineException) {
            CommandResult.Failed(ErrorKind.Offline)
        } catch (e: RateLimitedException) {
            CommandResult.Failed(ErrorKind.RateLimited)
        }
        // Other FleetExceptions (VehicleAsleepException, FleetHttpException,
        // FleetPartialDataException) are not expected on this endpoint's happy
        // paths; deliberately NOT caught here so they surface loudly rather than
        // being silently folded into Failed(Unknown) — see Task 29 concerns.
    }

    private suspend fun handleResponse(
        vehicleId: String,
        command: VehicleCommand,
        responseBytes: ByteArray,
        alreadyRetried: Boolean,
    ): CommandResult {
        if (responseBytes.isEmpty()) {
            return CommandResult.Success
        }

        if (CommandSigner.isFault(responseBytes)) {
            val fault = readSignedMessageFault(responseBytes)
            if (fault == MESSAGEFAULT_ERROR_UNKNOWN_KEY_ID) {
                return CommandResult.Failed(ErrorKind.KeyNotEnrolled)
            }
            if (alreadyRetried) {
                return CommandResult.Failed(ErrorKind.Unknown)
            }
            // Re-handshake once and retry once (invalidate this domain's cached
            // session first so sessionFor forces a fresh handshake).
            sessions.remove(command.domain)
            val freshSession = sessionFor(vehicleId, command.domain)
                ?: return CommandResult.Failed(ErrorKind.KeyNotEnrolled)
            return executeLocked(vehicleId, command, freshSession, alreadyRetried = true)
        }

        return parseOutcome(command.domain, responseBytes)
    }

    /**
     * Parses the plaintext application-layer payload
     * (`RoutableMessage.protobuf_message_as_bytes`, field 10) for a
     * non-fault response, mapping it to [CommandResult.Success] or
     * [CommandResult.Rejected]. Shape differs by domain:
     * - VCSEC: `FromVCSECMessage{commandStatus{operationStatus}}` — no
     *   plain-text reason field exists on this path (vcsec.proto's
     *   `CommandStatus` only carries enums), so a rejection is reported with
     *   a fixed plain-language message.
     * - Infotainment: `CarServer.Response{actionStatus{result, result_reason
     *   {plain_text}}}` — the plain_text reason is surfaced directly.
     *
     * Conservative by design (per Task 24 rules): if the expected inner
     * message can't be found/decoded, this returns `Failed(Unknown)` rather
     * than guessing a specific rejection reason. Full validation is Task 29's
     * real-car E2E.
     */
    private fun parseOutcome(domain: VcpDomain, responseBytes: ByteArray): CommandResult {
        val payload = readProtobufMessageAsBytes(responseBytes) ?: return CommandResult.Success

        return when (domain) {
            VcpDomain.VEHICLE_SECURITY -> parseVcsecOutcome(payload)
            VcpDomain.INFOTAINMENT -> parseInfotainmentOutcome(payload)
        }
    }

    // FromVCSECMessage (vcsec.proto:287-295): oneof sub_message { ... CommandStatus commandStatus = 4; ... }
    private fun parseVcsecOutcome(fromVcsecMessageBytes: ByteArray): CommandResult {
        var commandStatusBytes: ByteArray? = null
        ProtoReader(fromVcsecMessageBytes).forEachField { field, _, value ->
            if (field == FROM_VCSEC_MESSAGE_COMMAND_STATUS) commandStatusBytes = value as? ByteArray
        }
        val statusBytes = commandStatusBytes ?: return CommandResult.Success

        var operationStatus = VCSEC_OPERATIONSTATUS_OK
        ProtoReader(statusBytes).forEachField { field, _, value ->
            if (field == VCSEC_COMMAND_STATUS_OPERATION_STATUS) operationStatus = (value as Long).toInt()
        }

        return if (operationStatus == VCSEC_OPERATIONSTATUS_OK) {
            CommandResult.Success
        } else {
            // No plain-text reason is available on the VCSEC response path (see
            // kdoc above) — conservative fixed message rather than a guessed one.
            CommandResult.Rejected("Vehicle rejected the command")
        }
    }

    // CarServer.Response (car_server.proto:127-134): ActionStatus actionStatus = 1;
    // ActionStatus (car_server.proto:151-154): OperationStatus_E result = 1; ResultReason result_reason = 2;
    // ResultReason (car_server.proto:162-165): oneof reason { string plain_text = 1; }
    private fun parseInfotainmentOutcome(responseMessageBytes: ByteArray): CommandResult {
        var actionStatusBytes: ByteArray? = null
        ProtoReader(responseMessageBytes).forEachField { field, _, value ->
            if (field == CARSERVER_RESPONSE_ACTION_STATUS) actionStatusBytes = value as? ByteArray
        }
        val statusBytes = actionStatusBytes ?: return CommandResult.Success

        var result = CARSERVER_OPERATIONSTATUS_OK
        var resultReasonBytes: ByteArray? = null
        ProtoReader(statusBytes).forEachField { field, _, value ->
            when (field) {
                CARSERVER_ACTION_STATUS_RESULT -> result = (value as Long).toInt()
                CARSERVER_ACTION_STATUS_RESULT_REASON -> resultReasonBytes = value as? ByteArray
            }
        }

        if (result == CARSERVER_OPERATIONSTATUS_OK) {
            return CommandResult.Success
        }

        val reason = resultReasonBytes?.let { readPlainTextReason(it) }
        return CommandResult.Rejected(reason?.takeIf { it.isNotBlank() } ?: "Vehicle rejected the command")
    }

    private fun readPlainTextReason(resultReasonBytes: ByteArray): String? {
        var plainText: String? = null
        ProtoReader(resultReasonBytes).forEachField { field, _, value ->
            if (field == CARSERVER_RESULT_REASON_PLAIN_TEXT) {
                plainText = (value as? ByteArray)?.toString(Charsets.UTF_8)
            }
        }
        return plainText
    }

    private fun readProtobufMessageAsBytes(routableMessageBytes: ByteArray): ByteArray? {
        var payload: ByteArray? = null
        ProtoReader(routableMessageBytes).forEachField { field, _, value ->
            if (field == ROUTABLE_MESSAGE_PROTOBUF_MESSAGE_AS_BYTES) payload = value as? ByteArray
        }
        return payload
    }

    private fun readSignedMessageFault(routableMessageBytes: ByteArray): Int {
        var statusBytes: ByteArray? = null
        ProtoReader(routableMessageBytes).forEachField { field, _, value ->
            if (field == ROUTABLE_MESSAGE_SIGNED_MESSAGE_STATUS) statusBytes = value as? ByteArray
        }
        val bytes = statusBytes ?: return MESSAGEFAULT_ERROR_NONE
        var fault = MESSAGEFAULT_ERROR_NONE
        ProtoReader(bytes).forEachField { field, _, value ->
            if (field == MESSAGE_STATUS_SIGNED_MESSAGE_FAULT) fault = (value as Long).toInt()
        }
        return fault
    }

    /**
     * Returns the cached session for [domain], or performs the session-info
     * handshake and caches a new one. Returns null if the handshake itself
     * reports the client key is not on the vehicle's whitelist (there's no
     * point proceeding to sign a command in that case). Must be called while
     * holding [mutex].
     */
    private suspend fun sessionFor(vehicleId: String, domain: VcpDomain): Session? {
        sessions[domain]?.let { return it }

        val handshakeLocalMs = nowMs()
        val request = RoutableMessages.sessionInfoRequest(
            domain = domain,
            clientRoutingAddress = clientRoutingAddress,
            clientPublicKey = keys.publicUncompressed,
            uuid = uuidSource(),
        )
        val response = api.signedCommand(vehicleId, encodeB64(request))
        val responseBytes = decodeB64(response.responseB64)
        val sessionInfo = SessionInfoResponses.parseSessionInfo(responseBytes)

        if (sessionInfo.status == SessionInfoStatus.KEY_NOT_ON_WHITELIST) {
            return null
        }

        val sharedX = VcpCrypto.ecdhSharedSecretX(keys.privatePkcs8, sessionInfo.publicKey)
        val sessionKey = VcpCrypto.sessionKey(sharedX)

        val session = Session(
            vin = vin,
            clientPublicKey = keys.publicUncompressed,
            fromRoutingAddress = clientRoutingAddress,
            sessionKey = sessionKey,
            epoch = sessionInfo.epoch,
            baseCounter = sessionInfo.counter,
            clockOffset = sessionInfo.clockTime,
        ).also { handshakeAtMs[domain] = handshakeLocalMs }

        sessions[domain] = session
        return session
    }

    /**
     * `expires_at` as a **session-relative** uint32 second count, matching the
     * vendored reference exactly (`internal/authentication/signer.go:187-192`
     * `AuthorizeHMAC`, and `signer.go:80` `ImportSessionInfo`):
     *
     * ```
     * timeZero    = handshakeLocalMs - clockTime * 1000       // signer.go:80
     * expiresAt   = (nowMs() + 5000 - timeZero) / 1000        // signer.go:191 (expiresIn = 5s)
     *             = clockTime + (nowMs() - handshakeLocalMs + 5000) / 1000
     * ```
     *
     * i.e. vehicle-clock-relative "now" (`clockTime` advanced by however much
     * local wall-clock time has elapsed since the handshake) plus the fixed
     * 5-second expiry window (`internal/dispatcher/session.go:16
     * defaultExpiration`).
     */
    private fun expiresAtFor(domain: VcpDomain, session: Session): Int {
        val handshakeLocalMs = handshakeAtMs[domain] ?: nowMs()
        val vehicleRelativeNowMs = session.clockOffset.toLong() * 1000L + (nowMs() - handshakeLocalMs)
        return ((vehicleRelativeNowMs + EXPIRY_WINDOW_MS) / 1000L).toInt()
    }

    private fun encodeB64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    private fun decodeB64(b64: String): ByteArray =
        if (b64.isEmpty()) ByteArray(0) else Base64.getDecoder().decode(b64)

    private companion object {
        private const val EXPIRY_WINDOW_MS = 5_000L // internal/dispatcher/session.go:16 defaultExpiration

        // universal_message.proto:88 payload oneof / universal_message.proto:97 signedMessageStatus
        private const val ROUTABLE_MESSAGE_PROTOBUF_MESSAGE_AS_BYTES = 10
        private const val ROUTABLE_MESSAGE_SIGNED_MESSAGE_STATUS = 12

        // universal_message.proto:63-67 MessageStatus.signed_message_fault field 2
        private const val MESSAGE_STATUS_SIGNED_MESSAGE_FAULT = 2
        private const val MESSAGEFAULT_ERROR_NONE = 0 // universal_message.proto:32
        private const val MESSAGEFAULT_ERROR_UNKNOWN_KEY_ID = 3 // universal_message.proto:35

        // vcsec.proto:287-295 FromVCSECMessage.commandStatus field 4
        private const val FROM_VCSEC_MESSAGE_COMMAND_STATUS = 4
        // vcsec.proto:215-222 CommandStatus.operationStatus field 1
        private const val VCSEC_COMMAND_STATUS_OPERATION_STATUS = 1
        // vcsec.proto:138-142 OperationStatus_E.OPERATIONSTATUS_OK
        private const val VCSEC_OPERATIONSTATUS_OK = 0

        // car_server.proto:127-134 Response.actionStatus field 1
        private const val CARSERVER_RESPONSE_ACTION_STATUS = 1
        // car_server.proto:151-154 ActionStatus.result field 1 / result_reason field 2
        private const val CARSERVER_ACTION_STATUS_RESULT = 1
        private const val CARSERVER_ACTION_STATUS_RESULT_REASON = 2
        // car_server.proto:155-158 OperationStatus_E.OPERATIONSTATUS_OK
        private const val CARSERVER_OPERATIONSTATUS_OK = 0
        // car_server.proto:162-165 ResultReason.plain_text field 1
        private const val CARSERVER_RESULT_REASON_PLAIN_TEXT = 1
    }
}
