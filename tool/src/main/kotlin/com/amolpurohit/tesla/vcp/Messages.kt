package com.amolpurohit.tesla.vcp

/**
 * Hand-written VCP message encoders/decoders on top of [ProtoWriter] /
 * [ProtoReader]. Byte-exact against the fixtures in
 * `tool/src/test/resources/vcp/` fixture JSON files; field numbers transcribed from the
 * vendored `.proto` files under
 * `scripts/tesla/vcp-fixtures/upstream/pkg/protocol/protobuf/` (pinned v0.4.1,
 * see `scripts/tesla/vcp-fixtures/README.md`).
 *
 * Scope: PLAINTEXT application payloads, the UNSIGNED session-info request
 * envelope, and the session-info RESPONSE decoder only. Signing/encryption
 * and the signed command envelope are Task 23 (out of scope here).
 */

/** Domain routing: `universal_message.proto:10-14` `enum Domain`. */
enum class VcpDomain(val wireValue: Int) {
    VEHICLE_SECURITY(2), // universal_message.proto:12 DOMAIN_VEHICLE_SECURITY
    INFOTAINMENT(3),     // universal_message.proto:13 DOMAIN_INFOTAINMENT
}

/**
 * `VCSEC.UnsignedMessage` (vcsec.proto:224-232) — only the `RKEAction` arm
 * (vcsec.proto:228, field 2) is needed for lock/unlock.
 */
object VcsecMessages {
    private const val UNSIGNED_MESSAGE_RKE_ACTION = 2 // vcsec.proto:228

    // vcsec.proto:76-82 `enum RKEAction_E`
    const val RKE_ACTION_UNLOCK = 0 // vcsec.proto:77
    const val RKE_ACTION_LOCK = 1 // vcsec.proto:78

    fun rkeAction(action: Int): ByteArray {
        return ProtoWriter().varint(UNSIGNED_MESSAGE_RKE_ACTION, action.toLong()).toByteArray()
    }
}

/**
 * `UniversalMessage.RoutableMessage` (universal_message.proto:80-101).
 * Only the fields needed to build the UNSIGNED session-info-request envelope:
 * to_destination(6), from_destination(7), uuid(51), session_info_request(14,
 * payload oneof) — in that wire order, per the fixtures.
 */
object RoutableMessages {
    private const val TO_DESTINATION = 6 // universal_message.proto:84
    private const val FROM_DESTINATION = 7 // universal_message.proto:85
    private const val PAYLOAD_SESSION_INFO_REQUEST = 14 // universal_message.proto:89
    private const val UUID = 51 // universal_message.proto:99

    // universal_message.proto:16-21 `message Destination { oneof sub_destination { Domain domain = 1; bytes routing_address = 2; } }`
    private const val DESTINATION_DOMAIN = 1 // universal_message.proto:18
    private const val DESTINATION_ROUTING_ADDRESS = 2 // universal_message.proto:19

    // universal_message.proto:69-73 `message SessionInfoRequest { bytes public_key = 1; bytes challenge = 2; }`
    // Only public_key (field 1) is used — the fixtures never set challenge (field 2)
    // on this message; the "challenge" value from the README is used as this
    // RoutableMessage's own uuid (field 51) instead.
    private const val SESSION_INFO_REQUEST_PUBLIC_KEY = 1 // universal_message.proto:71

    private fun destinationDomain(domain: VcpDomain): ProtoWriter =
        ProtoWriter().varint(DESTINATION_DOMAIN, domain.wireValue.toLong())

    private fun destinationRoutingAddress(routingAddress: ByteArray): ProtoWriter =
        ProtoWriter().bytes(DESTINATION_ROUTING_ADDRESS, routingAddress)

    /**
     * Builds the unsigned session-info-request `RoutableMessage`:
     * to_destination = { domain }, from_destination = { routing_address },
     * uuid, payload = session_info_request{ public_key }.
     *
     * Field encoding order matches the fixtures exactly: 6, 7, 51, 14. Only
     * `public_key` is set on `SessionInfoRequest` — the fixtures do not set
     * `challenge` (field 2) on this message; the 16-byte "challenge" value
     * documented in the README is reused as the RoutableMessage's own `uuid`
     * (field 51), not as `SessionInfoRequest.challenge`.
     */
    fun sessionInfoRequest(
        domain: VcpDomain,
        clientRoutingAddress: ByteArray,
        clientPublicKey: ByteArray,
        uuid: ByteArray,
    ): ByteArray {
        val sessionInfoRequest = ProtoWriter()
            .bytes(SESSION_INFO_REQUEST_PUBLIC_KEY, clientPublicKey)

        return ProtoWriter()
            .message(TO_DESTINATION, destinationDomain(domain))
            .message(FROM_DESTINATION, destinationRoutingAddress(clientRoutingAddress))
            .bytes(UUID, uuid)
            .message(PAYLOAD_SESSION_INFO_REQUEST, sessionInfoRequest)
            .toByteArray()
    }
}

