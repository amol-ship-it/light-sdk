package com.amolpurohit.tesla.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SetupPayloadTest {
    private fun encode(json: String): String {   // test helper mirrors the login script
        val deflated = java.util.zip.Deflater(9, /*nowrap=*/true).let { d ->
            d.setInput(json.toByteArray()); d.finish()
            val buf = ByteArray(json.length * 2 + 64)
            val n = d.deflate(buf); buf.copyOf(n)
        }
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(deflated)
    }

    @Test fun `single QR decodes`() {
        val json = """{"v":1,"refresh_token":"rt","client_id":"cid","region":"na","private_key":"pk"}"""
        val p = SetupPayload.fromScans(listOf(encode(json)))
        assertEquals(SetupPayload.Complete(payload = SetupPayload("rt", "cid", null, "na", "pk")), p)
    }

    @Test fun `multi-part assembles in any order`() {
        val body = encode("""{"v":1,"refresh_token":"rt","client_id":"cid","region":"na","private_key":"pk"}""")
        val a = "LTP/1/2/" + body.substring(0, body.length / 2)
        val b = "LTP/2/2/" + body.substring(body.length / 2)
        assertIs<SetupPayload.NeedMore>(SetupPayload.fromScans(listOf(b)))
        assertIs<SetupPayload.Complete>(SetupPayload.fromScans(listOf(b, a)))
    }

    @Test fun `unsupported version rejected`() {
        val p = SetupPayload.fromScans(listOf(encode("""{"v":99,"refresh_token":"x","client_id":"y","region":"na","private_key":"z"}""")))
        assertIs<SetupPayload.Invalid>(p)
    }

    @Test fun `garbage rejected as Invalid`() {
        assertIs<SetupPayload.Invalid>(SetupPayload.fromScans(listOf("not-a-payload")))
    }
}
