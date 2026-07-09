package com.amolpurohit.tesla.vehicle

import com.amolpurohit.tesla.fleet.FleetApi
import com.amolpurohit.tesla.fleet.SignedCommandResponse
import com.amolpurohit.tesla.fleet.VehicleSummary
import com.amolpurohit.tesla.vcp.ClientKeys
import com.amolpurohit.tesla.vcp.ProtoWriter
import com.amolpurohit.tesla.vcp.SessionInfoResponses
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SignedCommandServiceTest {

    private val vehicleId = "v1"
    private val vin = "5YJ3E1EA1PF000001"

    private fun b64(s: String): ByteArray = Base64.getDecoder().decode(s)
    private fun toB64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    // ---- fixtures ----

    @Serializable
    private data class KeysFixture(
        val client_private_pkcs8_b64: String,
        val client_public_b64: String,
    )

    @Serializable
    private data class FaultResponseFixture(
        val routable_message_b64: String,
    )

    private val json = Json { ignoreUnknownKeys = true }

    private fun keysFixture(): KeysFixture {
        val text = ClassLoader.getSystemResource("vcp/keys.json")?.readText()
            ?: error("could not load vcp/keys.json")
        return json.decodeFromString(text)
    }

    private fun faultResponseFixture(): FaultResponseFixture {
        val text = ClassLoader.getSystemResource("vcp/fault_response.json")?.readText()
            ?: error("could not load vcp/fault_response.json")
        return json.decodeFromString(text)
    }

    private fun clientKeys(): ClientKeys {
        val keys = keysFixture()
        return ClientKeys.of(
            privatePkcs8 = b64(keys.client_private_pkcs8_b64),
            publicUncompressed = b64(keys.client_public_b64),
        )
    }

    // ---- synthetic wire builders (mirrors CommandSigner/Messages field layout) ----

    private object Fields {
        const val TO_DESTINATION = 6
        const val FROM_DESTINATION = 7
        const val PAYLOAD_PROTOBUF_MESSAGE_AS_BYTES = 10
        const val PAYLOAD_SESSION_INFO = 15
        const val SIGNED_MESSAGE_STATUS = 12
        const val UUID = 51

        const val DESTINATION_DOMAIN = 1
        const val DESTINATION_ROUTING_ADDRESS = 2

        const val MESSAGE_STATUS_SIGNED_MESSAGE_FAULT = 2

        const val SESSION_INFO_COUNTER = 1
        const val SESSION_INFO_PUBLIC_KEY = 2
        const val SESSION_INFO_EPOCH = 3
        const val SESSION_INFO_CLOCK_TIME = 4
        const val SESSION_INFO_STATUS = 5

        const val FROM_VCSEC_MESSAGE_COMMAND_STATUS = 4
        const val VCSEC_COMMAND_STATUS_OPERATION_STATUS = 1
        const val VCSEC_OPERATIONSTATUS_ERROR = 2

        const val CARSERVER_RESPONSE_ACTION_STATUS = 1
        const val CARSERVER_ACTION_STATUS_RESULT = 1
        const val CARSERVER_ACTION_STATUS_RESULT_REASON = 2
        const val CARSERVER_OPERATIONSTATUS_ERROR = 1
        const val CARSERVER_RESULT_REASON_PLAIN_TEXT = 1
    }

    /** Builds a session-info-response RoutableMessage carrying a SessionInfo payload. */
    private fun sessionInfoResponse(
        vehiclePublicKey: ByteArray,
        epoch: ByteArray,
        counter: Long,
        clockTime: Int,
        status: Int = 0,
    ): ByteArray {
        val sessionInfo = ProtoWriter()
            .varint(Fields.SESSION_INFO_COUNTER, counter)
            .bytes(Fields.SESSION_INFO_PUBLIC_KEY, vehiclePublicKey)
            .bytes(Fields.SESSION_INFO_EPOCH, epoch)
            .fixed32(Fields.SESSION_INFO_CLOCK_TIME, clockTime)
            .varint(Fields.SESSION_INFO_STATUS, status.toLong())
            .toByteArray()
        return ProtoWriter()
            .bytes(Fields.PAYLOAD_SESSION_INFO, sessionInfo)
            .toByteArray()
    }

    /** A successful signed-command response: RoutableMessage with no fault, empty payload. */
    private fun successResponse(): ByteArray = ByteArray(0)

    /** A MESSAGE_FAULT RoutableMessage carrying the given fault code. */
    private fun faultResponse(faultCode: Int): ByteArray {
        val status = ProtoWriter().varint(Fields.MESSAGE_STATUS_SIGNED_MESSAGE_FAULT, faultCode.toLong())
        return ProtoWriter().message(Fields.SIGNED_MESSAGE_STATUS, status).toByteArray()
    }

    /** A VCSEC rejection: FromVCSECMessage{commandStatus{operationStatus=ERROR}}. */
    private fun vcsecRejection(): ByteArray {
        val commandStatus = ProtoWriter()
            .varint(Fields.VCSEC_COMMAND_STATUS_OPERATION_STATUS, Fields.VCSEC_OPERATIONSTATUS_ERROR.toLong())
        val fromVcsecMessage = ProtoWriter().message(Fields.FROM_VCSEC_MESSAGE_COMMAND_STATUS, commandStatus)
        return ProtoWriter()
            .bytes(Fields.PAYLOAD_PROTOBUF_MESSAGE_AS_BYTES, fromVcsecMessage.toByteArray())
            .toByteArray()
    }

    /** An Infotainment rejection: CarServer.Response{actionStatus{result=ERROR, result_reason{plain_text}}}. */
    private fun infotainmentRejection(reason: String): ByteArray {
        val resultReason = ProtoWriter().string(Fields.CARSERVER_RESULT_REASON_PLAIN_TEXT, reason)
        val actionStatus = ProtoWriter()
            .varint(Fields.CARSERVER_ACTION_STATUS_RESULT, Fields.CARSERVER_OPERATIONSTATUS_ERROR.toLong())
            .message(Fields.CARSERVER_ACTION_STATUS_RESULT_REASON, resultReason)
        val response = ProtoWriter().message(Fields.CARSERVER_RESPONSE_ACTION_STATUS, actionStatus)
        return ProtoWriter()
            .bytes(Fields.PAYLOAD_PROTOBUF_MESSAGE_AS_BYTES, response.toByteArray())
            .toByteArray()
    }

    /**
     * A response whose application payload (field 10) is NOT decodable protobuf —
     * modelling the AES-GCM-encrypted body the real vehicle returns when
     * FLAG_ENCRYPT_RESPONSE is set. The leading 0x3F byte parses as wire type 7
     * (invalid), so ProtoReader throws — which the service must treat as success
     * (isFault already ruled out a protocol-level rejection), not crash.
     */
    private fun encryptedLikeResponse(): ByteArray =
        ProtoWriter()
            .bytes(
                Fields.PAYLOAD_PROTOBUF_MESSAGE_AS_BYTES,
                byteArrayOf(0x3F, 0x7A, 0x11, 0x9C.toByte(), 0x04),
            )
            .toByteArray()

    // ---- fake Fleet API ----

    private class FakeFleetApi(
        private val responses: MutableList<() -> SignedCommandResponse>,
    ) : FleetApi {
        val requests = mutableListOf<String>()
        var signedCommandCount = 0
            private set

        override suspend fun listVehicles(): List<VehicleSummary> = emptyList()
        override suspend fun vehicleSummary(id: String): VehicleSummary? = null
        override suspend fun vehicleData(id: String) = error("not used")
        override suspend fun wakeUp(id: String) {}

        override suspend fun signedCommand(id: String, routableMessageB64: String): SignedCommandResponse {
            signedCommandCount++
            requests += routableMessageB64
            check(responses.isNotEmpty()) { "signedCommand called with empty script (call #$signedCommandCount)" }
            val next = if (responses.size == 1) responses[0] else responses.removeAt(0)
            return next()
        }
    }

    private fun vehiclePublicKeyFromSessionResponseFixture(): ByteArray {
        val text = ClassLoader.getSystemResource("vcp/session_info_response.json")?.readText()!!
        @Serializable
        data class Parsed(val vehicle_public_b64: String)
        @Serializable
        data class Fixture(val parsed: Parsed)
        val fixture: Fixture = json.decodeFromString(text)
        return b64(fixture.parsed.vehicle_public_b64)
    }

    private fun handshakeOkResponse(clockTime: Int = 0): SignedCommandResponse {
        val vehiclePublic = vehiclePublicKeyFromSessionResponseFixture()
        val epoch = ByteArray(16) { it.toByte() }
        val bytes = sessionInfoResponse(vehiclePublic, epoch, counter = 0L, clockTime = clockTime)
        return SignedCommandResponse(toB64(bytes))
    }

    private fun service(api: FleetApi, nowMs: () -> Long = { 0L }): SignedCommandService {
        return SignedCommandService(
            api = api,
            keys = clientKeys(),
            vin = vin,
            nowMs = nowMs,
            uuidSource = { ByteArray(16) { 0x11 } },
        )
    }

    // ---- tests ----

    @Test
    fun `first command to a domain handshakes then signs`() = runTest {
        val api = FakeFleetApi(
            responses = mutableListOf(
                { handshakeOkResponse() },
                { SignedCommandResponse(toB64(successResponse())) },
            ),
        )
        val svc = service(api)

        val result = svc.execute(vehicleId, VehicleCommand.Lock)

        assertEquals(CommandResult.Success, result)
        assertEquals(2, api.signedCommandCount)

        // First request is the unsigned session-info-request (no signature_data field 13).
        val firstRequestBytes = Base64.getDecoder().decode(api.requests[0])
        assertTrue(
            !containsField13(firstRequestBytes),
            "expected first request to be an unsigned session-info-request (no signature_data)",
        )
        val secondRequestBytes = Base64.getDecoder().decode(api.requests[1])
        assertTrue(
            containsField13(secondRequestBytes),
            "expected second request to be signed (signature_data present)",
        )
    }

    /** Cheap check for the presence of a top-level field 13 (signature_data) without a full decoder. */
    private fun containsField13(routableMessageBytes: ByteArray): Boolean {
        var found = false
        com.amolpurohit.tesla.vcp.ProtoReader(routableMessageBytes).forEachField { field, _, _ ->
            if (field == 13) found = true
        }
        return found
    }

    @Test
    fun `second command to same domain reuses session, no second handshake`() = runTest {
        val api = FakeFleetApi(
            responses = mutableListOf(
                { handshakeOkResponse() },
                { SignedCommandResponse(toB64(successResponse())) },
                { SignedCommandResponse(toB64(successResponse())) },
            ),
        )
        val svc = service(api)

        val r1 = svc.execute(vehicleId, VehicleCommand.Lock)
        val r2 = svc.execute(vehicleId, VehicleCommand.Unlock)

        assertEquals(CommandResult.Success, r1)
        assertEquals(CommandResult.Success, r2)
        assertEquals(3, api.signedCommandCount) // 1 handshake + 2 signed commands
    }

    @Test
    fun `different domains handshake independently`() = runTest {
        val api = FakeFleetApi(
            responses = mutableListOf(
                { handshakeOkResponse() }, // VCSEC handshake
                { SignedCommandResponse(toB64(successResponse())) }, // lock
                { handshakeOkResponse() }, // Infotainment handshake
                { SignedCommandResponse(toB64(successResponse())) }, // charge start
            ),
        )
        val svc = service(api)

        val r1 = svc.execute(vehicleId, VehicleCommand.Lock)
        val r2 = svc.execute(vehicleId, VehicleCommand.StartCharging)

        assertEquals(CommandResult.Success, r1)
        assertEquals(CommandResult.Success, r2)
        assertEquals(4, api.signedCommandCount)
    }

    @Test
    fun `MESSAGE_FAULT triggers exactly one re-handshake and retry then fails`() = runTest {
        val fault = faultResponseFixture()
        val api = FakeFleetApi(
            responses = mutableListOf(
                { handshakeOkResponse() }, // initial handshake
                { SignedCommandResponse(toB64(b64(fault.routable_message_b64))) }, // fault on first attempt
                { handshakeOkResponse() }, // re-handshake
                { SignedCommandResponse(toB64(b64(fault.routable_message_b64))) }, // fault again on retry
            ),
        )
        val svc = service(api)

        val result = svc.execute(vehicleId, VehicleCommand.Lock)

        assertIs<CommandResult.Failed>(result)
        assertEquals(ErrorKind.Unknown, result.kind)
        // handshake, signed(fault), re-handshake, signed(fault) = 4 calls, not more (no infinite retry loop).
        assertEquals(4, api.signedCommandCount)
    }

    @Test
    fun `MESSAGE_FAULT then success on retry succeeds`() = runTest {
        val fault = faultResponseFixture()
        val api = FakeFleetApi(
            responses = mutableListOf(
                { handshakeOkResponse() },
                { SignedCommandResponse(toB64(b64(fault.routable_message_b64))) },
                { handshakeOkResponse() },
                { SignedCommandResponse(toB64(successResponse())) },
            ),
        )
        val svc = service(api)

        val result = svc.execute(vehicleId, VehicleCommand.Lock)

        assertEquals(CommandResult.Success, result)
        assertEquals(4, api.signedCommandCount)
    }

    @Test
    fun `vcsec rejection maps to Rejected with plain reason`() = runTest {
        val api = FakeFleetApi(
            responses = mutableListOf(
                { handshakeOkResponse() },
                { SignedCommandResponse(toB64(vcsecRejection())) },
            ),
        )
        val svc = service(api)

        val result = svc.execute(vehicleId, VehicleCommand.Lock)

        assertIs<CommandResult.Rejected>(result)
        assertEquals("Vehicle rejected the command", result.reason)
    }

    @Test
    fun `infotainment rejection surfaces the vehicle's plain-text reason`() = runTest {
        val api = FakeFleetApi(
            responses = mutableListOf(
                { handshakeOkResponse() },
                { SignedCommandResponse(toB64(infotainmentRejection("charge port door is open"))) },
            ),
        )
        val svc = service(api)

        val result = svc.execute(vehicleId, VehicleCommand.StartCharging)

        assertIs<CommandResult.Rejected>(result)
        assertEquals("charge port door is open", result.reason)
    }

    @Test
    fun `key-not-enrolled fault maps to Failed KeyNotEnrolled without retry`() = runTest {
        val api = FakeFleetApi(
            responses = mutableListOf(
                { handshakeOkResponse() },
                { SignedCommandResponse(toB64(faultResponse(3))) }, // MESSAGEFAULT_ERROR_UNKNOWN_KEY_ID = 3
            ),
        )
        val svc = service(api)

        val result = svc.execute(vehicleId, VehicleCommand.Lock)

        assertIs<CommandResult.Failed>(result)
        assertEquals(ErrorKind.KeyNotEnrolled, result.kind)
        // No re-handshake/retry for this fault: handshake + one signed attempt only.
        assertEquals(2, api.signedCommandCount)
    }

    @Test
    fun `key-not-on-whitelist at handshake maps to Failed KeyNotEnrolled`() = runTest {
        val api = FakeFleetApi(
            responses = mutableListOf(
                {
                    val vehiclePublic = vehiclePublicKeyFromSessionResponseFixture()
                    val epoch = ByteArray(16) { it.toByte() }
                    val bytes = sessionInfoResponse(vehiclePublic, epoch, counter = 0L, clockTime = 0, status = 1)
                    SignedCommandResponse(toB64(bytes))
                },
            ),
        )
        val svc = service(api)

        val result = svc.execute(vehicleId, VehicleCommand.Lock)

        assertIs<CommandResult.Failed>(result)
        assertEquals(ErrorKind.KeyNotEnrolled, result.kind)
        assertEquals(1, api.signedCommandCount)
    }

    @Test
    fun `verifyKey handshakes to Infotainment and succeeds without sending a command`() = runTest {
        val api = FakeFleetApi(
            responses = mutableListOf(
                { handshakeOkResponse() },
            ),
        )
        val svc = service(api)

        val result = svc.verifyKey(vehicleId)

        assertEquals(CommandResult.Success, result)
        // Handshake only — no signed lock/unlock/etc. is ever sent.
        assertEquals(1, api.signedCommandCount)
        assertTrue(
            !containsField13(Base64.getDecoder().decode(api.requests[0])),
            "expected the only request to be the unsigned session-info-request",
        )
    }

    @Test
    fun `verifyKey maps whitelist-reject at handshake to Failed KeyNotEnrolled`() = runTest {
        val api = FakeFleetApi(
            responses = mutableListOf(
                {
                    val vehiclePublic = vehiclePublicKeyFromSessionResponseFixture()
                    val epoch = ByteArray(16) { it.toByte() }
                    val bytes = sessionInfoResponse(vehiclePublic, epoch, counter = 0L, clockTime = 0, status = 1)
                    SignedCommandResponse(toB64(bytes))
                },
            ),
        )
        val svc = service(api)

        val result = svc.verifyKey(vehicleId)

        assertIs<CommandResult.Failed>(result)
        assertEquals(ErrorKind.KeyNotEnrolled, result.kind)
        assertEquals(1, api.signedCommandCount)
    }

    @Test
    fun `verifyKey reuses an already-cached Infotainment session without re-handshaking`() = runTest {
        val api = FakeFleetApi(
            responses = mutableListOf(
                { handshakeOkResponse() }, // Infotainment handshake (from StartCharging)
                { SignedCommandResponse(toB64(successResponse())) }, // charge start
            ),
        )
        val svc = service(api)

        svc.execute(vehicleId, VehicleCommand.StartCharging)
        val result = svc.verifyKey(vehicleId)

        assertEquals(CommandResult.Success, result)
        assertEquals(2, api.signedCommandCount) // no extra handshake call
    }

    @Test
    fun `verifyKey maps offline transport error to Failed Offline`() = runTest {
        val api = object : FleetApi {
            override suspend fun listVehicles(): List<VehicleSummary> = emptyList()
            override suspend fun vehicleSummary(id: String): VehicleSummary? = null
            override suspend fun vehicleData(id: String) = error("not used")
            override suspend fun wakeUp(id: String) {}
            override suspend fun signedCommand(id: String, routableMessageB64: String): SignedCommandResponse {
                throw com.amolpurohit.tesla.fleet.FleetOfflineException(java.io.IOException("boom"))
            }
        }
        val svc = service(api)

        val result = svc.verifyKey(vehicleId)

        assertIs<CommandResult.Failed>(result)
        assertEquals(ErrorKind.Offline, result.kind)
    }

    @Test
    fun `expiresAt reflects clock offset and elapsed wall time via injected clock`() = runTest {
        // Handshake at local t=1_000_000ms reports vehicle clockTime=100 (seconds).
        // The expiry window is 30s (widened from the reference's BLE-tuned 5s for
        // Fleet API relay latency). A command signed at the same instant should
        // compute expiresAt = 100 + 0 + 30 = 130. A later command signed 3s of local
        // wall-clock time after the handshake should compute 100 + 3 + 30 = 133.
        var clock = 1_000_000L
        val api = FakeFleetApi(
            responses = mutableListOf(
                { handshakeOkResponse(clockTime = 100) },
                { SignedCommandResponse(toB64(successResponse())) },
                { SignedCommandResponse(toB64(successResponse())) },
            ),
        )
        val svc = service(api, nowMs = { clock })

        svc.execute(vehicleId, VehicleCommand.Lock) // triggers handshake, then signs at clock=1_000_000

        val firstSignedRequestBytes = Base64.getDecoder().decode(api.requests[1])
        assertEquals(130, extractExpiresAt(firstSignedRequestBytes))

        clock = 1_003_000L
        svc.execute(vehicleId, VehicleCommand.Unlock) // same cached session, signs at clock=1_003_000

        val secondSignedRequestBytes = Base64.getDecoder().decode(api.requests[2])
        assertEquals(133, extractExpiresAt(secondSignedRequestBytes))
    }

    @Test
    fun `encrypted or unparseable response body is treated as success`() = runTest {
        val api = FakeFleetApi(
            responses = mutableListOf(
                { handshakeOkResponse() },
                { SignedCommandResponse(toB64(encryptedLikeResponse())) },
            ),
        )
        val svc = service(api, nowMs = { 0L })

        val result = svc.execute(vehicleId, VehicleCommand.ClimateOn)

        assertEquals(CommandResult.Success, result)
    }

    /** Extracts HMAC_Personalized_Signature_Data.expires_at (tag 3, fixed32 LE) from a signed RoutableMessage. */
    private fun extractExpiresAt(signedRoutableMessageBytes: ByteArray): Int {
        var signatureDataBytes: ByteArray? = null
        com.amolpurohit.tesla.vcp.ProtoReader(signedRoutableMessageBytes).forEachField { field, _, value ->
            if (field == 13) signatureDataBytes = value as? ByteArray
        }
        var hmacDataBytes: ByteArray? = null
        com.amolpurohit.tesla.vcp.ProtoReader(signatureDataBytes!!).forEachField { field, _, value ->
            if (field == 8) hmacDataBytes = value as? ByteArray
        }
        var expiresAt = 0
        com.amolpurohit.tesla.vcp.ProtoReader(hmacDataBytes!!).forEachField { field, wireType, value ->
            if (field == 3) expiresAt = (value as Long).toInt()
        }
        return expiresAt
    }
}
