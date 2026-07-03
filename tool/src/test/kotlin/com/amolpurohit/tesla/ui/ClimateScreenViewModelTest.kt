package com.amolpurohit.tesla.ui

import com.amolpurohit.tesla.vehicle.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import kotlin.test.*

class ClimateScreenViewModelTest {
    @Test fun `incrementTargetTemp steps by 0_5`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = FakeVehicleRepository()  // DEFAULT: targetTempC 21.0
            val vm = ClimateScreenViewModel(repo)
            vm.incrementTargetTemp()
            advanceUntilIdle()
            val state = (repo.state.value as VehicleUiState.Ready).state
            assertEquals(21.5, state.targetTempC)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `incrementTargetTemp is a no-op at the max boundary`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = FakeVehicleRepository(initial = FakeVehicleRepository.DEFAULT.copy(targetTempC = 28.0))
            val vm = ClimateScreenViewModel(repo)
            vm.incrementTargetTemp()
            assertNull(vm.pending.value)  // guard returned before setting pending — no repo call was made
            advanceUntilIdle()
            val state = (repo.state.value as VehicleUiState.Ready).state
            assertEquals(28.0, state.targetTempC)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `decrementTargetTemp is a no-op at the min boundary`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = FakeVehicleRepository(initial = FakeVehicleRepository.DEFAULT.copy(targetTempC = 15.0))
            val vm = ClimateScreenViewModel(repo)
            vm.decrementTargetTemp()
            assertNull(vm.pending.value)  // guard returned before setting pending — no repo call was made
            advanceUntilIdle()
            val state = (repo.state.value as VehicleUiState.Ready).state
            assertEquals(15.0, state.targetTempC)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `decrementTargetTemp steps by 0_5`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = FakeVehicleRepository()  // DEFAULT: targetTempC 21.0
            val vm = ClimateScreenViewModel(repo)
            vm.decrementTargetTemp()
            advanceUntilIdle()
            val state = (repo.state.value as VehicleUiState.Ready).state
            assertEquals(20.5, state.targetTempC)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `setOverheatProtection delegates to repo`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = FakeVehicleRepository()  // DEFAULT: NoAc
            val vm = ClimateScreenViewModel(repo)
            vm.setOverheatProtection(OverheatProtectionMode.Ac)
            assertEquals(ClimateCommand.Overheat, vm.pending.value)
            advanceUntilIdle()
            assertNull(vm.pending.value)
            val state = (repo.state.value as VehicleUiState.Ready).state
            assertEquals(OverheatProtectionMode.Ac, state.overheatProtection)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `setOverheatProtection with current mode is a no-op`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = FakeVehicleRepository()  // DEFAULT: NoAc
            val vm = ClimateScreenViewModel(repo)
            vm.setOverheatProtection(OverheatProtectionMode.NoAc)
            assertNull(vm.pending.value)  // guard returned before setting pending — no repo call was made
            advanceUntilIdle()
            val state = (repo.state.value as VehicleUiState.Ready).state
            assertEquals(OverheatProtectionMode.NoAc, state.overheatProtection)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `toggleDogMode delegates to repo`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = FakeVehicleRepository()  // DEFAULT: dogModeOn false
            val vm = ClimateScreenViewModel(repo)
            vm.toggleDogMode()
            assertEquals(ClimateCommand.DogMode, vm.pending.value)
            advanceUntilIdle()
            assertNull(vm.pending.value)
            val state = (repo.state.value as VehicleUiState.Ready).state
            assertEquals(true, state.dogModeOn)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `toggleClimate delegates to repo`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = FakeVehicleRepository()  // DEFAULT: climate off
            val vm = ClimateScreenViewModel(repo)
            vm.toggleClimate()
            assertEquals(ClimateCommand.Power, vm.pending.value)
            advanceUntilIdle()
            assertNull(vm.pending.value)
            val state = (repo.state.value as VehicleUiState.Ready).state
            assertEquals(true, state.climateOn)
        } finally { Dispatchers.resetMain() }
    }
}