/** Decoded `Signatures.SessionInfo` (signatures.proto:87-94), the fields Task 22 needs. */
data class SessionInfo(
    val counter: Long,
    val publicKey: ByteArray,
    val epoch: ByteArray,
    val clockTime: Int,
    val status: Int,
)

/** `Signatures.Session_Info_Status` (signatures.proto:81-84). */
object SessionInfoStatus {
    const val OK = 0 // signatures.proto:82 SESSION_INFO_STATUS_OK
    const val KEY_NOT_ON_WHITELIST = 1 // signatures.proto:83
}

/**
 * Decodes the session-info handshake response: a `RoutableMessage` whose
 * `payload` oneof carries raw `session_info` bytes (universal_message.proto:90,
 * field 15) containing an embedded `Signatures.SessionInfo` message
 * (signatures.proto:87-94).
 */
object SessionInfoResponses {
    private const val PAYLOAD_SESSION_INFO = 15 // universal_message.proto:90

    // signatures.proto:87-94 `message SessionInfo`
    private const val SESSION_INFO_COUNTER = 1 // signatures.proto:88
    private const val SESSION_INFO_PUBLIC_KEY = 2 // signatures.proto:89
    private const val SESSION_INFO_EPOCH = 3 // signatures.proto:90
    private const val SESSION_INFO_CLOCK_TIME = 4 // signatures.proto:91 (fixed32)
    private const val SESSION_INFO_STATUS = 5 // signatures.proto:92

    /** Extracts and decodes the embedded `SessionInfo` from a `RoutableMessage`. */
    fun parseSessionInfo(routableMessageBytes: ByteArray): SessionInfo {
        var sessionInfoBytes: ByteArray? = null
        ProtoReader(routableMessageBytes).forEachField { field, _, value ->
            if (field == PAYLOAD_SESSION_INFO) {
                sessionInfoBytes = value as ByteArray
            }
        }
        val bytes = sessionInfoBytes
            ?: error("RoutableMessage has no session_info payload (field $PAYLOAD_SESSION_INFO)")

        var counter = 0L
        var publicKey = ByteArray(0)
        var epoch = ByteArray(0)
        var clockTime = 0
        var status = 0

        ProtoReader(bytes).forEachField { field, _, value ->
            when (field) {
                SESSION_INFO_COUNTER -> counter = value as Long
                SESSION_INFO_PUBLIC_KEY -> publicKey = value as ByteArray
                SESSION_INFO_EPOCH -> epoch = value as ByteArray
                SESSION_INFO_CLOCK_TIME -> clockTime = (value as Long).toInt()
                SESSION_INFO_STATUS -> status = (value as Long).toInt()
            }
        }

        return SessionInfo(
            counter = counter,
            publicKey = publicKey,
            epoch = epoch,
            clockTime = clockTime,
            status = status,
        )
    }
}

/** Dispatches a command name to its plaintext application-proto encoder. */
object CommandEncoders {
    fun encode(name: String): ByteArray = when (name) {
        "lock" -> VcsecMessages.rkeAction(VcsecMessages.RKE_ACTION_LOCK)
        "unlock" -> VcsecMessages.rkeAction(VcsecMessages.RKE_ACTION_UNLOCK)
        "charge_start" -> CarServerActions.chargeStart()
        "charge_stop" -> CarServerActions.chargeStop()
        "set_charge_limit_80" -> CarServerActions.setChargeLimit(80)
        "set_charge_amps_16" -> CarServerActions.setChargingAmps(16)
        "climate_on" -> CarServerActions.climate(powerOn = true)
        "climate_off" -> CarServerActions.climate(powerOn = false)
        "set_temp_21_5" -> CarServerActions.setTemperature(21.5f, 21.5f)
        "overheat_off" -> CarServerActions.setCabinOverheatProtection(on = false, fanOnly = false)
        "overheat_no_ac" -> CarServerActions.setCabinOverheatProtection(on = true, fanOnly = true)
        "overheat_ac" -> CarServerActions.setCabinOverheatProtection(on = true, fanOnly = false)
        "dog_mode_on" -> CarServerActions.dogMode(CarServerActions.CLIMATE_KEEPER_ACTION_DOG)
        "dog_mode_off" -> CarServerActions.dogMode(CarServerActions.CLIMATE_KEEPER_ACTION_OFF)
        "vent_windows" -> CarServerActions.ventWindows()
        "close_windows" -> CarServerActions.closeWindows()
        else -> error("unknown command: $name")
    }
}
