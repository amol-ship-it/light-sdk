package com.amolpurohit.tesla.auth

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Thrown when the refresh token itself is no longer usable (Tesla's token
 * endpoint rejected the refresh grant, e.g. `invalid_grant`). Distinct from
 * transient/5xx failures, which are propagated as-is so callers can treat
 * them as offline rather than requiring re-auth.
 */
class AuthExpiredException(message: String) : Exception(message)

@Serializable
private data class TokenResponse(
    val access_token: String,
    val refresh_token: String,
    val expires_in: Long,
)

/**
 * Lazily exchanges the stored refresh token for a bearer access token,
 * caching it until shortly before expiry.
 *
 * Tesla rotates the refresh token on every use: the response to a refresh
 * grant carries a NEW refresh token, and the one just used becomes invalid.
 * To avoid permanently stranding the user (crash between "old token consumed"
 * and "new token saved"), the entire refresh sequence — load credentials,
 * perform the HTTP exchange, persist the rotated refresh token, THEN cache
 * and return the new access token — runs under a single mutex, and the
 * persist step strictly precedes returning the access token. If the persist
 * throws, bearer() propagates that failure rather than returning a token
 * backed by an unpersisted rotation.
 */
class TokenManager(
    private val client: HttpClient,
    private val credentials: CredentialStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private companion object {
        private const val TOKEN_URL = "https://auth.tesla.com/oauth2/v3/token"
        private const val EARLY_REFRESH_MS = 60_000L
        private val jsonCodec = Json { ignoreUnknownKeys = true }
    }

    private val mutex = Mutex()
    private var accessToken: String? = null
    private var expiresAtMs: Long = 0L

    suspend fun bearer(): String = mutex.withLock {
        val cached = accessToken
        if (cached != null && !isExpiredLocked()) {
            return@withLock cached
        }
        refreshLocked()
    }

    suspend fun invalidate() = mutex.withLock {
        accessToken = null
        expiresAtMs = 0L
    }

    private fun isExpiredLocked(): Boolean = expiresAtMs - EARLY_REFRESH_MS <= nowMs()

    private suspend fun refreshLocked(): String {
        val payload = credentials.load() ?: throw AuthExpiredException("no stored credentials")

        val formParameters = Parameters.build {
            append("grant_type", "refresh_token")
            append("client_id", payload.clientId)
            append("refresh_token", payload.refreshToken)
            if (payload.clientSecret != null) {
                append("client_secret", payload.clientSecret)
            }
        }

        val response = client.post(TOKEN_URL) {
            setBody(FormDataContent(formParameters))
        }

        if (response.status.value in 400..499) {
            throw AuthExpiredException("refresh rejected: HTTP ${response.status.value}")
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("token refresh HTTP ${response.status.value}: ${response.bodyAsText().take(500)}")
        }

        val tokenResponse = try {
            jsonCodec.decodeFromString(TokenResponse.serializer(), response.bodyAsText())
        } catch (e: SerializationException) {
            // Same "never log raw" convention as SetupPayload: SerializationException
            // messages embed the full JSON input — access token and rotated refresh
            // token verbatim. Rethrow with a static message and NO cause/body/message
            // interpolation so nothing secret can reach logs or crash reporters.
            throw IllegalStateException("token refresh returned malformed JSON")
        }

        // Persist the rotated refresh token BEFORE caching/returning the new
        // access token. If this throws, propagate — do not hand back a token
        // whose rotation wasn't durably saved.
        credentials.updateRefreshToken(tokenResponse.refresh_token)

        accessToken = tokenResponse.access_token
        expiresAtMs = nowMs() + tokenResponse.expires_in * 1000
        return tokenResponse.access_token
    }
}
