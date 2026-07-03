package com.amolpurohit.tesla.ui

import androidx.lifecycle.viewModelScope
import com.amolpurohit.tesla.vehicle.ChargingState
import com.amolpurohit.tesla.vehicle.CommandResult
import com.amolpurohit.tesla.vehicle.VehicleRepository
import com.amolpurohit.tesla.vehicle.VehicleUiState
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import kotlinx.coroutines.flow.StateFlow

enum class Command { Lock, Climate, Windows, Charging, Wake, Refresh }

typealias CommandError = TrackedError<Command>

class HomeScreenViewModel(
    private val repo: VehicleRepository,
) : LightViewModel<Unit>() {

    private val tracker = CommandTracker<Command>()

    val ui: StateFlow<VehicleUiState> = repo.state
    val pending: StateFlow<Command?> = tracker.pending
    val commandError: StateFlow<CommandError?> = tracker.error

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        refresh()
    }

    // The `as? Ready` guards below look unreachable (the buttons only render in Ready) but
    // cover the compose-race window: a tap can land just as the state flips away from Ready.

    fun toggleLock() {
        val locked = (ui.value as? VehicleUiState.Ready)?.state?.locked ?: return
        runCommand(Command.Lock) { if (locked) repo.unlock() else repo.lock() }
    }

    fun toggleClimate() {
        val climateOn = (ui.value as? VehicleUiState.Ready)?.state?.climateOn ?: return
        runCommand(Command.Climate) { repo.setClimateOn(!climateOn) }
    }

    fun toggleWindows() {
        val windowsOpen = (ui.value as? VehicleUiState.Ready)?.state?.windowsOpen ?: return
        runCommand(Command.Windows) { if (windowsOpen) repo.closeWindows() else repo.ventWindows() }
    }

    fun toggleCharging() {
        val state = (ui.value as? VehicleUiState.Ready)?.state ?: return
        val isCharging = state.chargingState == ChargingState.Charging
        runCommand(Command.Charging) { if (isCharging) repo.stopCharging() else repo.startCharging() }
    }

    fun wake() {
        runCommand(Command.Wake) { repo.wake() }
    }

    fun refresh() {
        runCommand(Command.Refresh) {
            repo.refresh()
            CommandResult.Success
        }
    }

    private fun runCommand(command: Command, block: suspend () -> CommandResult) {
        tracker.launch(viewModelScope, command, block)
    }
}
