package com.amolpurohit.tesla.vcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

@Serializable
private data class SignerCommandFixture(
    val name: String,
    val domain: String,
    val expires_at: Int,
    val counter: Int,
    val epoch_b64: String,
    val metadata_b64: String,
    val plaintext_action_b64: String,
    val tag_b64: String,
    val routable_message_b64: String,
)

@Serializable
private data class SignerSessionInfoParsed(
    val epoch_b64: String,
    val clock_time: Int,
    val counter: Int,
    val vehicle_public_b64: String,
    val status: String,
)

@Serializable
private data class SignerSessionInfoResponseFixture(
    val description: String,
    val domain: String,
    val challenge_uuid_b64: String,
    val routable_message_b64: String,
    val session_info_tag_b64: String,
    val parsed: SignerSessionInfoParsed,
)

@Serializable
private data class SignerKeysFixture(
    val client_private_pkcs8_b64: String,
    val client_public_b64: String,
    val vehicle_private_pkcs8_b64: String,
    val vehicle_public_b64: String,
    val ecdh_shared_secret_x_b64: String,
    val session_key_b64: String,
)

@Serializable
private data class SignerFaultResponseFixture(
    val description: String,
    val routable_message_b64: String,
    val fault: String,
    val fault_codes: Map<String, Int>,
)

private val json = Json { ignoreUnknownKeys = true }

class CommandSignerTest {

    private fun b64(s: String): ByteArray = Base64.getDecoder().decode(s)

    private fun commandsFixture(): List<SignerCommandFixture> {
        val text = ClassLoader.getSystemResource("vcp/commands.json")?.readText()
            ?: error("could not load vcp/commands.json from test resources")
        return json.decodeFromString(text)
    }

    private fun keysFixture(): SignerKeysFixture {
        val text = ClassLoader.getSystemResource("vcp/keys.json")?.readText()
            ?: error("could not load vcp/keys.json from test resources")
        return json.decodeFromString(text)
    }

    private fun sessionInfoResponseFixture(): SignerSessionInfoResponseFixture {
        val text = ClassLoader.getSystemResource("vcp/session_info_response.json")?.readText()
            ?: error("could not load vcp/session_info_response.json from test resources")
        return json.decodeFromString(text)
    }

    private fun faultResponseFixture(): SignerFaultResponseFixture {
        val text = ClassLoader.getSystemResource("vcp/fault_response.json")?.readText()
            ?: error("could not load vcp/fault_response.json from test resources")
        return json.decodeFromString(text)
    }

    // Fixed test inputs, from scripts/tesla/vcp-fixtures/README.md "Fixed test inputs".
    private val vin = "5YJ3E1EA1PF000001"
    private val clientRoutingAddress = byteArrayOf(
        0x2c, 0x90.toByte(), 0x7b, 0xd7.toByte(), 0x6c, 0x64, 0x0d, 0x36,
        0x0b, 0x30, 0x27, 0xdc.toByte(), 0x74, 0x04, 0xef.toByte(), 0xde.toByte(),
    )
    private val commandUuid = byteArrayOf(
        0x58, 0x40, 0x65.toByte(), 0x80.toByte(), 0x52, 0x8b.toByte(), 0x6a, 0x53,
        0x01, 0x39, 0x18, 0x00, 0xb4.toByte(), 0xfe.toByte(), 0x9b.toByte(), 0x99.toByte(),
    )

    private fun domainOf(name: String): VcpDomain = when (name) {
        "VCSEC" -> VcpDomain.VEHICLE_SECURITY
        "Infotainment" -> VcpDomain.INFOTAINMENT
        else -> error("unknown domain: $name")
    }

    private fun sessionFor(fixture: SignerCommandFixture): Session {
        val keys = keysFixture()
        val clientPrivate = b64(keys.client_private_pkcs8_b64)
        val vehiclePublic = b64(keys.vehicle_public_b64)
        val sharedX = VcpCrypto.ecdhSharedSecretX(clientPrivate, vehiclePublic)
        val sessionKey = VcpCrypto.sessionKey(sharedX)
        return Session(
            vin = vin,
            clientPublicKey = b64(keys.client_public_b64),
            fromRoutingAddress = clientRoutingAddress,
            sessionKey = sessionKey,
            epoch = b64(fixture.epoch_b64),
            baseCounter = 0,
            clockOffset = 0,
        )
    }

