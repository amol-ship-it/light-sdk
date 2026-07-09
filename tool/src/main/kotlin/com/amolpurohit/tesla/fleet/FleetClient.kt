package com.amolpurohit.tesla.fleet

import com.amolpurohit.tesla.vehicle.VehicleState
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.io.IOException

/**
 * Narrow seam over TokenManager so FleetClient (and its tests) don't depend
 * on the concrete class. TokenManager implements this directly.
 *
 * bearer()/invalidate() both take TokenManager's internal, non-reentrant
 * mutex — FleetClient must never call either while holding a lock of its
 * own. FleetClient holds no locks, so this is satisfied trivially.
 */
interface TokenSource {
    suspend fun bearer(): String
    suspend fun invalidate()
}

interface FleetApi {
    suspend fun listVehicles(): List<VehicleSummary>
    suspend fun vehicleSummary(id: String): VehicleSummary?
    suspend fun vehicleData(id: String): VehicleState
    suspend fun wakeUp(id: String)
    suspend fun signedCommand(id: String, routableMessageB64: String): SignedCommandResponse
}

@Serializable
private data class RoutableMessageRequest(@kotlinx.serialization.SerialName("routable_message") val routableMessage: String)

class FleetClient(
    engine: HttpClientEngine,
    private val tokens: TokenSource,
    region: String,
) : FleetApi, Closeable {

    private companion object {
        private val jsonCodec = Json { ignoreUnknownKeys = true }

        private fun baseUrlFor(region: String): String = when (region) {
            "eu" -> "https://fleet-api.prd.eu.vn.cloud.tesla.com"
            else -> "https://fleet-api.prd.na.vn.cloud.tesla.com"
        }
    }

    private val baseUrl = baseUrlFor(region)

    private val client = HttpClient(engine) {
        install(ContentNegotiation) {
            json(jsonCodec)
        }
    }

    override suspend fun listVehicles(): List<VehicleSummary> {
        val envelope: FleetEnvelope<List<VehicleSummaryDto>> = authorizedGet("$baseUrl/api/1/vehicles")
        return envelope.response.orEmpty().map { it.toSummary() }
    }

    override suspend fun vehicleSummary(id: String): VehicleSummary? {
        // Deliberately re-fetches the vehicle LIST: it's the cheap/budget-safe
        // state poll per spec §4.4/§7 — do not "optimize" to vehicle_data.
        return listVehicles().firstOrNull { it.id == id }
    }

    override suspend fun vehicleData(id: String): VehicleState {
        // Scope the request to only the fields VehicleState needs: smaller
        // payload, and Fleet API bills/rate-limits per requested data scope.
        val envelope: FleetEnvelope<VehicleDataDto> = authorizedGet(
            "$baseUrl/api/1/vehicles/$id/vehicle_data",
        ) {
            parameter("endpoints", "charge_state;climate_state;vehicle_state")
        }
        val data = envelope.response ?: throw FleetPartialDataException("vehicle_data response envelope")
        return data.toVehicleState()
    }

    override suspend fun wakeUp(id: String) {
        executeWithAuthRetryRaw {
            client.post("$baseUrl/api/1/vehicles/$id/wake_up") {
                header("Authorization", "Bearer $it")
            }
        }
    }

    override suspend fun signedCommand(id: String, routableMessageB64: String): SignedCommandResponse {
        // The Fleet API returns the vehicle's reply as a base64 STRING directly in
        // the envelope: {"response": "<base64 routable_message>"} — NOT a nested
        // object. (Confirmed against the real vehicle; the earlier nested-object
        // DTO caused a JsonConvertException at $.response on every command.)
        val envelope: FleetEnvelope<String> = executeWithAuthRetry {
            client.post("$baseUrl/api/1/vehicles/$id/signed_command") {
                header("Authorization", "Bearer $it")
                contentType(ContentType.Application.Json)
                setBody(RoutableMessageRequest(routableMessageB64))
            }
        }
        val responseB64 = envelope.response
            ?: throw FleetPartialDataException("signed_command response envelope")
        return SignedCommandResponse(responseB64)
    }

    private suspend inline fun <reified T> authorizedGet(
        url: String,
        crossinline configure: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {},
    ): T {
        return executeWithAuthRetry {
            client.get(url) {
                header("Authorization", "Bearer $it")
                configure()
            }
        }
    }

    /** Same 401-retry policy as [executeWithAuthRetry], but discards the body — for endpoints like wake_up whose response we don't need to parse. */
    private suspend inline fun executeWithAuthRetryRaw(
        crossinline request: suspend (token: String) -> HttpResponse,
    ) {
        val response = executeAndFollowRetry(request)
        checkStatusOrThrow(response)
    }

    /**
     * Runs [request] with a fresh bearer token, retrying exactly once on a
     * 401: invalidate the cached token and try again with a new one. A
     * second consecutive 401 lets TokenManager's own AuthExpiredException
     * (thrown from invalidate()'s subsequent bearer() refresh) propagate —
     * here we just don't swallow whatever the retry raises.
     */
    private suspend inline fun <reified T> executeWithAuthRetry(
        crossinline request: suspend (token: String) -> HttpResponse,
    ): T {
        val response = executeAndFollowRetry(request)
        checkStatusOrThrow(response)
        return response.body()
    }

    private suspend inline fun executeAndFollowRetry(
        crossinline request: suspend (token: String) -> HttpResponse,
    ): HttpResponse {
        val response = try {
            request(tokens.bearer())
        } catch (e: IOException) {
            throw FleetOfflineException(e)
        }

        if (response.status != HttpStatusCode.Unauthorized) {
            return response
        }

        tokens.invalidate()
        return try {
            request(tokens.bearer())
        } catch (e: IOException) {
            throw FleetOfflineException(e)
        }
    }

    private suspend fun checkStatusOrThrow(response: HttpResponse) {
        when (response.status.value) {
            in 200..299 -> return
            408 -> throw VehicleAsleepException()
            429 -> throw RateLimitedException()
            else -> {
                val brief = response.bodyAsText().take(200)
                throw FleetHttpException(response.status.value, brief)
            }
        }
    }

    override fun close() {
        client.close()
    }
}

private fun VehicleSummaryDto.toSummary() = VehicleSummary(
    id = idS,
    vin = vin,
    name = displayName.orEmpty(),
    state = state.orEmpty(),
)
