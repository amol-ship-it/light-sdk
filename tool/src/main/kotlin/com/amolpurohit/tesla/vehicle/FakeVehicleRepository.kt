package com.amolpurohit.tesla.vehicle

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory [VehicleRepository] that mirrors real vehicle semantics closely enough to power
 * UI development and tests without a network round-trip. No delays by default so tests stay
 * fast; pass [latencyMs] to emulate command round-trip time (e.g. in the emulator).
 */
class FakeVehicleRepository(
    initial: VehicleState = DEFAULT,
    private val latencyMs: Long = 0,
) : VehicleRepository {

    private var vehicle: VehicleState = initial

    private val _state = MutableStateFlow<VehicleUiState>(uiStateFor(vehicle))
    override val state: StateFlow<VehicleUiState> = _state.asStateFlow()

    override suspend fun refresh() {
        maybeDelay()
        _state.value = uiStateFor(vehicle)
    }

    override suspend fun wake(): CommandResult {
        maybeDelay()
        vehicle = vehicle.copy(asleep = false)
        emitReady()
        return CommandResult.Success
    }

    override suspend fun lock(): CommandResult = mutate { it.copy(locked = true) }

    override suspend fun unlock(): CommandResult = mutate { it.copy(locked = false) }

    override suspend fun startCharging(): CommandResult {
        maybeDelay()
        if (!vehicle.pluggedIn) return CommandResult.Rejected("Not plugged in")
        vehicle = vehicle.copy(chargingState = ChargingState.Charging)
        emitReady()
        return CommandResult.Success
    }

    override suspend fun stopCharging(): CommandResult =
        mutate { it.copy(chargingState = ChargingState.Stopped) }

    override suspend fun setChargeLimit(percent: Int): CommandResult =
        mutate { it.copy(chargeLimitPercent = percent.coerceIn(50, 100)) }

    override suspend fun setChargeAmps(amps: Int): CommandResult =
        mutate { it.copy(chargeAmps = amps.coerceIn(5, it.maxChargeAmps)) }

    override suspend fun setClimateOn(on: Boolean): CommandResult =
        mutate { it.copy(climateOn = on) }

    override suspend fun setTargetTemp(celsius: Double): CommandResult =
        mutate { it.copy(targetTempC = celsius.coerceIn(it.minTargetTempC, it.maxTargetTempC)) }

    override suspend fun setOverheatProtection(mode: OverheatProtectionMode): CommandResult =
        mutate { it.copy(overheatProtection = mode) }

    override suspend fun setDogMode(on: Boolean): CommandResult =
        mutate { it.copy(dogModeOn = on) }

    override suspend fun ventWindows(): CommandResult =
        mutate { it.copy(windowsOpen = true) }

    override suspend fun closeWindows(): CommandResult =
        mutate { it.copy(windowsOpen = false) }

    private suspend fun mutate(transform: (VehicleState) -> VehicleState): CommandResult {
        maybeDelay()
        vehicle = transform(vehicle)
        emitReady()
        return CommandResult.Success
    }

    private suspend fun maybeDelay() {
        if (latencyMs > 0) delay(latencyMs)
    }

    private fun emitReady() {
        _state.value = uiStateFor(vehicle)
    }

    private fun uiStateFor(v: VehicleState): VehicleUiState =
        if (v.asleep) {
            VehicleUiState.Asleep(cached = v, updatedAtMs = System.currentTimeMillis())
        } else {
            VehicleUiState.Ready(state = v, updatedAtMs = System.currentTimeMillis(), stale = false)
        }

    companion object {
        val DEFAULT = VehicleState(
            batteryPercent = 72,
            rangeKm = 340.0,
            chargingState = ChargingState.Stopped,
            pluggedIn = true,
            chargeLimitPercent = 80,
            chargeAmps = 16,
            maxChargeAmps = 32,
            insideTempC = 21.0,
            climateOn = false,
            targetTempC = 21.0,
            overheatProtection = OverheatProtectionMode.NoAc,
            dogModeOn = false,
            locked = true,
            windowsOpen = false,
            asleep = false,
        )
    }
}
