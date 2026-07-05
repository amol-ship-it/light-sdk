package com.amolpurohit.tesla.vcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@Serializable
private data class GcmVectorFixture(
    val key_b64: String,
    val nonce_b64: String,
    val aad_b64: String,
    val plaintext_b64: String,
    val ciphertext_b64: String,
    val tag_b64: String,
)

@Serializable
private data class KeysFixture(
    val client_private_pkcs8_b64: String,
    val client_public_b64: String,
    val vehicle_private_pkcs8_b64: String,
    val vehicle_public_b64: String,
    val ecdh_shared_secret_x_b64: String,
    val session_key_b64: String,
    val gcm_vector: GcmVectorFixture,
)

private val json = Json { ignoreUnknownKeys = true }

class VcpCryptoTest {

    private fun keysFixture(): KeysFixture {
        val text = ClassLoader.getSystemResource("vcp/keys.json")?.readText()
            ?: error("could not load vcp/keys.json from test resources")
        return json.decodeFromString(KeysFixture.serializer(), text)
    }

    private fun b64(s: String): ByteArray = Base64.getDecoder().decode(s)

    @Test
    fun `ecdh shared secret matches fixture`() {
        val fixture = keysFixture()
        val clientPrivate = b64(fixture.client_private_pkcs8_b64)
        val vehiclePublic = b64(fixture.vehicle_public_b64)
        val expectedX = b64(fixture.ecdh_shared_secret_x_b64)

        val sharedX = VcpCrypto.ecdhSharedSecretX(clientPrivate, vehiclePublic)

        assertEquals(32, sharedX.size)
        assertContentEquals(expectedX, sharedX)
    }

    @Test
    fun `session key derivation matches fixture`() {
        val fixture = keysFixture()
        val sharedX = b64(fixture.ecdh_shared_secret_x_b64)
        val expectedSessionKey = b64(fixture.session_key_b64)

        val sessionKey = VcpCrypto.sessionKey(sharedX)

        assertEquals(16, sessionKey.size)
        assertContentEquals(expectedSessionKey, sessionKey)
    }

    @Test
    fun `subkey derivation is stable`() {
        val fixture = keysFixture()
        val sessionKey = b64(fixture.session_key_b64)

        val commandKey1 = VcpCrypto.subkey(sessionKey, VcpCrypto.LABEL_AUTHENTICATED_COMMAND)
        val commandKey2 = VcpCrypto.subkey(sessionKey, VcpCrypto.LABEL_AUTHENTICATED_COMMAND)
        val sessionInfoKey = VcpCrypto.subkey(sessionKey, VcpCrypto.LABEL_SESSION_INFO)

        assertEquals(32, commandKey1.size)
        assertEquals(32, sessionInfoKey.size)
        assertContentEquals(commandKey1, commandKey2)
        assertNotEquals(commandKey1.toList(), sessionInfoKey.toList())
    }

    @Test
    fun `hmac sha256 primitive`() {
        // RFC 4231 test case 2.
        val key = "Jefe".toByteArray(Charsets.US_ASCII)
        val data = "what do ya want for nothing?".toByteArray(Charsets.US_ASCII)
        val expected = "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"

        val mac = VcpCrypto.hmacSha256(key, data)

        assertEquals(expected, mac.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `aes-gcm known answer`() {
        val fixture = keysFixture().gcm_vector
        val key = b64(fixture.key_b64)
        val nonce = b64(fixture.nonce_b64)
        val aad = b64(fixture.aad_b64)
        val plaintext = b64(fixture.plaintext_b64)
        val expectedCiphertext = b64(fixture.ciphertext_b64)
        val expectedTag = b64(fixture.tag_b64)

        val (ciphertext, tag) = VcpCrypto.aesGcmEncrypt(key, nonce, aad, plaintext)

        assertContentEquals(expectedCiphertext, ciphertext)
        assertContentEquals(expectedTag, tag)
    }
}
