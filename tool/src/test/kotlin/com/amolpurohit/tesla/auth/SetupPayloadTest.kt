package com.amolpurohit.tesla.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

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

    @Test fun `scan over 8192 chars rejected`() {
        assertIs<SetupPayload.Invalid>(SetupPayload.fromScans(listOf("A".repeat(8193))))
    }

    @Test fun `decompression bomb rejected`() {
        // 4 MB of zeros deflates to a few KB of base64 (well under the scan cap);
        // the decoder must abort at its inflate output cap, not materialize the bomb.
        val bomb = encode("0".repeat(4 * 1024 * 1024))
        val startNs = System.nanoTime()
        val p = SetupPayload.fromScans(listOf(bomb))
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
        val invalid = assertIs<SetupPayload.Invalid>(p)
        assertEquals("payload too large", invalid.reason)
        assertTrue(elapsedMs < 2_000, "expected early abort, took ${elapsedMs}ms")
    }

    // Cross-implementation seam: this exact string is the captured stdout of
    // `python3 scripts/tesla/setup/login.py --selftest`, which encodes a fixed
    // canned payload (JSON -> raw deflate nowrap -> base64url no padding) with
    // no OAuth/network/QR involved. If login.py's encoder and this Kotlin
    // decoder ever disagree on the wire format, this test fails first.
    private val PYTHON_SELFTEST_ENCODED =
        "lY9Ba4NAEIX_SthzDC300kAOG50YiZpGTWlBkK0Z08V1167btKH0v3e1Sckhlz4YmJn3wZv5IgcyvR0TjZXG7rUwqkZJpqRDURnsjHMynMFwbqzImJSCozQF312Sv0vHLv" +
            "-ADkuN5gp0MvrcPVd9oGR22qmG8cv4CX6yphU4KVVj_VbzAzNY1Hi0kNNrDn4Qj8AdPSTBI81gtILnwchltCxdugEI5imEiwzSbEFXYP3IcklAw3idJUBDb22bbQpPgxRVvuu" +
            "--Wl0d59L-gFLtd14mz2FitXYvr8IXtr8xt6hOROV0udjlRRHqQzTyIQleryvXJ67_9ZslsvhF4i96y-S7x8"

    @Test fun `python login-py selftest encoding decodes end-to-end`() {
        val result = SetupPayload.fromScans(listOf(PYTHON_SELFTEST_ENCODED))
        val complete = assertIs<SetupPayload.Complete>(result)
        assertEquals(
            SetupPayload(
                refreshToken = "selftest-refresh-token-0000",
                clientId = "selftest-client-id",
                clientSecret = "selftest-client-secret",
                region = "na",
                privateKey = "-----BEGIN EC PRIVATE KEY-----\n" +
                    "MHcCAQEEIBSELFTESTFAKEKEYMATERIALNOTREALDONOTUSEXXXXXXoAoGCCqGSM49\n" +
                    "AwEHoUQDQgAEfakepublickeymaterialforselftestonlynotarealkeyfakefake\n" +
                    "fakefakefakefakefakefakefakefakefakefakefakefakefakefake==\n" +
                    "-----END EC PRIVATE KEY-----\n",
                domain = "selftest.example.com",
            ),
            complete.payload,
        )
    }
}
