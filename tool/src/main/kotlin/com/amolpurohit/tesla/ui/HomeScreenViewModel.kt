package com.amolpurohit.tesla.ui

import androidx.lifecycle.viewModelScope
import com.amolpurohit.tesla.vehicle.ChargingState
import com.amolpurohit.tesla.vehicle.CommandResult
import com.amolpurohit.tesla.vehicle.ErrorKind
import com.amolpurohit.tesla.vehicle.VehicleRepository
import com.amolpurohit.tesla.vehicle.VehicleUiState
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class Command { Lock, Climate, Windows, Charging, Wake, Refresh }

data class CommandError(val command: Command, val message: String)

class HomeScreenViewModel(
    private val repo: VehicleRepository,
) : LightViewModel<Unit>() {

    val ui: StateFlow<VehicleUiState> = repo.state
    val pending = MutableStateFlow<Command?>(null)
    val commandError = MutableStateFlow<CommandError?>(null)

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        refresh()
    }

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
        pending.value = Command.Refresh
        viewModelScope.launch {
            repo.refresh()
            pending.value = null
        }
    }

    private fun runCommand(command: Command, block: suspend () -> CommandResult) {
        pending.value = command
        viewModelScope.launch {
            when (val result = block()) {
                is CommandResult.Success -> commandError.value = null
                is CommandResult.Rejected -> commandError.value = CommandError(command, result.reason)
                is CommandResult.Failed -> commandError.value = CommandError(command, errorMessage(result.kind))
            }
            pending.value = null
        }
    }

    private fun errorMessage(kind: ErrorKind): String = when (kind) {
        ErrorKind.Offline -> "No connection"
        ErrorKind.AuthExpired -> "Sign-in expired"
        ErrorKind.KeyNotEnrolled -> "Key not enrolled"
        ErrorKind.RateLimited -> "Try again later"
        ErrorKind.WakeTimeout -> "Car didn't wake"
        ErrorKind.Unknown -> "Something went wrong"
    }
}
