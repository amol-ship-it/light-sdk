package com.amolpurohit.tesla.fleet

import com.amolpurohit.tesla.auth.AuthExpiredException
import com.amolpurohit.tesla.vehicle.ChargingState
import com.amolpurohit.tesla.vehicle.OverheatProtectionMode
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FleetClientTest {

    private class CountingFakeTokenSource(
        private val tokenSequence: List<String> = listOf("token1"),
    ) : TokenSource {
        var bearerCalls = 0
            private set
        var invalidateCalls = 0
            private set

        override suspend fun bearer(): String {
            if (invalidateCalls >= tokenSequence.size) {
                throw AuthExpiredException("no more tokens")
            }
            bearerCalls++
            return tokenSequence[invalidateCalls]
        }

        override suspend fun invalidate() {
            invalidateCalls++
        }
    }

    private fun vehicleDataFixture(): String =
        ClassLoader.getSystemResource("fleet/vehicle_data.json")!!.readText()

    @Test
    fun `listVehicles parses id, vin, display_name, state`() = runTest {
        val json = """
            {"response": [
                {"id_s": "111", "vin": "VIN111", "display_name": "Red Car", "state": "online"},
                {"id_s": "222", "vin": "VIN222", "display_name": "Blue Car", "state": "asleep"}
            ]}
        """.trimIndent()
        val engine = MockEngine { _ ->
            respond(json, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = FleetClient(engine, CountingFakeTokenSource(), "na")

        val vehicles = client.listVehicles()

        assertEquals(2, vehicles.size)
        assertEquals(VehicleSummary("111", "VIN111", "Red Car", "online"), vehicles[0])
        assertEquals(VehicleSummary("222", "VIN222", "Blue Car", "asleep"), vehicles[1])
    }

    @Test
    fun `vehicleData maps fixture to VehicleState`() = runTest {
        val body = vehicleDataFixture()
        val engine = MockEngine { _ ->
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = FleetClient(engine, CountingFakeTokenSource(), "na")

        val state = client.vehicleData("1234567890")

        assertEquals(72, state.batteryPercent)
        assertTrue(kotlin.math.abs(state.rangeKm - 340.4) < 0.1, "rangeKm was ${state.rangeKm}")
        assertEquals(ChargingState.Stopped, state.chargingState)
        assertTrue(state.pluggedIn)
        assertEquals(80, state.chargeLimitPercent)
        assertEquals(16, state.chargeAmps)
        assertEquals(32, state.maxChargeAmps)
        assertEquals(24.5, state.insideTempC)
        assertEquals(false, state.climateOn)
        assertEquals(21.0, state.targetTempC)
        assertEquals(15.0, state.minTargetTempC)
        assertEquals(28.0, state.maxTargetTempC)
        assertEquals(OverheatProtectionMode.Ac, state.overheatProtection)
        assertEquals(false, state.dogModeOn)
        assertTrue(state.locked)
        assertEquals(false, state.windowsOpen)
        assertEquals(false, state.asleep)
    }

    @Test
    fun `windowsOpen true when any window nonzero`() = runTest {
        val json = """
            {"response": {
                "state": "online",
                "charge_state": {"battery_level": 50, "battery_range": 100.0, "charging_state": "Disconnected", "charge_limit_soc": 80, "charge_amps": 0, "charge_current_request_max": 32},
                "climate_state": {"inside_temp": 20.0, "is_climate_on": false, "driver_temp_setting": 21.0, "min_avail_temp": 15.0, "max_avail_temp": 28.0, "cabin_overheat_protection": "Off", "climate_keeper_mode": "off"},
                "vehicle_state": {"locked": true, "fd_window": 1, "fp_window": 0, "rd_window": 0, "rp_window": 0}
            }}
        """.trimIndent()
        val engine = MockEngine { _ ->
            respond(json, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = FleetClient(engine, CountingFakeTokenSource(), "na")

        val state = client.vehicleData("1234567890")

        assertTrue(state.windowsOpen)
    }

    @Test
    fun `sends bearer header from TokenManager`() = runTest {
        var capturedAuth: String? = null
        val engine = MockEngine { request ->
            capturedAuth = request.headers[HttpHeaders.Authorization]
            respond(
                """{"response": []}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = FleetClient(engine, CountingFakeTokenSource(listOf("secret-token")), "na")

        client.listVehicles()

        assertEquals("Bearer secret-token", capturedAuth)
    }

    @Test
    fun `401 triggers invalidate and single retry`() = runTest {
        val hitCount = AtomicInteger(0)
        val engine = MockEngine { _ ->
            val hit = hitCount.incrementAndGet()
            if (hit == 1) {
                respond("unauthorized", HttpStatusCode.Unauthorized, headersOf(HttpHeaders.ContentType, "text/plain"))
            } else {
                respond(
                    """{"response": []}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val tokenSource = CountingFakeTokenSource(listOf("token1", "token2"))
        val client = FleetClient(engine, tokenSource, "na")

        val vehicles = client.listVehicles()

        assertEquals(2, hitCount.get())
        assertEquals(emptyList(), vehicles)
        assertEquals(1, tokenSource.invalidateCalls)
    }

    @Test
    fun `second consecutive 401 throws AuthExpiredException`() = runTest {
        val engine = MockEngine { _ ->
            respond("unauthorized", HttpStatusCode.Unauthorized, headersOf(HttpHeaders.ContentType, "text/plain"))
        }
        // Only one token available; after the first 401 -> invalidate(), the
        // fake's bearer() has exhausted its sequence and throws AuthExpiredException,
        // exactly like a real TokenManager whose refresh token was rejected.
        val tokenSource = CountingFakeTokenSource(listOf("token1"))
        val client = FleetClient(engine, tokenSource, "na")

        assertFailsWith<AuthExpiredException> {
            client.listVehicles()
        }
    }

    @Test
    fun `408 maps to VehicleAsleepException`() = runTest {
        val engine = MockEngine { _ ->
            respond("asleep", HttpStatusCode.RequestTimeout, headersOf(HttpHeaders.ContentType, "text/plain"))
        }
        val client = FleetClient(engine, CountingFakeTokenSource(), "na")

        assertFailsWith<VehicleAsleepException> {
            client.vehicleData("123")
        }
    }

    @Test
    fun `429 maps to RateLimitedException`() = runTest {
        val engine = MockEngine { _ ->
            respond("too many requests", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.ContentType, "text/plain"))
        }
        val client = FleetClient(engine, CountingFakeTokenSource(), "na")

        assertFailsWith<RateLimitedException> {
            client.listVehicles()
        }
    }

    @Test
    fun `IOException maps to FleetOfflineException`() = runTest {
        val engine = MockEngine { _ ->
            throw java.io.IOException("connection reset")
        }
        val client = FleetClient(engine, CountingFakeTokenSource(), "na")

        assertFailsWith<FleetOfflineException> {
            client.listVehicles()
        }
    }

    @Test
    fun `other 4xx5xx maps to FleetHttpException`() = runTest {
        val engine = MockEngine { _ ->
            respondError(HttpStatusCode.InternalServerError, "boom")
        }
        val client = FleetClient(engine, CountingFakeTokenSource(), "na")

        val e = assertFailsWith<FleetHttpException> {
            client.listVehicles()
        }
        assertEquals(500, e.code)
        assertTrue(e.briefBody.length <= 200)
    }

    @Test
    fun `signedCommand posts routable_message and returns response b64`() = runTest {
        var capturedRequest: HttpRequestData? = null
        val engine = MockEngine { request ->
            capturedRequest = request
            respond(
                // The real Fleet API returns the vehicle reply as a base64 STRING
                // directly in the envelope: {"response": "<b64>"} — NOT a nested
                // object. (Verified against the real vehicle; the earlier nested
                // shape here masked a JsonConvertException on every live command.)
                """{"response": "base64payload=="}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = FleetClient(engine, CountingFakeTokenSource(), "na")

        val result = client.signedCommand("123", "b64message==")

        assertEquals("base64payload==", result.responseB64)
        val sentRequest = capturedRequest ?: error("no request captured")
        assertTrue(sentRequest.url.toString().endsWith("/api/1/vehicles/123/signed_command"))
        val bodyText = (sentRequest.body as io.ktor.http.content.TextContent).text
        assertTrue(bodyText.contains("\"routable_message\":\"b64message==\""), "body was: $bodyText")
    }

    @Test
    fun `pluggedIn true when cable null but latch engaged`() = runTest {
        // Exercises the corroborating-evidence branch of the pluggedIn
        // heuristic: no conn_charge_cable, but charge_port_latch "Engaged".
        val json = """
            {"response": {
                "state": "online",
                "charge_state": {"battery_level": 50, "battery_range": 100.0, "charging_state": "Stopped", "charge_limit_soc": 80, "charge_amps": 16, "charge_current_request_max": 32, "charge_port_latch": "Engaged"},
                "climate_state": {"inside_temp": 20.0, "is_climate_on": false, "driver_temp_setting": 21.0, "min_avail_temp": 15.0, "max_avail_temp": 28.0, "cabin_overheat_protection": "Off", "climate_keeper_mode": "off"},
                "vehicle_state": {"locked": true, "fd_window": 0, "fp_window": 0, "rd_window": 0, "rp_window": 0}
            }}
        """.trimIndent()
        val engine = MockEngine { _ ->
            respond(json, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = FleetClient(engine, CountingFakeTokenSource(), "na")

        val state = client.vehicleData("1234567890")

        assertTrue(state.pluggedIn)
    }

    @Test
    fun `missing charge_state block throws FleetPartialDataException`() = runTest {
        // Degraded response: 200 with climate/vehicle blocks but no
        // charge_state. Must NOT map to a zero-defaulted VehicleState.
        val json = """
            {"response": {
                "state": "online",
                "climate_state": {"inside_temp": 20.0, "is_climate_on": false, "driver_temp_setting": 21.0, "min_avail_temp": 15.0, "max_avail_temp": 28.0, "cabin_overheat_protection": "Off", "climate_keeper_mode": "off"},
                "vehicle_state": {"locked": true, "fd_window": 0, "fp_window": 0, "rd_window": 0, "rp_window": 0}
            }}
        """.trimIndent()
        val engine = MockEngine { _ ->
            respond(json, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = FleetClient(engine, CountingFakeTokenSource(), "na")

        val e = assertFailsWith<FleetPartialDataException> {
            client.vehicleData("1234567890")
        }
        assertEquals("charge_state", e.missing)
    }

    @Test
    fun `vehicleSummary returns matching entry from list`() = runTest {
        val json = """
            {"response": [
                {"id_s": "111", "vin": "VIN111", "display_name": "Red Car", "state": "online"}
            ]}
        """.trimIndent()
        val engine = MockEngine { _ ->
            respond(json, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = FleetClient(engine, CountingFakeTokenSource(), "na")

        val summary = client.vehicleSummary("111")
        val missing = client.vehicleSummary("999")

        assertEquals("Red Car", summary?.name)
        assertNull(missing)
    }
}
