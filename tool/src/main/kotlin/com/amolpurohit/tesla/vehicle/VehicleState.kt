package com.amolpurohit.tesla.vehicle

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ChargingState {
    @SerialName("Disconnected") Disconnected,
    @SerialName("Stopped") Stopped,
    @SerialName("Charging") Charging,
    @SerialName("Complete") Complete,
}

@Serializable
enum class OverheatProtectionMode {
    @SerialName("Off") Off,
    @SerialName("NoAc") NoAc,
    @SerialName("Ac") Ac,
}

// Cache format (StateCache persists this as JSON). Additive changes are safe:
// unknown keys are ignored and new fields need defaults. Renames/removals/type
// changes are breaking — the cache then decodes to null and the UI falls back
// to a fresh fetch. No schema version on purpose; that fallback is the recovery.
@Serializable
data class VehicleState(
    val batteryPercent: Int,
    val rangeKm: Double,
    val chargingState: ChargingState,
    val pluggedIn: Boolean,
    val chargeLimitPercent: Int,
    val chargeAmps: Int,
    val maxChargeAmps: Int,
    val insideTempC: Double?,
    val climateOn: Boolean,
    val targetTempC: Double,
    val minTargetTempC: Double = 15.0,
    val maxTargetTempC: Double = 28.0,
    val overheatProtection: OverheatProtectionMode,
    val dogModeOn: Boolean,
    val locked: Boolean,
    val windowsOpen: Boolean,
    val asleep: Boolean,
)
