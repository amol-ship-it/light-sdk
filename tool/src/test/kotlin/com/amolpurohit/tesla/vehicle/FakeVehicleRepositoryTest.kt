package com.amolpurohit.tesla.vehicle

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class FakeVehicleRepositoryTest {
    private fun ready(repo: VehicleRepository) =
        (repo.state.value as VehicleUiState.Ready).state

    @Test fun `starts Ready with plausible state`() = runTest {
        val repo = FakeVehicleRepository()
        assertIs<VehicleUiState.Ready>(repo.state.value)
    }

    @Test fun `lock and unlock toggle locked`() = runTest {
        val repo = FakeVehicleRepository()
        repo.unlock(); assertFalse(ready(repo).locked)
        repo.lock(); assertTrue(ready(repo).locked)
    }

    @Test fun `stopCharging while charging moves to Stopped`() = runTest {
        val repo = FakeVehicleRepository()
        repo.startCharging()
        assertEquals(ChargingState.Charging, ready(repo).chargingState)
        repo.stopCharging()
        assertEquals(ChargingState.Stopped, ready(repo).chargingState)
    }

    @Test fun `startCharging while unplugged is Rejected`() = runTest {
        val repo = FakeVehicleRepository(initial = FakeVehicleRepository.DEFAULT.copy(
            pluggedIn = false, chargingState = ChargingState.Disconnected))
        val r = repo.startCharging()
        assertIs<CommandResult.Rejected>(r)
    }

    @Test fun `asleep vehicle reports Asleep and wake recovers`() = runTest {
        val repo = FakeVehicleRepository(initial = FakeVehicleRepository.DEFAULT.copy(asleep = true))
        repo.refresh()
        assertIs<VehicleUiState.Asleep>(repo.state.value)
        repo.wake()
        assertIs<VehicleUiState.Ready>(repo.state.value)
    }

    @Test fun `setChargeLimit clamps to 50-100`() = runTest {
        val repo = FakeVehicleRepository()
        repo.setChargeLimit(30); assertEquals(50, ready(repo).chargeLimitPercent)
        repo.setChargeLimit(110); assertEquals(100, ready(repo).chargeLimitPercent)
    }

    @Test fun `setChargeAmps clamps to 5-maxChargeAmps`() = runTest {
        val repo = FakeVehicleRepository()  // DEFAULT.maxChargeAmps = 32
        repo.setChargeAmps(2); assertEquals(5, ready(repo).chargeAmps)
        repo.setChargeAmps(48); assertEquals(32, ready(repo).chargeAmps)
    }

    @Test fun `stopCharging while already Stopped stays Stopped`() = runTest {
        val repo = FakeVehicleRepository()
        val result = repo.stopCharging()
        assertEquals(ChargingState.Stopped, ready(repo).chargingState)
        assertIs<CommandResult.Success>(result)
    }

    @Test fun `refresh on awake vehicle re-emits Ready`() = runTest {
        val repo = FakeVehicleRepository()
        repo.refresh()
        val state = repo.state.value
        assertIs<VehicleUiState.Ready>(state)
        assertFalse(state.stale)
    }
}
