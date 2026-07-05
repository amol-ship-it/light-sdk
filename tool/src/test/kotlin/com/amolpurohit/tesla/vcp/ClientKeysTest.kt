package com.amolpurohit.tesla.vcp

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class ClientKeysTest {

    // Fresh P-256 key, generated with:
    //   openssl ecparam -genkey -name prime256v1 -noout | openssl pkcs8 -topk8 -nocrypt
    // (a throwaway key, never used against any real vehicle). Its expected public point
    // is independently confirmed via `openssl ec -text -noout` during investigation for
    // this task; see ClientKeys.fromPem's kdoc.
    private val pkcs8Pem = """
        -----BEGIN PRIVATE KEY-----
        MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgJOakS3oBKw8BNAH3
        VYC8o6j4PWAKWWhnwMhYBK3odl+hRANCAASMhX6VnGQdRxwzfLm5TvuOISp/8EC3
        ovgnVgY7RZC54kCdjG/rrGG0zH2xzmrfXtN1HREUnyRRmoXITcPjCAHy
        -----END PRIVATE KEY-----
    """.trimIndent()

    private val expectedPublicUncompressedB64 =
        "BIyFfpWcZB1HHDN8ublO+44hKn/wQLei+CdWBjtFkLniQJ2Mb+usYbTMfbHOat9e03UdERSfJFGahchNw+MIAfI="

    @Test
    fun `fromPem derives the public point embedded in the PKCS8 DER`() {
        val keys = ClientKeys.fromPem(pkcs8Pem)

        assertContentEquals(
            Base64.getDecoder().decode(expectedPublicUncompressedB64),
            keys.publicUncompressed,
        )
        assertEqualsUncompressedPointShape(keys.publicUncompressed)
    }

    @Test
    fun `fromPem-derived private key material works with VcpCrypto ECDH`() {
        // Round-trip sanity: the private key bytes fromPem() extracts must still be usable
        // by VcpCrypto's ECDH, against an arbitrary peer public key (the fixture's vehicle key).
        val vehiclePublic = Base64.getDecoder().decode(
            "BG07Z7ABJmOcI6aDWbHxzfXOj7F7Vyd++Pb3e5rgnQr0I5RgC7/enO3nd3NycmUQoFm55nh8Bs1fygtaz6aPxMw=",
        )
        val keys = ClientKeys.fromPem(pkcs8Pem)

        // The crypto correctness itself is VcpCrypto's job (VcpCryptoTest/CommandSignerTest);
        // here we only confirm the extracted PKCS8 bytes are accepted and produce a
        // well-shaped 32-byte X coordinate.
        val sharedX = VcpCrypto.ecdhSharedSecretX(keys.privatePkcs8, vehiclePublic)
        kotlin.test.assertEquals(32, sharedX.size)
    }

    @Test
    fun `fromPem rejects malformed PEM`() {
        assertFailsWith<IllegalArgumentException> {
            ClientKeys.fromPem("-----BEGIN PRIVATE KEY-----\n-----END PRIVATE KEY-----")
        }
    }

    private fun assertEqualsUncompressedPointShape(point: ByteArray) {
        kotlin.test.assertEquals(65, point.size)
        kotlin.test.assertEquals(0x04, point[0].toInt() and 0xFF)
    }
}
