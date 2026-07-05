package com.amolpurohit.tesla.vcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

@Serializable
private data class CommandFixture(
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
private data class SessionInfoRequestFixture(
    val description: String,
    val domain: String,
    val routable_message_b64: String,
)

@Serializable
private data class SessionInfoParsed(
    val epoch_b64: String,
    val clock_time: Int,
    val counter: Int,
    val vehicle_public_b64: String,
    val status: String,
)

@Serializable
private data class SessionInfoResponseFixture(
    val description: String,
    val domain: String,
    val challenge_uuid_b64: String,
    val routable_message_b64: String,
    val session_info_tag_b64: String,
    val parsed: SessionInfoParsed,
)

@Serializable
private data class ClientPublicKeyFixture(
    val client_public_b64: String,
)

private val json = Json { ignoreUnknownKeys = true }

class MessagesTest {

    private fun b64(s: String): ByteArray = Base64.getDecoder().decode(s)
    private fun b64Str(b: ByteArray): String = Base64.getEncoder().encodeToString(b)

    private fun commandsFixture(): List<CommandFixture> {
        val text = ClassLoader.getSystemResource("vcp/commands.json")?.readText()
            ?: error("could not load vcp/commands.json from test resources")
        return json.decodeFromString(text)
    }

    private fun sessionInfoRequestFixture(): List<SessionInfoRequestFixture> {
        val text = ClassLoader.getSystemResource("vcp/session_info_request.json")?.readText()
            ?: error("could not load vcp/session_info_request.json from test resources")
        return json.decodeFromString(text)
    }

    private fun sessionInfoResponseFixture(): SessionInfoResponseFixture {
        val text = ClassLoader.getSystemResource("vcp/session_info_response.json")?.readText()
            ?: error("could not load vcp/session_info_response.json from test resources")
        return json.decodeFromString(text)
    }

    private fun keysFixture(): ClientPublicKeyFixture {
        val text = ClassLoader.getSystemResource("vcp/keys.json")?.readText()
            ?: error("could not load vcp/keys.json from test resources")
        return json.decodeFromString(text)
    }

    // Fixed test inputs from scripts/tesla/vcp-fixtures/README.md "Fixed test inputs".
    private val clientRoutingAddress = byteArrayOf(
        0x2c, 0x90.toByte(), 0x7b, 0xd7.toByte(), 0x6c, 0x64, 0x0d, 0x36,
        0x0b, 0x30, 0x27, 0xdc.toByte(), 0x74, 0x04, 0xef.toByte(), 0xde.toByte(),
    )
    private val challengeUuid = byteArrayOf(
        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
    )

    @Test
    fun `each command's plaintext action matches fixture byte-for-byte`() {
        val fixtures = commandsFixture()
        assertEquals(16, fixtures.size, "expected 16 command fixtures")
        for (fixture in fixtures) {
            val expected = b64(fixture.plaintext_action_b64)
            val actual = CommandEncoders.encode(fixture.name)
            assertContentEquals(expected, actual, "command '${fixture.name}' plaintext_action mismatch")
        }
    }

    @Test
    fun `session info request envelope matches fixture for both domains`() {
        val keys = keysFixture()
        val clientPublicKey = b64(keys.client_public_b64)
        val fixtures = sessionInfoRequestFixture()
        assertEquals(2, fixtures.size, "expected 2 session_info_request fixtures")

        for (fixture in fixtures) {
            val domain = when (fixture.domain) {
                "VCSEC" -> VcpDomain.VEHICLE_SECURITY
                "Infotainment" -> VcpDomain.INFOTAINMENT
                else -> error("unknown domain: ${fixture.domain}")
            }
            val expected = b64(fixture.routable_message_b64)
            val actual = RoutableMessages.sessionInfoRequest(
                domain = domain,
                clientRoutingAddress = clientRoutingAddress,
                clientPublicKey = clientPublicKey,
                uuid = challengeUuid,
            )
            assertContentEquals(expected, actual, "session_info_request for ${fixture.domain} mismatch")
        }
    }

    @Test
    fun `session info response decodes epoch, clock_time, counter, vehicle_public, status`() {
        val fixture = sessionInfoResponseFixture()
        val routableMessage = b64(fixture.routable_message_b64)

        val sessionInfo = SessionInfoResponses.parseSessionInfo(routableMessage)

        assertContentEquals(b64(fixture.parsed.epoch_b64), sessionInfo.epoch)
        assertEquals(fixture.parsed.clock_time, sessionInfo.clockTime)
        assertEquals(fixture.parsed.counter.toLong(), sessionInfo.counter)
        assertEquals(b64Str(sessionInfo.publicKey), fixture.parsed.vehicle_public_b64)
        assertEquals(SessionInfoStatus.OK, sessionInfo.status)
        assertEquals("SESSION_INFO_STATUS_OK", fixture.parsed.status)
    }
}
