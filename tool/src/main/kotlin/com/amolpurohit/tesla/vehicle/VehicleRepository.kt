package com.amolpurohit.tesla.vehicle

import kotlinx.coroutines.flow.StateFlow

sealed interface CommandResult {
    data object Success : CommandResult
    data class Rejected(val reason: String) : CommandResult
    data class Failed(val kind: ErrorKind) : CommandResult
}

// Naming note: diverges deliberately from spec §4.4's sketch (setClimateOn(Boolean) vs
// climateOn()/climateOff(); VehicleUiState gains NoCredentials) — same shape, tighter surface.
interface VehicleRepository {
    val state: StateFlow<VehicleUiState>
    suspend fun refresh()
    suspend fun wake(): CommandResult
    suspend fun lock(): CommandResult
    suspend fun unlock(): CommandResult
    suspend fun startCharging(): CommandResult
    suspend fun stopCharging(): CommandResult
    suspend fun setChargeLimit(percent: Int): CommandResult
    suspend fun setChargeAmps(amps: Int): CommandResult
    suspend fun setClimateOn(on: Boolean): CommandResult
    suspend fun setTargetTemp(celsius: Double): CommandResult
    suspend fun setOverheatProtection(mode: OverheatProtectionMode): CommandResult
    suspend fun setDogMode(on: Boolean): CommandResult
    suspend fun ventWindows(): CommandResult
    suspend fun closeWindows(): CommandResult
}
