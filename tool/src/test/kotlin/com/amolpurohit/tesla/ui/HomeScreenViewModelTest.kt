package com.amolpurohit.tesla.ui

import com.amolpurohit.tesla.vehicle.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import kotlin.test.*

class HomeScreenViewModelTest {
    @Test fun `lock command sets pending then clears`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = FakeVehicleRepository()
            val vm = HomeScreenViewModel(repo)
            vm.toggleLock()
            assertEquals(Command.Lock, vm.pending.value)
            advanceUntilIdle()
            assertNull(vm.pending.value)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `rejected command surfaces inline error`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = FakeVehicleRepository(initial = FakeVehicleRepository.DEFAULT.copy(
                pluggedIn = false, chargingState = ChargingState.Disconnected))
            val vm = HomeScreenViewModel(repo)
            vm.toggleCharging()
            advanceUntilIdle()
            assertEquals("Not plugged in", vm.commandError.value?.message)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `second command while one is pending is ignored`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = FakeVehicleRepository()  // DEFAULT: locked=true, climateOn=false
            val vm = HomeScreenViewModel(repo)
            vm.toggleLock()
            assertEquals(Command.Lock, vm.pending.value)
            // Lock's coroutine has not run yet; a second command must be ignored.
            vm.toggleClimate()
            assertEquals(Command.Lock, vm.pending.value)
            advanceUntilIdle()
            assertNull(vm.pending.value)
            val state = (repo.state.value as VehicleUiState.Ready).state
            assertFalse(state.locked)      // Lock ran (unlock direction)
            assertFalse(state.climateOn)   // Climate never reached the repo
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `toggle no-ops when state is not Ready`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = FakeVehicleRepository(initial = FakeVehicleRepository.DEFAULT.copy(asleep = true))
            val vm = HomeScreenViewModel(repo)
            assertIs<VehicleUiState.Asleep>(repo.state.value)
            vm.toggleLock()
            assertNull(vm.pending.value)   // guard returned before setting pending
            advanceUntilIdle()
            val cached = (repo.state.value as VehicleUiState.Asleep).cached
            assertEquals(true, cached?.locked)  // no repo mutation happened
        } finally { Dispatchers.resetMain() }
    }
}
