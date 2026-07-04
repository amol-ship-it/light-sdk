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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewModelScope
import com.amolpurohit.tesla.auth.CredentialStore
import com.amolpurohit.tesla.auth.SetupPayload
import com.amolpurohit.tesla.auth.TokenManager
import com.amolpurohit.tesla.fleet.FleetApi
import com.amolpurohit.tesla.fleet.FleetClient
import com.amolpurohit.tesla.fleet.VehicleSummary
import com.amolpurohit.tesla.store.DataStoreKeyValueStore
import com.amolpurohit.tesla.ui.components.CommandButton
import com.thelightphone.sdk.LightQrCodeScanner
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
import com.thelightphone.sdk.ui.lightClickable
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Steps of the one-time QR credential handoff (spec: scan -> pick vehicle -> done). */
sealed interface SetupStep {
    data object Scanning : SetupStep
    data class NeedMore(val have: Int, val of: Int) : SetupStep
    data object ListingVehicles : SetupStep
    data class PickingVehicle(val vehicles: List<VehicleSummary>) : SetupStep
    data object Done : SetupStep
    data class Failed(val reason: String, val canRetryListing: Boolean) : SetupStep
}

/**
 * Drives the QR scan -> credential persist -> vehicle list -> pick flow.
 *
 * [apiFactory] builds a [FleetApi] scoped to the just-decoded [SetupPayload] (its region
 * selects the Fleet API base URL); production wiring is in [SetupScreen.createViewModel].
 *
 * Never logs or stringifies [SetupPayload] — it carries the refresh token and private key.
 */
