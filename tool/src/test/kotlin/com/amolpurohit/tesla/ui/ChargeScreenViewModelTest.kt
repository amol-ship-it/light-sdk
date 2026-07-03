package com.amolpurohit.tesla.ui

import com.amolpurohit.tesla.vehicle.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import kotlin.test.*

class ChargeScreenViewModelTest {
    @Test fun `incrementLimit steps by 5`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = FakeVehicleRepository()  // DEFAULT: limit 80
            val vm = ChargeScreenViewModel(repo)
            vm.incrementLimit()
            advanceUntilIdle()
            val state = (repo.state.value as VehicleUiState.Ready).state
            assertEquals(85, state.chargeLimitPercent)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `incrementLimit is a no-op at the 100 boundary`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = FakeVehicleRepository(initial = FakeVehicleRepository.DEFAULT.copy(chargeLimitPercent = 100))
            val vm = ChargeScreenViewModel(repo)
            vm.incrementLimit()
            advanceUntilIdle()
            val state = (repo.state.value as VehicleUiState.Ready).state
            assertEquals(100, state.chargeLimitPercent)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `decrementLimit is a no-op at the 50 boundary`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = FakeVehicleRepository(initial = FakeVehicleRepository.DEFAULT.copy(chargeLimitPercent = 50))
            val vm = ChargeScreenViewModel(repo)
            vm.decrementLimit()
            advanceUntilIdle()
            val state = (repo.state.value as VehicleUiState.Ready).state
            assertEquals(50, state.chargeLimitPercent)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `incrementAmps steps by 1`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = FakeVehicleRepository()  // DEFAULT: amps 16, maxChargeAmps 32
            val vm = ChargeScreenViewModel(repo)
            vm.incrementAmps()
            advanceUntilIdle()
            val state = (repo.state.value as VehicleUiState.Ready).state
            assertEquals(17, state.chargeAmps)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `incrementAmps is a no-op at maxChargeAmps boundary`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = FakeVehicleRepository(initial = FakeVehicleRepository.DEFAULT.copy(chargeAmps = 32, maxChargeAmps = 32))
            val vm = ChargeScreenViewModel(repo)
            vm.incrementAmps()
            advanceUntilIdle()
            val state = (repo.state.value as VehicleUiState.Ready).state
            assertEquals(32, state.chargeAmps)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `decrementAmps is a no-op at the 5 boundary`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = FakeVehicleRepository(initial = FakeVehicleRepository.DEFAULT.copy(chargeAmps = 5))
            val vm = ChargeScreenViewModel(repo)
            vm.decrementAmps()
            advanceUntilIdle()
            val state = (repo.state.value as VehicleUiState.Ready).state
            assertEquals(5, state.chargeAmps)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `toggleCharging delegates to repo`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = FakeVehicleRepository()  // DEFAULT: Stopped, plugged in
            val vm = ChargeScreenViewModel(repo)
            vm.toggleCharging()
            assertEquals(ChargeCommand.Charging, vm.pending.value)
            advanceUntilIdle()
            assertNull(vm.pending.value)
            val state = (repo.state.value as VehicleUiState.Ready).state
            assertEquals(ChargingState.Charging, state.chargingState)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `toggleCharging surfaces rejection when unplugged`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = FakeVehicleRepository(initial = FakeVehicleRepository.DEFAULT.copy(
                pluggedIn = false, chargingState = ChargingState.Disconnected))
            val vm = ChargeScreenViewModel(repo)
            vm.toggleCharging()
            advanceUntilIdle()
            assertEquals("Not plugged in", vm.commandError.value?.message)
        } finally { Dispatchers.resetMain() }
    }
}