    @Test
    fun `metadata TLV matches fixture for all 16 commands`() {
        val fixtures = commandsFixture()
        assertEquals(16, fixtures.size, "expected 16 command fixtures")
        for (fixture in fixtures) {
            val session = sessionFor(fixture)
            val metadata = CommandSigner.buildMetadata(
                domain = domainOf(fixture.domain),
                vin = session.vin,
                epoch = session.epoch,
                expiresAt = fixture.expires_at,
                counter = fixture.counter,
            )
            assertContentEquals(
                b64(fixture.metadata_b64),
                metadata,
                "command '${fixture.name}' metadata mismatch",
            )
        }
    }

    @Test
    fun `hmac tag matches fixture for all 16 commands`() {
        val fixtures = commandsFixture()
        assertEquals(16, fixtures.size, "expected 16 command fixtures")
        for (fixture in fixtures) {
            val session = sessionFor(fixture)
            val plaintextAction = b64(fixture.plaintext_action_b64)
            val metadata = CommandSigner.buildMetadata(
                domain = domainOf(fixture.domain),
                vin = session.vin,
                epoch = session.epoch,
                expiresAt = fixture.expires_at,
                counter = fixture.counter,
            )
            val commandKey = VcpCrypto.subkey(session.sessionKey, VcpCrypto.LABEL_AUTHENTICATED_COMMAND)
            val tag = VcpCrypto.hmacSha256(commandKey, metadata + plaintextAction)
            assertContentEquals(
                b64(fixture.tag_b64),
                tag,
                "command '${fixture.name}' tag mismatch",
            )
        }
    }

    @Test
    fun `signed routable message matches fixture byte-for-byte for all 16 commands`() {
        val fixtures = commandsFixture()
        assertEquals(16, fixtures.size, "expected 16 command fixtures")
        for (fixture in fixtures) {
            val session = sessionFor(fixture)
            val plaintextAction = b64(fixture.plaintext_action_b64)
            val signed = CommandSigner.sign(
                session = session,
                domain = domainOf(fixture.domain),
                plaintextAction = plaintextAction,
                counter = fixture.counter.toLong(),
                expiresAt = fixture.expires_at,
                uuid = commandUuid,
            )
            assertContentEquals(
                b64(fixture.routable_message_b64),
                signed,
                "command '${fixture.name}' routable_message mismatch",
            )
        }
    }

    @Test
    fun `session constructs from parsed session-info response and client key`() {
        val keys = keysFixture()
        val clientPrivate = b64(keys.client_private_pkcs8_b64)
        val infoFixture = sessionInfoResponseFixture()
        val sessionInfo = SessionInfoResponses.parseSessionInfo(b64(infoFixture.routable_message_b64))

        val sharedX = VcpCrypto.ecdhSharedSecretX(clientPrivate, sessionInfo.publicKey)
        val sessionKey = VcpCrypto.sessionKey(sharedX)

        val session = Session(
            vin = vin,
            clientPublicKey = b64(keys.client_public_b64),
            fromRoutingAddress = clientRoutingAddress,
            sessionKey = sessionKey,
            epoch = sessionInfo.epoch,
            baseCounter = sessionInfo.counter,
            clockOffset = sessionInfo.clockTime,
        )

        assertContentEquals(b64(keys.session_key_b64), session.sessionKey)
        assertContentEquals(b64(infoFixture.parsed.epoch_b64), session.epoch)
    }

    @Test
    fun `nextCounter is monotonic`() {
        val fixture = commandsFixture().first()
        val session = sessionFor(fixture).copy(baseCounter = 5)

        val c1 = session.nextCounter()
        val c2 = session.nextCounter()
        val c3 = session.nextCounter()

        assertTrue(c1 < c2, "counter must increase: $c1 !< $c2")
        assertTrue(c2 < c3, "counter must increase: $c2 !< $c3")
    }

    @Test
    fun `isFault detects fault in fault_response fixture`() {
        val fixture = faultResponseFixture()
        val bytes = b64(fixture.routable_message_b64)

        assertTrue(CommandSigner.isFault(bytes))
    }

    @Test
    fun `isFault is false for session_info_response fixture (no fault)`() {
        val fixture = sessionInfoResponseFixture()
        val bytes = b64(fixture.routable_message_b64)

        assertFalse(CommandSigner.isFault(bytes))
    }

    @Test
    fun `sign rejects a counter beyond uint32 range`() {
        val fixture = commandsFixture().first()
        val session = sessionFor(fixture)
        assertFailsWith<IllegalArgumentException> {
            CommandSigner.sign(
                session = session,
                domain = domainOf(fixture.domain),
                plaintextAction = b64(fixture.plaintext_action_b64),
                counter = 0x1_0000_0000L, // 2^32 — one past uint32 max
                expiresAt = fixture.expires_at,
                uuid = commandUuid,
            )
        }
    }
}