class SetupScreenViewModel(
    private val credentials: CredentialStore,
    private val apiFactory: (SetupPayload) -> FleetApi,
) : LightViewModel<Unit>() {

    private val _step = MutableStateFlow<SetupStep>(SetupStep.Scanning)
    val step: StateFlow<SetupStep> = _step.asStateFlow()

    // Raw scans accumulated for the current multi-part attempt. A Failed retry clears this and
    // starts over rather than trying to preserve partial multi-part progress across an invalid
    // scan — mixing "some old parts, some new" is confusing to reconstruct correctly and to
    // explain in the UI, so we accept re-scanning all parts as the simpler, safer behavior.
    private val scans = mutableListOf<String>()

    // Built once when a payload completes, so retry() after a listVehicles() failure re-lists
    // over the same client instead of constructing (and leaking) a fresh FleetClient per attempt.
    private var api: FleetApi? = null

    override fun onCleared() {
        super.onCleared()
        (api as? java.io.Closeable)?.close()
    }

    fun onScan(text: String) {
        // The UI only renders the scanner in Scanning/NeedMore, but enforce the
        // invariant here too: a stray scan during listing/picking must not race
        // persistAndListVehicles or mutate accumulated state.
        if (_step.value !is SetupStep.Scanning && _step.value !is SetupStep.NeedMore) return

        scans += text
        when (val result = SetupPayload.fromScans(scans)) {
            is SetupPayload.Complete -> {
                scans.clear()
                persistAndListVehicles(result.payload)
            }
            is SetupPayload.NeedMore -> {
                _step.value = SetupStep.NeedMore(have = result.have.size, of = result.of)
            }
            is SetupPayload.Invalid -> {
                scans.clear()
                _step.value = SetupStep.Failed(reason = result.reason, canRetryListing = false)
            }
        }
    }

    private fun persistAndListVehicles(payload: SetupPayload) {
        viewModelScope.launch {
            // Persist BEFORE listing: downstream wiring (Task 18) reads credentials from the
            // store, so they must be durable by the time PickingVehicle is entered.
            credentials.save(payload)
            api = apiFactory(payload)
            listVehicles()
        }
    }

    private suspend fun listVehicles() {
        val fleetApi = api ?: return
        _step.value = SetupStep.ListingVehicles
        try {
            val vehicles = fleetApi.listVehicles()
            _step.value = if (vehicles.isEmpty()) {
                SetupStep.Failed(reason = "No vehicles found on this Tesla account.", canRetryListing = true)
            } else {
                SetupStep.PickingVehicle(vehicles)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Messages on this path are format/HTTP-level only (see FleetException docs);
            // they never carry payload contents.
            _step.value = SetupStep.Failed(
                reason = "Couldn't reach Tesla: ${e.message ?: "unknown error"}",
                canRetryListing = true,
            )
        }
    }

    fun pick(vehicle: VehicleSummary) {
        viewModelScope.launch {
            credentials.saveVehicle(id = vehicle.id, vin = vehicle.vin, name = vehicle.name)
            _step.value = SetupStep.Done
            // Task 18: Graph.reset() here
        }
    }

    fun retry() {
        val failed = _step.value as? SetupStep.Failed ?: return
        if (failed.canRetryListing && api != null) {
            // Credentials already persisted for this payload; re-attempt listing only.
            viewModelScope.launch { listVehicles() }
        } else {
            scans.clear()
            _step.value = SetupStep.Scanning
        }
    }
}

class SetupScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, SetupScreenViewModel>(sealedActivity) {

    override val viewModelClass: Class<SetupScreenViewModel>
        get() = SetupScreenViewModel::class.java

    override fun createViewModel(): SetupScreenViewModel {
        val credentials = CredentialStore(DataStoreKeyValueStore(lightContext.dataStore))
        return SetupScreenViewModel(
            credentials = credentials,
            apiFactory = { payload ->
                // One OkHttp engine shared by the token exchange and the Fleet API client.
                // Direct wiring for now; Task 18 may rewire this through Graph.
                val engine = OkHttp.create()
                val tokens = TokenManager(HttpClient(engine), credentials)
                FleetClient(engine, tokens, payload.region)
            },
        )
    }

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val step by viewModel.step.collectAsState()

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier.fillMaxSize().background(LightThemeTokens.colors.background),
            ) {
                when (val currentStep = step) {
                    is SetupStep.Scanning, is SetupStep.NeedMore -> {
                        // LightQrCodeScanner delivers only ONE scan per composition (internal
                        // one-shot latch), so re-key it on every accepted scan or the second/
                        // third part of a multi-part payload would never be delivered.
                        var scanAttempts by remember { mutableStateOf(0) }
                        key(scanAttempts) {
                            LightQrCodeScanner(
                                title = "Scan setup code",
                                onScanned = { text ->
                                    scanAttempts++
                                    viewModel.onScan(text)
                                },
                                onBack = { goBack() },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        if (currentStep is SetupStep.NeedMore) {
                            LightText(
                                text = "Scanned ${currentStep.have} of ${currentStep.of}",
                                variant = LightTextVariant.Copy,
                                align = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.BottomCenter)
                                    .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 2f.gridUnitsAsDp()),
                            )
                        }
                    }

                    is SetupStep.ListingVehicles -> {
                        LightScrollView(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 1f.gridUnitsAsDp()),
                        ) {
                            LightText(
                                text = "Setup",
                                variant = LightTextVariant.Heading,
                                modifier = Modifier.padding(vertical = 1f.gridUnitsAsDp()),
                            )
                            LightText(text = "Looking up your vehicles…", variant = LightTextVariant.Copy)
                        }
                    }

                    is SetupStep.PickingVehicle -> {
                        LightScrollView(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 1f.gridUnitsAsDp()),
                        ) {
                            LightText(
                                text = "Choose a vehicle",
                                variant = LightTextVariant.Heading,
                                modifier = Modifier.padding(vertical = 1f.gridUnitsAsDp()),
                            )
                            Column(modifier = Modifier.fillMaxWidth()) {
                                currentStep.vehicles.forEach { vehicle ->
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .lightClickable { viewModel.pick(vehicle) }
                                            .padding(vertical = 0.75f.gridUnitsAsDp()),
                                    ) {
                                        LightText(text = vehicle.name, variant = LightTextVariant.Copy)
                                        LightText(
                                            text = vehicle.vin,
                                            variant = LightTextVariant.Detail,
                                            lighten = true,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    is SetupStep.Done -> {
                        LightScrollView(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 1f.gridUnitsAsDp()),
                        ) {
                            LightText(
                                text = "Setup",
                                variant = LightTextVariant.Heading,
                                modifier = Modifier.padding(vertical = 1f.gridUnitsAsDp()),
                            )
                            LightText(text = "Vehicle linked.", variant = LightTextVariant.Copy)
                            Column(modifier = Modifier.fillMaxWidth().padding(top = 1f.gridUnitsAsDp())) {
                                CommandButton(
                                    label = "Verify key",
                                    pending = false,
                                    enabled = false,
                                    error = "Available after M4",
                                    onClick = {},
                                )
                            }
                        }
                    }

                    is SetupStep.Failed -> {
                        LightScrollView(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 1f.gridUnitsAsDp()),
                        ) {
                            LightText(
                                text = "Setup",
                                variant = LightTextVariant.Heading,
                                modifier = Modifier.padding(vertical = 1f.gridUnitsAsDp()),
                            )
                            LightText(text = currentStep.reason, variant = LightTextVariant.Copy)
                            Column(modifier = Modifier.fillMaxWidth().padding(top = 1f.gridUnitsAsDp())) {
                                CommandButton(
                                    label = "Retry",
                                    pending = false,
                                    enabled = true,
                                    onClick = viewModel::retry,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
