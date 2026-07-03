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
import com.amolpurohit.tesla.ui.components.ModeSelector
import com.amolpurohit.tesla.ui.components.Stepper
import com.amolpurohit.tesla.ui.components.UpdatedAtLine
import com.amolpurohit.tesla.vehicle.CommandResult
import com.amolpurohit.tesla.vehicle.OverheatProtectionMode
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

enum class ClimateCommand { Power, Temp, Overheat, DogMode }

typealias ClimateCommandError = TrackedError<ClimateCommand>

private const val TEMP_STEP = 0.5

class ClimateScreenViewModel(
    private val repo: VehicleRepository,
) : LightViewModel<Unit>() {

    private val tracker = CommandTracker<ClimateCommand>()

    val ui: StateFlow<VehicleUiState> = repo.state
    val pending: StateFlow<ClimateCommand?> = tracker.pending
    val commandError: StateFlow<ClimateCommandError?> = tracker.error

    fun toggleClimate() {
        val state = (ui.value as? VehicleUiState.Ready)?.state ?: return
        val turnOn = !state.climateOn
        runCommand(ClimateCommand.Power) { repo.setClimateOn(turnOn) }
    }

    fun incrementTargetTemp() {
        val state = (ui.value as? VehicleUiState.Ready)?.state ?: return
        if (state.targetTempC >= state.maxTargetTempC) return
        runCommand(ClimateCommand.Temp) { repo.setTargetTemp(state.targetTempC + TEMP_STEP) }
    }

    fun decrementTargetTemp() {
        val state = (ui.value as? VehicleUiState.Ready)?.state ?: return
        if (state.targetTempC <= state.minTargetTempC) return
        runCommand(ClimateCommand.Temp) { repo.setTargetTemp(state.targetTempC - TEMP_STEP) }
    }

    fun setOverheatProtection(mode: OverheatProtectionMode) {
        val state = (ui.value as? VehicleUiState.Ready)?.state ?: return
        if (state.overheatProtection == mode) return
        runCommand(ClimateCommand.Overheat) { repo.setOverheatProtection(mode) }
    }

    fun toggleDogMode() {
        val state = (ui.value as? VehicleUiState.Ready)?.state ?: return
        val turnOn = !state.dogModeOn
        runCommand(ClimateCommand.DogMode) { repo.setDogMode(turnOn) }
    }

    private fun runCommand(command: ClimateCommand, block: suspend () -> CommandResult) {
        tracker.launch(viewModelScope, command, block)
    }
}

class ClimateScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, ClimateScreenViewModel>(sealedActivity) {

    override val viewModelClass: Class<ClimateScreenViewModel>
        get() = ClimateScreenViewModel::class.java

    override fun createViewModel(): ClimateScreenViewModel = ClimateScreenViewModel(Graph.repository())

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val ui by viewModel.ui.collectAsState()
        val pending by viewModel.pending.collectAsState()
        val commandError by viewModel.commandError.collectAsState()

        fun enabledFor(command: ClimateCommand) = pending == null || pending == command

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
                        text = "Climate",
                        variant = LightTextVariant.Heading,
                        modifier = Modifier.padding(vertical = 1f.gridUnitsAsDp()),
                    )

                    when (val state = ui) {
                        is VehicleUiState.Ready -> {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Stepper(
                                    label = "Target temp",
                                    value = formatTemp(state.state.targetTempC),
                                    onDecrement = viewModel::decrementTargetTemp,
                                    onIncrement = viewModel::incrementTargetTemp,
                                    pending = pending == ClimateCommand.Temp,
                                )
                            }
                            ModeSelector(
                                label = "Overheat protection",
                                options = listOf(
                                    "Off" to OverheatProtectionMode.Off,
                                    "No A/C" to OverheatProtectionMode.NoAc,
                                    "A/C" to OverheatProtectionMode.Ac,
                                ),
                                current = state.state.overheatProtection,
                                enabled = enabledFor(ClimateCommand.Overheat),
                                onSelect = viewModel::setOverheatProtection,
                            )
                            UpdatedAtLine(
                                updatedAtMs = state.updatedAtMs,
                                suffix = if (state.stale) " · stale" else null,
                            )
                            Column(modifier = Modifier.fillMaxWidth().padding(top = 1f.gridUnitsAsDp())) {
                                CommandButton(
                                    label = if (state.state.climateOn) "Climate off" else "Climate on",
                                    pending = pending == ClimateCommand.Power,
                                    enabled = enabledFor(ClimateCommand.Power),
                                    error = commandError.messageFor(ClimateCommand.Power),
                                    onClick = viewModel::toggleClimate,
                                )
                                CommandButton(
                                    label = if (state.state.dogModeOn) "Dog mode off" else "Dog mode on",
                                    pending = pending == ClimateCommand.DogMode,
                                    enabled = enabledFor(ClimateCommand.DogMode),
                                    error = commandError.messageFor(ClimateCommand.DogMode),
                                    onClick = viewModel::toggleDogMode,
                                )
                                LightText(
                                    text = "Keeps climate on for pets while parked. Screen in car shows a message.",
                                    variant = LightTextVariant.Detail,
                                    lighten = true,
                                    modifier = Modifier.padding(top = 0.25f.gridUnitsAsDp()),
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
                                text = state.kind.name,
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
