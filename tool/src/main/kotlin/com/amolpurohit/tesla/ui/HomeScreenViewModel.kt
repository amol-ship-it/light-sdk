package com.amolpurohit.tesla.ui

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewModelScope
import com.amolpurohit.tesla.Graph
import com.amolpurohit.tesla.vehicle.ChargingState
import com.amolpurohit.tesla.vehicle.CommandResult
import com.amolpurohit.tesla.vehicle.VehicleRepository
import com.amolpurohit.tesla.vehicle.VehicleUiState
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class Command { Lock, Climate, Windows, Charging, Wake, Refresh }

typealias CommandError = TrackedError<Command>

class HomeScreenViewModel private constructor(
    initialRepo: VehicleRepository?,
    private val dataStore: DataStore<Preferences>?,
) : LightViewModel<Unit>() {

    /** Test constructor: repo is available immediately, so [ui] is never Loading-for-resolution. */
    constructor(repo: VehicleRepository) : this(initialRepo = repo, dataStore = null)

    /** Production constructor: the screen hands over its own `lightContext.dataStore`; the real repo resolves in [onScreenShow]. */
    constructor(dataStore: DataStore<Preferences>) : this(initialRepo = null, dataStore = dataStore)

    private val tracker = CommandTracker<Command>()

    private val repoFlow = MutableStateFlow(initialRepo)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val ui: StateFlow<VehicleUiState> = repoFlow
        .flatMapLatest { repo -> repo?.state ?: flowOf(VehicleUiState.Loading) }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            initialRepo?.state?.value ?: VehicleUiState.Loading,
        )

    val pending: StateFlow<Command?> = tracker.pending
    val commandError: StateFlow<CommandError?> = tracker.error

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        onShow()
    }

    /** Body of [onScreenShow]; internal so tests can drive it without constructing a real screen (needs Android). */
    internal fun onShow() {
        val store = dataStore
        if (store != null) {
            // Always re-resolve — never latch on repoFlow being non-null: after Graph.reset()
            // (SetupScreen pick / re-link) the Graph memo is cleared and whatever repoFlow holds
            // (e.g. the NoCredentials sentinel) is stale. Graph.repository is memoized, so
            // post-resolution re-shows are cheap and return the same instance (reference-equal
            // -> MutableStateFlow skips re-emission -> flatMapLatest doesn't restart).
            viewModelScope.launch {
                repoFlow.value = Graph.repository(store)
                refresh()
            }
        } else {
            refresh()
        }
    }

    // The `as? Ready` guards below look unreachable (the buttons only render in Ready) but
    // cover the compose-race window: a tap can land just as the state flips away from Ready.

    fun toggleLock() {
        val locked = (ui.value as? VehicleUiState.Ready)?.state?.locked ?: return
        val repo = repoFlow.value ?: return
        runCommand(Command.Lock) { if (locked) repo.unlock() else repo.lock() }
    }

    fun toggleClimate() {
        val climateOn = (ui.value as? VehicleUiState.Ready)?.state?.climateOn ?: return
        val repo = repoFlow.value ?: return
        runCommand(Command.Climate) { repo.setClimateOn(!climateOn) }
    }

    fun toggleWindows() {
        val windowsOpen = (ui.value as? VehicleUiState.Ready)?.state?.windowsOpen ?: return
        val repo = repoFlow.value ?: return
        runCommand(Command.Windows) { if (windowsOpen) repo.closeWindows() else repo.ventWindows() }
    }

    fun toggleCharging() {
        val state = (ui.value as? VehicleUiState.Ready)?.state ?: return
        val repo = repoFlow.value ?: return
        val isCharging = state.chargingState == ChargingState.Charging
        runCommand(Command.Charging) { if (isCharging) repo.stopCharging() else repo.startCharging() }
    }

    fun wake() {
        val repo = repoFlow.value ?: return
        runCommand(Command.Wake) { repo.wake() }
    }

    fun refresh() {
        val repo = repoFlow.value ?: return
        runCommand(Command.Refresh) {
            repo.refresh()
            CommandResult.Success
        }
    }

    private fun runCommand(command: Command, block: suspend () -> CommandResult) {
        tracker.launch(viewModelScope, command, block)
    }
}
