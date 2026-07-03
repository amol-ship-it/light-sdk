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
}
