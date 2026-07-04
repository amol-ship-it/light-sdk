package com.amolpurohit.tesla.vehicle

import com.amolpurohit.tesla.auth.AuthExpiredException
import com.amolpurohit.tesla.fleet.FleetApi
import com.amolpurohit.tesla.fleet.FleetHttpException
import com.amolpurohit.tesla.fleet.FleetOfflineException
import com.amolpurohit.tesla.fleet.FleetPartialDataException
import com.amolpurohit.tesla.fleet.RateLimitedException
import com.amolpurohit.tesla.fleet.SignedCommandResponse
import com.amolpurohit.tesla.fleet.VehicleAsleepException
import com.amolpurohit.tesla.fleet.VehicleSummary
import com.amolpurohit.tesla.store.InMemoryKeyValueStore
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RealVehicleRepositoryTest {

    private val vehicleId = "v1"

    /**
     * Scriptable [FleetApi] fake. vehicleData responses/throws are consumed one at a time from
     * [vehicleDataScript]; vehicleSummary states are consumed one at a time from
     * [summaryStatesScript] (repeats the last entry once exhausted, so tests don't need to
     * over-provision). Every method increments its own call counter.
     */
    private class FakeFleetApi(
        private val vehicleDataScript: MutableList<() -> VehicleState> = mutableListOf(),
        private val summaryStatesScript: MutableList<String> = mutableListOf(),
    ) : FleetApi {
        var listVehiclesCount = 0
            private set
        var vehicleSummaryCount = 0
            private set
        var vehicleDataCount = 0
            private set
        var wakeUpCount = 0
            private set
        var signedCommandCount = 0
            private set

        override suspend fun listVehicles(): List<VehicleSummary> {
            listVehiclesCount++
            return emptyList()
        }

        override suspend fun vehicleSummary(id: String): VehicleSummary? {
            vehicleSummaryCount++
            val state = if (summaryStatesScript.isEmpty()) {
                "online"
            } else if (summaryStatesScript.size == 1) {
                summaryStatesScript[0]
            } else {
                summaryStatesScript.removeAt(0)
            }
            return VehicleSummary(id = id, vin = "VIN", name = "Car", state = state)
        }

        override suspend fun vehicleData(id: String): VehicleState {
            vehicleDataCount++
            check(vehicleDataScript.isNotEmpty()) { "vehicleData called with empty script" }
            val next = if (vehicleDataScript.size == 1) vehicleDataScript[0] else vehicleDataScript.removeAt(0)
            return next()
        }

        override suspend fun wakeUp(id: String) {
            wakeUpCount++
        }

        override suspend fun signedCommand(id: String, routableMessageB64: String): SignedCommandResponse {
            signedCommandCount++
            return SignedCommandResponse("")
        }
    }

    private fun sampleState(battery: Int = 72) = VehicleState(
        batteryPercent = battery,
        rangeKm = 340.0,
        chargingState = ChargingState.Stopped,
        pluggedIn = true,
        chargeLimitPercent = 80,
        chargeAmps = 16,
        maxChargeAmps = 32,
        insideTempC = 21.0,
        climateOn = false,
        targetTempC = 21.0,
        overheatProtection = OverheatProtectionMode.NoAc,
        dogModeOn = false,
        locked = true,
        windowsOpen = false,
        asleep = false,
    )

    @Test
    fun `starts with cached state marked stale`() = runTest {
        val store = InMemoryKeyValueStore()
        val cache = StateCache(store)
        val cachedState = sampleState(battery = 55)
        cache.save(cachedState, 1_000L)

        val api = FakeFleetApi()
        val repo = RealVehicleRepository(
            api = api,
            cache = StateCache(store),
            vehicleId = vehicleId,
            scope = backgroundScope,
            nowMs = { 5_000L },
        )

        repo.start()
        runCurrent()

        val state = repo.state.value
        assertIs<VehicleUiState.Ready>(state)
        assertEquals(cachedState, state.state)
        assertEquals(1_000L, state.updatedAtMs)
        assertTrue(state.stale)
        assertEquals(0, api.vehicleDataCount)
    }

    @Test
    fun `starts Loading when no cache`() = runTest {
        val store = InMemoryKeyValueStore()
        val api = FakeFleetApi()
        val repo = RealVehicleRepository(
            api = api,
            cache = StateCache(store),
            vehicleId = vehicleId,
            scope = backgroundScope,
            nowMs = { 5_000L },
        )

        repo.start()
        runCurrent()

        assertIs<VehicleUiState.Loading>(repo.state.value)
        assertEquals(0, api.vehicleDataCount)
    }

    @Test
    fun `refresh fetches vehicle_data and un-stales`() = runTest {
        val store = InMemoryKeyValueStore()
        val fresh = sampleState(battery = 90)
        val api = FakeFleetApi(vehicleDataScript = mutableListOf({ fresh }))
        val repo = RealVehicleRepository(
            api = api,
            cache = StateCache(store),
            vehicleId = vehicleId,
            scope = backgroundScope,
            nowMs = { 9_999L },
        )
        repo.start()
        runCurrent()

        repo.refresh()

        val state = repo.state.value
        assertIs<VehicleUiState.Ready>(state)
        assertEquals(fresh, state.state)
        assertEquals(9_999L, state.updatedAtMs)
        assertTrue(!state.stale)
        assertEquals(1, api.vehicleDataCount)

        // Persisted: verify via a second repository over the same store.
        val secondCache = StateCache(store)
        val loaded = secondCache.load()
        assertEquals(fresh, loaded?.state)
        assertEquals(9_999L, loaded?.updatedAtMs)
    }

    @Test
    fun `refresh when vehicle asleep emits Asleep with cached data and does NOT wake`() = runTest {
        val store = InMemoryKeyValueStore()
        val cached = sampleState(battery = 40)
        StateCache(store).save(cached, 2_000L)

        val api = FakeFleetApi(
            vehicleDataScript = mutableListOf({ throw VehicleAsleepException() }),
        )
        val repo = RealVehicleRepository(
            api = api,
            cache = StateCache(store),
            vehicleId = vehicleId,
            scope = backgroundScope,
            nowMs = { 3_000L },
        )
        repo.start()
        runCurrent()

        repo.refresh()

        val state = repo.state.value
        assertIs<VehicleUiState.Asleep>(state)
        assertEquals(cached, state.cached)
        assertEquals(2_000L, state.updatedAtMs)
        assertEquals(0, api.wakeUpCount)
    }

    @Test
    fun `wake polls summary then fetches data exactly once`() = runTest {
        val store = InMemoryKeyValueStore()
        val cached = sampleState(battery = 33)
        StateCache(store).save(cached, 1_000L)

        val woken = sampleState(battery = 60)
        val api = FakeFleetApi(
            vehicleDataScript = mutableListOf({ woken }),
            summaryStatesScript = mutableListOf("asleep", "asleep", "online"),
        )
        val repo = RealVehicleRepository(
            api = api,
            cache = StateCache(store),
            vehicleId = vehicleId,
            scope = backgroundScope,
            nowMs = { 20_000L },
        )
        repo.start()
        runCurrent()

        val result = repo.wake()

        assertEquals(CommandResult.Success, result)
        assertEquals(1, api.wakeUpCount)
        assertEquals(3, api.vehicleSummaryCount)
        assertEquals(1, api.vehicleDataCount)

        val state = repo.state.value
        assertIs<VehicleUiState.Ready>(state)
        assertEquals(woken, state.state)
        assertTrue(!state.stale)
    }

    @Test
    fun `wake timeout after 30s returns Failed WakeTimeout`() = runTest {
        val store = InMemoryKeyValueStore()
        val cached = sampleState(battery = 20)
        StateCache(store).save(cached, 500L)

        val api = FakeFleetApi(
            summaryStatesScript = mutableListOf("asleep"),
        )
        val repo = RealVehicleRepository(
            api = api,
            cache = StateCache(store),
            vehicleId = vehicleId,
            scope = backgroundScope,
            nowMs = { 999_999L },
        )
        repo.start()
        runCurrent()

        val result = repo.wake()

        assertIs<CommandResult.Failed>(result)
        assertEquals(ErrorKind.WakeTimeout, result.kind)
        assertEquals(0, api.vehicleDataCount)

        val state = repo.state.value
        assertIs<VehicleUiState.Error>(state)
        assertEquals(ErrorKind.WakeTimeout, state.kind)
        assertEquals(cached, state.cached)
    }

    @Test
    fun `auth expiry surfaces Error AuthExpired`() = runTest {
        val store = InMemoryKeyValueStore()
        val cached = sampleState(battery = 45)
        StateCache(store).save(cached, 1_500L)

        val api = FakeFleetApi(
            vehicleDataScript = mutableListOf({ throw AuthExpiredException("expired") }),
        )
        val repo = RealVehicleRepository(
            api = api,
            cache = StateCache(store),
            vehicleId = vehicleId,
            scope = backgroundScope,
            nowMs = { 4_000L },
        )
        repo.start()
        runCurrent()

        repo.refresh()

        val state = repo.state.value
        assertIs<VehicleUiState.Error>(state)
        assertEquals(ErrorKind.AuthExpired, state.kind)
        assertEquals(cached, state.cached)
    }

    @Test
    fun `offline surfaces Error Offline with cached data`() = runTest {
        val store = InMemoryKeyValueStore()
        val cached = sampleState(battery = 50)
        StateCache(store).save(cached, 1_600L)

        val api = FakeFleetApi(
            vehicleDataScript = mutableListOf({ throw FleetOfflineException(java.io.IOException("boom")) }),
        )
        val repo = RealVehicleRepository(
            api = api,
            cache = StateCache(store),
            vehicleId = vehicleId,
            scope = backgroundScope,
            nowMs = { 4_400L },
        )
        repo.start()
        runCurrent()

        repo.refresh()

        val state = repo.state.value
        assertIs<VehicleUiState.Error>(state)
        assertEquals(ErrorKind.Offline, state.kind)
        assertEquals(cached, state.cached)
        assertEquals(1_600L, state.updatedAtMs)
    }

    @Test
    fun `rate limit surfaces Error RateLimited`() = runTest {
        val store = InMemoryKeyValueStore()
        val cached = sampleState(battery = 61)
        StateCache(store).save(cached, 1_700L)

        val api = FakeFleetApi(
            vehicleDataScript = mutableListOf({ throw RateLimitedException() }),
        )
        val repo = RealVehicleRepository(
            api = api,
            cache = StateCache(store),
            vehicleId = vehicleId,
            scope = backgroundScope,
            nowMs = { 4_800L },
        )
        repo.start()
        runCurrent()

        repo.refresh()

        val state = repo.state.value
        assertIs<VehicleUiState.Error>(state)
        assertEquals(ErrorKind.RateLimited, state.kind)
        assertEquals(cached, state.cached)
    }

    @Test
    fun `partial data falls back to cache as Error Unknown`() = runTest {
        val store = InMemoryKeyValueStore()
        val cached = sampleState(battery = 66)
        StateCache(store).save(cached, 1_800L)

        val api = FakeFleetApi(
            vehicleDataScript = mutableListOf({ throw FleetPartialDataException("charge_state") }),
        )
        val repo = RealVehicleRepository(
            api = api,
            cache = StateCache(store),
            vehicleId = vehicleId,
            scope = backgroundScope,
            nowMs = { 5_500L },
        )
        repo.start()
        runCurrent()

        repo.refresh()

        val state = repo.state.value
        assertIs<VehicleUiState.Error>(state)
        assertEquals(ErrorKind.Unknown, state.kind)
        assertEquals(cached, state.cached)

        // Do NOT persist anything on this path.
        val loaded = StateCache(store).load()
        assertEquals(cached, loaded?.state)
        assertEquals(1_800L, loaded?.updatedAtMs)
    }

    @Test
    fun `generic http error surfaces Error Unknown`() = runTest {
        val store = InMemoryKeyValueStore()
        val cached = sampleState(battery = 70)
        StateCache(store).save(cached, 1_900L)

        val api = FakeFleetApi(
            vehicleDataScript = mutableListOf({ throw FleetHttpException(500, "server error") }),
        )
        val repo = RealVehicleRepository(
            api = api,
            cache = StateCache(store),
            vehicleId = vehicleId,
            scope = backgroundScope,
            nowMs = { 6_000L },
        )
        repo.start()
        runCurrent()

        repo.refresh()

        val state = repo.state.value
        assertIs<VehicleUiState.Error>(state)
        assertEquals(ErrorKind.Unknown, state.kind)
    }

    @Test
    fun `command methods return Rejected not-yet-supported`() = runTest {
        val store = InMemoryKeyValueStore()
        val api = FakeFleetApi()
        val repo = RealVehicleRepository(
            api = api,
            cache = StateCache(store),
            vehicleId = vehicleId,
            scope = backgroundScope,
            nowMs = { 0L },
        )

        val lockResult = repo.lock()
        val chargeLimitResult = repo.setChargeLimit(80)
        val ventResult = repo.ventWindows()

        assertIs<CommandResult.Rejected>(lockResult)
        assertEquals("Not yet supported in this build", lockResult.reason)
        assertIs<CommandResult.Rejected>(chargeLimitResult)
        assertEquals("Not yet supported in this build", chargeLimitResult.reason)
        assertIs<CommandResult.Rejected>(ventResult)
        assertEquals("Not yet supported in this build", ventResult.reason)
    }
}
