package com.amolpurohit.tesla.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.amolpurohit.tesla.Graph
import com.amolpurohit.tesla.ui.components.CommandButton
import com.amolpurohit.tesla.ui.components.Stepper
import com.amolpurohit.tesla.ui.components.UpdatedAtLine
import com.amolpurohit.tesla.vehicle.ChargingState
import com.amolpurohit.tesla.vehicle.CommandResult
import com.amolpurohit.tesla.vehicle.VehicleRepository
import com.amolpurohit.tesla.vehicle.VehicleUiState
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.flow.StateFlow

enum class ChargeCommand { Limit, Amps, Charging }

typealias ChargeCommandError = TrackedError<ChargeCommand>

private const val LIMIT_STEP = 5
private const val LIMIT_MIN = 50
private const val LIMIT_MAX = 100
private const val AMPS_STEP = 1
private const val AMPS_MIN = 5

class ChargeScreenViewModel(
    private val repo: VehicleRepository,
) : LightViewModel<Unit>() {

    private val tracker = CommandTracker<ChargeCommand>()

    val ui: StateFlow<VehicleUiState> = repo.state
    val pending: StateFlow<ChargeCommand?> = tracker.pending
    val commandError: StateFlow<ChargeCommandError?> = tracker.error

    fun incrementLimit() {
        val state = (ui.value as? VehicleUiState.Ready)?.state ?: return
        if (state.chargeLimitPercent >= LIMIT_MAX) return
        runCommand(ChargeCommand.Limit) { repo.setChargeLimit(state.chargeLimitPercent + LIMIT_STEP) }
    }

    fun decrementLimit() {
        val state = (ui.value as? VehicleUiState.Ready)?.state ?: return
        if (state.chargeLimitPercent <= LIMIT_MIN) return
        runCommand(ChargeCommand.Limit) { repo.setChargeLimit(state.chargeLimitPercent - LIMIT_STEP) }
    }

    fun incrementAmps() {
        val state = (ui.value as? VehicleUiState.Ready)?.state ?: return
        if (state.chargeAmps >= state.maxChargeAmps) return
        runCommand(ChargeCommand.Amps) { repo.setChargeAmps(state.chargeAmps + AMPS_STEP) }
    }

    fun decrementAmps() {
        val state = (ui.value as? VehicleUiState.Ready)?.state ?: return
        if (state.chargeAmps <= AMPS_MIN) return
        runCommand(ChargeCommand.Amps) { repo.setChargeAmps(state.chargeAmps - AMPS_STEP) }
    }

    fun toggleCharging() {
        val state = (ui.value as? VehicleUiState.Ready)?.state ?: return
        val isCharging = state.chargingState == ChargingState.Charging
        runCommand(ChargeCommand.Charging) { if (isCharging) repo.stopCharging() else repo.startCharging() }
    }

    private fun runCommand(command: ChargeCommand, block: suspend () -> CommandResult) {
        tracker.launch(viewModelScope, command, block)
    }
}

class ChargeScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, ChargeScreenViewModel>(sealedActivity) {

    override val viewModelClass: Class<ChargeScreenViewModel>
        get() = ChargeScreenViewModel::class.java

    override fun createViewModel(): ChargeScreenViewModel = ChargeScreenViewModel(Graph.repository())

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val ui by viewModel.ui.collectAsState()
        val pending by viewModel.pending.collectAsState()
        val commandError by viewModel.commandError.collectAsState()

        fun enabledFor(command: ChargeCommand) = pending == null || pending == command

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier.fillMaxSize().background(LightThemeTokens.colors.background),
            ) {
                LightScrollView(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                ) {
                    LightText(
                        text = "Charging",
                        variant = LightTextVariant.Heading,
                        modifier = Modifier.padding(vertical = 1f.gridUnitsAsDp()),
                    )

                    when (val state = ui) {
                        is VehicleUiState.Ready -> {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Stepper(
                                    label = "Charge limit",
                                    value = "${state.state.chargeLimitPercent}%",
                                    onDecrement = viewModel::decrementLimit,
                                    onIncrement = viewModel::incrementLimit,
                                    pending = pending == ChargeCommand.Limit,
                                )
                                Stepper(
                                    label = "Amps",
                                    value = "${state.state.chargeAmps} A",
                                    onDecrement = viewModel::decrementAmps,
                                    onIncrement = viewModel::incrementAmps,
                                    pending = pending == ChargeCommand.Amps,
                                )
                            }
                            UpdatedAtLine(
                                updatedAtMs = state.updatedAtMs,
                                suffix = if (state.stale) " · stale" else null,
                            )
                            Column(modifier = Modifier.fillMaxWidth().padding(top = 1f.gridUnitsAsDp())) {
                                CommandButton(
                                    label = if (state.state.chargingState == ChargingState.Charging) "Stop charging" else "Start charging",
                                    pending = pending == ChargeCommand.Charging,
                                    enabled = enabledFor(ChargeCommand.Charging),
                                    error = commandError.messageFor(ChargeCommand.Charging),
                                    onClick = viewModel::toggleCharging,
                                )
                            }
                        }

                        is VehicleUiState.NoCredentials -> {
                            LightText(text = "Setup required", variant = LightTextVariant.Copy)
                        }

                        is VehicleUiState.Loading -> {
                            LightText(text = "Loading…", variant = LightTextVariant.Copy)
                        }

                        is VehicleUiState.Asleep -> {
                            UpdatedAtLine(updatedAtMs = state.updatedAtMs, badge = "Asleep")
                        }

                        is VehicleUiState.Error -> {
                            UpdatedAtLine(updatedAtMs = state.updatedAtMs)
                            LightText(
                                text = errorMessage(state.kind),
                                variant = LightTextVariant.Detail,
                                lighten = true,
                                modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                            )
                        }
                    }
                }
            }
        }
    }
}
