package com.amolpurohit.tesla.vehicle

import kotlinx.serialization.Serializable

@Serializable
enum class ChargingState { Disconnected, Stopped, Charging, Complete }

@Serializable
enum class OverheatProtectionMode { Off, NoAc, Ac }

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
