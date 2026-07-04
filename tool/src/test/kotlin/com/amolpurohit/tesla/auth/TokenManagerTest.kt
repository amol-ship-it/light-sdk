package com.amolpurohit.tesla.auth

import com.amolpurohit.tesla.store.InMemoryKeyValueStore
import com.amolpurohit.tesla.store.KeyValueStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class TokenManagerTest {

    private fun seededPayload(clientSecret: String? = null) = SetupPayload(
        refreshToken = "rt1",
        clientId = "cid",
        clientSecret = clientSecret,
        region = "na",
        privateKey = "pk",
        domain = null,
        version = 1,
    )

    private suspend fun seededStore(clientSecret: String? = null): CredentialStore {
        val store = CredentialStore(InMemoryKeyValueStore())
        store.save(seededPayload(clientSecret))
        return store
    }

    @Test
    fun `bearer refreshes on first call and persists rotated refresh token`() = runTest {
        val requestBodies = mutableListOf<String>()
        val engine = MockEngine { request ->
            requestBodies += bodyText(request)
            respond(
                content = """{"access_token":"at1","refresh_token":"rt2","expires_in":28800}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val credentialStore = seededStore()
        val manager = TokenManager(HttpClient(engine), credentialStore)

        val token = manager.bearer()

        assertEquals("at1", token)
        assertEquals("rt2", credentialStore.load()!!.refreshToken)
        assertEquals(1, requestBodies.size)
        val body = requestBodies[0]
        assertTrue(body.contains("refresh_token=rt1"), "body was: $body")
        assertTrue(body.contains("grant_type=refresh_token"), "body was: $body")
        assertTrue(body.contains("client_id=cid"), "body was: $body")
    }

    @Test
    fun `client_secret included when present`() = runTest {
        val requestBodies = mutableListOf<String>()
        val engine = MockEngine { request ->
            requestBodies += bodyText(request)
            respond(
                content = """{"access_token":"at1","refresh_token":"rt2","expires_in":28800}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val credentialStore = seededStore(clientSecret = "cs1")
        val manager = TokenManager(HttpClient(engine), credentialStore)

        manager.bearer()

        assertTrue(requestBodies[0].contains("client_secret=cs1"), "body was: ${requestBodies[0]}")
    }

    @Test
    fun `no secret means body does NOT contain client_secret`() = runTest {
        val requestBodies = mutableListOf<String>()
        val engine = MockEngine { request ->
            requestBodies += bodyText(request)
            respond(
                content = """{"access_token":"at1","refresh_token":"rt2","expires_in":28800}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val credentialStore = seededStore(clientSecret = null)
        val manager = TokenManager(HttpClient(engine), credentialStore)

        manager.bearer()

        assertFalse(requestBodies[0].contains("client_secret"), "body was: ${requestBodies[0]}")
    }

    @Test
    fun `bearer caches until expiry`() = runTest {
        val hitCount = AtomicInteger(0)
        val engine = MockEngine { _ ->
            hitCount.incrementAndGet()
            respond(
                content = """{"access_token":"at1","refresh_token":"rt2","expires_in":28800}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val credentialStore = seededStore()
        val manager = TokenManager(HttpClient(engine), credentialStore)

        manager.bearer()
        manager.bearer()

        assertEquals(1, hitCount.get())
    }

    @Test
    fun `expired token re-refreshes`() = runTest {
        val hitCount = AtomicInteger(0)
        var currentTime = 0L
        val engine = MockEngine { _ ->
            hitCount.incrementAndGet()
            respond(
                content = """{"access_token":"at${hitCount.get()}","refresh_token":"rt${hitCount.get() + 1}","expires_in":100}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val credentialStore = seededStore()
        val manager = TokenManager(HttpClient(engine), credentialStore, nowMs = { currentTime })

        manager.bearer()
        assertEquals(1, hitCount.get())

        // early-refresh threshold is expiresAtMs - 60_000; expires_in=100s means
        // advancing past (100 - 60) = 40s should trigger a re-refresh.
        currentTime += 41_000

        manager.bearer()
        assertEquals(2, hitCount.get())
    }

    @Test
    fun `invalidate forces re-refresh`() = runTest {
        val hitCount = AtomicInteger(0)
        val engine = MockEngine { _ ->
            hitCount.incrementAndGet()
            respond(
                content = """{"access_token":"at1","refresh_token":"rt2","expires_in":28800}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val credentialStore = seededStore()
        val manager = TokenManager(HttpClient(engine), credentialStore)

        manager.bearer()
        assertEquals(1, hitCount.get())

        manager.invalidate()
        manager.bearer()
        assertEquals(2, hitCount.get())
    }

    @Test
    fun `refresh rejection maps to AuthExpiredException`() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = """{"error":"invalid_grant"}""",
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val credentialStore = seededStore()
        val manager = TokenManager(HttpClient(engine), credentialStore)

        assertFailsWith<AuthExpiredException> {
            manager.bearer()
        }
    }

    @Test
    fun `rotation persists before bearer returns`() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = """{"access_token":"at1","refresh_token":"rt2","expires_in":28800}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val credentialStore = seededStore()
        val manager = TokenManager(HttpClient(engine), credentialStore)

        val token = manager.bearer()

        assertEquals("at1", token)
        // The rotated refresh token must already be durably persisted by the
        // time bearer() returns — this is the core anti-strand invariant.
        assertEquals("rt2", credentialStore.load()!!.refreshToken)
    }

    @Test
    fun `if persist throws bearer propagates failure instead of returning access token`() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = """{"access_token":"at1","refresh_token":"rt2","expires_in":28800}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val innerStore = InMemoryKeyValueStore()
        val failingStore = object : KeyValueStore {
            override suspend fun get(key: String): String? = innerStore.get(key)
            override suspend fun put(key: String, value: String) {
                throw RuntimeException("persist failed")
            }
            override suspend fun remove(key: String) = innerStore.remove(key)
        }
        val credentialStore = CredentialStore(failingStore)
        // Seed via the real store first, then swap in the failing put.
        innerStore.put(
            "credentials",
            kotlinx.serialization.json.Json.encodeToString(SetupPayload.serializer(), seededPayload()),
        )
        val manager = TokenManager(HttpClient(engine), credentialStore)

        assertFailsWith<RuntimeException> {
            manager.bearer()
        }
    }

    @Test
    fun `malformed 200 throws sanitized error`() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = """{"access_token":"at1","refresh_token":"secretRT456","expires_in":""}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val credentialStore = seededStore()
        val manager = TokenManager(HttpClient(engine), credentialStore)

        val e = assertFailsWith<IllegalStateException> {
            manager.bearer()
        }
        val message = e.message.orEmpty()
        assertFalse(message.contains("secretRT456"), "message leaked refresh token: $message")
        assertFalse(message.contains("at1"), "message leaked access token: $message")
    }

    @Test
    fun `exactly 60s remaining counts as expired`() = runTest {
        val hitCount = AtomicInteger(0)
        var currentTime = 0L
        val engine = MockEngine { _ ->
            hitCount.incrementAndGet()
            respond(
                content = """{"access_token":"at${hitCount.get()}","refresh_token":"rt${hitCount.get() + 1}","expires_in":100}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val credentialStore = seededStore()
        val manager = TokenManager(HttpClient(engine), credentialStore, nowMs = { currentTime })

        manager.bearer()
        assertEquals(1, hitCount.get())

        // expiresAtMs = 100_000; threshold is expiresAtMs - 60_000 <= nowMs():
        // at exactly 40_000 (precisely 60s remaining) the token counts as expired.
        currentTime = 40_000

        manager.bearer()
        assertEquals(2, hitCount.get())
    }

    @Test
    fun `same refresh token round-trips when rotation disabled`() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = """{"access_token":"at1","refresh_token":"rt1","expires_in":28800}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val credentialStore = seededStore()
        val manager = TokenManager(HttpClient(engine), credentialStore)

        val token = manager.bearer()

        assertEquals("at1", token)
        assertEquals("rt1", credentialStore.load()!!.refreshToken)
    }

    @Test
    fun `concurrent bearer calls produce one refresh`() = runTest {
        val hitCount = AtomicInteger(0)
        val engine = MockEngine { _ ->
            hitCount.incrementAndGet()
            delay(10)
            respond(
                content = """{"access_token":"at1","refresh_token":"rt2","expires_in":28800}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val credentialStore = seededStore()
        val manager = TokenManager(HttpClient(engine), credentialStore)

        val results = listOf(
            async { manager.bearer() },
            async { manager.bearer() },
            async { manager.bearer() },
        ).awaitAll()

        assertEquals(1, hitCount.get())
        assertTrue(results.all { it == "at1" })
    }

    @Test
    fun `no credentials throws AuthExpiredException`() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = "",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val credentialStore = CredentialStore(InMemoryKeyValueStore())
        val manager = TokenManager(HttpClient(engine), credentialStore)

        assertFailsWith<AuthExpiredException> {
            manager.bearer()
        }
    }

    private fun bodyText(request: HttpRequestData): String {
        val body = request.body as? FormDataContent
            ?: error("expected FormDataContent, was ${request.body::class}")
        return String(body.bytes(), Charsets.UTF_8)
    }
}
