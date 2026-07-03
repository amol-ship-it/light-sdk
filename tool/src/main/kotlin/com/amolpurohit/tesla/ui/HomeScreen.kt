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
import com.amolpurohit.tesla.Graph
import com.amolpurohit.tesla.ui.components.CommandButton
import com.amolpurohit.tesla.ui.components.StatusRow
import com.amolpurohit.tesla.ui.components.UpdatedAtLine
import com.amolpurohit.tesla.vehicle.ChargingState
import com.amolpurohit.tesla.vehicle.VehicleState
import com.amolpurohit.tesla.vehicle.VehicleUiState
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp

@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, HomeScreenViewModel>(sealedActivity) {

    override val viewModelClass: Class<HomeScreenViewModel>
        get() = HomeScreenViewModel::class.java

    override fun createViewModel(): HomeScreenViewModel = HomeScreenViewModel(Graph.repository())

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val ui by viewModel.ui.collectAsState()
        val pending by viewModel.pending.collectAsState()
        val commandError by viewModel.commandError.collectAsState()

        // Single in-flight command: while one command is pending every other button is
        // disabled (the pending one shows its own spinner via `pending`).
        fun enabledFor(command: Command) = pending == null || pending == command

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
                        text = "Tesla",
                        variant = LightTextVariant.Heading,
                        modifier = Modifier.padding(vertical = 1f.gridUnitsAsDp()),
                    )

                    when (val state = ui) {
                        is VehicleUiState.NoCredentials -> {
                            LightText(text = "Setup required", variant = LightTextVariant.Copy)
                        }

                        is VehicleUiState.Loading -> {
                            LightText(text = "Loading…", variant = LightTextVariant.Copy)
                        }

                        is VehicleUiState.Ready -> {
                            DashboardRows(state.state)
                            UpdatedAtLine(
                                updatedAtMs = state.updatedAtMs,
                                suffix = if (state.stale) " · stale" else null,
                            )
                            Column(modifier = Modifier.fillMaxWidth().padding(top = 1f.gridUnitsAsDp())) {
                                CommandButton(
                                    label = if (state.state.locked) "Unlock" else "Lock",
                                    pending = pending == Command.Lock,
                                    enabled = enabledFor(Command.Lock),
                                    error = commandError.messageFor(Command.Lock),
                                    onClick = viewModel::toggleLock,
                                )
                                CommandButton(
                                    label = if (state.state.climateOn) "Climate off" else "Climate on",
                                    pending = pending == Command.Climate,
                                    enabled = enabledFor(Command.Climate),
                                    error = commandError.messageFor(Command.Climate),
                                    onClick = viewModel::toggleClimate,
                                )
                                CommandButton(
                                    label = if (state.state.windowsOpen) "Close windows" else "Vent windows",
                                    pending = pending == Command.Windows,
                                    enabled = enabledFor(Command.Windows),
                                    error = commandError.messageFor(Command.Windows),
                                    onClick = viewModel::toggleWindows,
                                )
                                if (state.state.pluggedIn) {
                                    CommandButton(
                                        label = if (state.state.chargingState == ChargingState.Charging) "Stop charging" else "Start charging",
                                        pending = pending == Command.Charging,
                                        enabled = enabledFor(Command.Charging),
                                        error = commandError.messageFor(Command.Charging),
                                        onClick = viewModel::toggleCharging,
                                    )
                                }
                                CommandButton(
                                    label = "Refresh",
                                    pending = pending == Command.Refresh,
                                    enabled = enabledFor(Command.Refresh),
                                    error = commandError.messageFor(Command.Refresh),
                                    onClick = viewModel::refresh,
                                )
                            }
                        }

                        is VehicleUiState.Asleep -> {
                            state.cached?.let { DashboardRows(it) }
                            UpdatedAtLine(updatedAtMs = state.updatedAtMs, badge = "Asleep")
                            Column(modifier = Modifier.fillMaxWidth().padding(top = 1f.gridUnitsAsDp())) {
                                CommandButton(
                                    label = "Wake",
                                    pending = pending == Command.Wake,
                                    enabled = enabledFor(Command.Wake),
                                    error = commandError.messageFor(Command.Wake),
                                    onClick = viewModel::wake,
                                )
                            }
                        }

                        is VehicleUiState.Error -> {
                            state.cached?.let { DashboardRows(it) }
                            UpdatedAtLine(updatedAtMs = state.updatedAtMs)
                            LightText(
                                text = state.kind.name,
                                variant = LightTextVariant.Detail,
                                lighten = true,
                                modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                            )
                            Column(modifier = Modifier.fillMaxWidth().padding(top = 1f.gridUnitsAsDp())) {
                                CommandButton(
                                    label = "Refresh",
                                    pending = pending == Command.Refresh,
                                    enabled = enabledFor(Command.Refresh),
                                    error = commandError.messageFor(Command.Refresh),
                                    onClick = viewModel::refresh,
                                )
                            }
                        }
                    }

                    // Nav links to Charge/Climate screens: uncommented in Tasks 7/8.
                    // LightText(
                    //     text = "Charging →",
                    //     variant = LightTextVariant.Copy,
                    //     modifier = Modifier.lightClickable { navigateTo(::ChargeScreen) },
                    // )
                    // LightText(
                    //     text = "Climate →",
                    //     variant = LightTextVariant.Copy,
                    //     modifier = Modifier.lightClickable { navigateTo(::ClimateScreen) },
                    // )
                }
            }
        }
    }
}

// StatusRow has no lighten mode (spec's Task 6 "lightened if easy" wording, not built into the
// component yet); cached rows render with the same weight as live rows for now.
@Composable
private fun DashboardRows(state: VehicleState) {
    StatusRow(label = "Battery", value = "${state.batteryPercent}% · ${formatRange(state.rangeKm)}")
    StatusRow(
        label = "Charging",
        value = "${state.chargingState.name} · limit ${state.chargeLimitPercent}%",
    )
    StatusRow(
        label = "Climate",
        value = "${if (state.climateOn) "On" else "Off"} · ${state.insideTempC?.let(::formatTemp) ?: "—"} inside",
    )
    StatusRow(label = "Locked", value = if (state.locked) "Locked" else "Unlocked")
    StatusRow(label = "Windows", value = if (state.windowsOpen) "Open" else "Closed")
}
