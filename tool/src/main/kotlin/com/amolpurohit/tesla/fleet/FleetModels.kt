package com.amolpurohit.tesla.fleet

import com.amolpurohit.tesla.vehicle.ChargingState
import com.amolpurohit.tesla.vehicle.OverheatProtectionMode
import com.amolpurohit.tesla.vehicle.VehicleState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Cheap summary from GET /api/1/vehicles — no charge/climate detail. */
data class VehicleSummary(
    val id: String,
    val vin: String,
    val name: String,
    val state: String,
)

/** Raw base64 `response` field from a signed_command reply; VCP decoding is not this layer's job. */
data class SignedCommandResponse(val responseB64: String)

// --- Wire DTOs. Fleet API wraps every payload in a top-level "response" key. ---

@Serializable
internal data class FleetEnvelope<T>(val response: T? = null)

@Serializable
internal data class VehicleSummaryDto(
    @SerialName("id_s") val idS: String,
    val vin: String,
    @SerialName("display_name") val displayName: String? = null,
    val state: String? = null,
)

@Serializable
internal data class SignedCommandResponseDto(val response: String? = null)

@Serializable
internal data class VehicleDataDto(
    val state: String? = null,
    @SerialName("charge_state") val chargeState: ChargeStateDto? = null,
    @SerialName("climate_state") val climateState: ClimateStateDto? = null,
    @SerialName("vehicle_state") val vehicleState: VehicleStateDto? = null,
)

@Serializable
internal data class ChargeStateDto(
    @SerialName("battery_level") val batteryLevel: Int = 0,
    // Miles, per Tesla Fleet API convention; converted to km in toVehicleState().
    @SerialName("battery_range") val batteryRangeMiles: Double = 0.0,
    @SerialName("charging_state") val chargingState: ChargingState = ChargingState.Disconnected,
    @SerialName("charge_limit_soc") val chargeLimitSoc: Int = 0,
    @SerialName("charge_amps") val chargeAmps: Int = 0,
    @SerialName("charge_current_request_max") val chargeCurrentRequestMax: Int = 0,
    @SerialName("charge_port_latch") val chargePortLatch: String? = null,
    @SerialName("conn_charge_cable") val connChargeCable: String? = null,
)

@Serializable
internal data class ClimateStateDto(
    @SerialName("inside_temp") val insideTemp: Double? = null,
    @SerialName("is_climate_on") val isClimateOn: Boolean = false,
    @SerialName("driver_temp_setting") val driverTempSetting: Double = 0.0,
    @SerialName("min_avail_temp") val minAvailTemp: Double = 15.0,
    @SerialName("max_avail_temp") val maxAvailTemp: Double = 28.0,
    // "On" -> Ac, "FanOnly" -> NoAc, "Off" -> Off. See OverheatProtectionDtoMode below.
    @SerialName("cabin_overheat_protection") val cabinOverheatProtection: String? = null,
    @SerialName("climate_keeper_mode") val climateKeeperMode: String? = null,
)

@Serializable
internal data class VehicleStateDto(
    val locked: Boolean = false,
    @SerialName("fd_window") val fdWindow: Int = 0,
    @SerialName("fp_window") val fpWindow: Int = 0,
    @SerialName("rd_window") val rdWindow: Int = 0,
    @SerialName("rp_window") val rpWindow: Int = 0,
)

private const val MILES_TO_KM = 1.609344

/**
 * Maps the Fleet API's raw vehicle_data payload onto our domain [VehicleState].
 *
 * pluggedIn heuristic: conn_charge_cable is present and not the sentinel
 * "<invalid>" Tesla uses for "no cable connected". charge_port_latch ==
 * "Engaged" is treated as corroborating evidence (either signal is enough)
 * since some vehicle firmware versions omit conn_charge_cable while a cable
 * is physically latched.
 *
 * cabin_overheat_protection mapping: "On" -> Ac (active cooling), "FanOnly"
 * -> NoAc (fan circulation only, no compressor), "Off" -> Off (disabled).
 * Unrecognized values fall back to Off.
 */
internal fun VehicleDataDto.toVehicleState(): VehicleState {
    // We explicitly request all three blocks via the endpoints query param,
    // so any missing block means a degraded response (asleep car, or server
    // ignoring the filter). Defaulting the missing block would fabricate a
    // plausible-but-wrong VehicleState (0% battery, unlocked, overheat Off) —
    // refuse instead and let the repository fall back to cached state.
    val missing = listOfNotNull(
        "charge_state".takeIf { chargeState == null },
        "climate_state".takeIf { climateState == null },
        "vehicle_state".takeIf { vehicleState == null },
    )
    if (missing.isNotEmpty()) {
        throw FleetPartialDataException(missing.joinToString(","))
    }
    val charge = requireNotNull(chargeState)
    val climate = requireNotNull(climateState)
    val vehicle = requireNotNull(vehicleState)

    val cableConnected = charge.connChargeCable != null && charge.connChargeCable != "<invalid>"
    val latchEngaged = charge.chargePortLatch == "Engaged"

    val windowsOpen = vehicle.fdWindow != 0 ||
        vehicle.fpWindow != 0 ||
        vehicle.rdWindow != 0 ||
        vehicle.rpWindow != 0

    val overheatProtection = when (climate.cabinOverheatProtection) {
        "On" -> OverheatProtectionMode.Ac
        "FanOnly" -> OverheatProtectionMode.NoAc
        "Off" -> OverheatProtectionMode.Off
        else -> OverheatProtectionMode.Off
    }

    return VehicleState(
        batteryPercent = charge.batteryLevel,
        rangeKm = charge.batteryRangeMiles * MILES_TO_KM,
        chargingState = charge.chargingState,
        pluggedIn = cableConnected || latchEngaged,
        chargeLimitPercent = charge.chargeLimitSoc,
        chargeAmps = charge.chargeAmps,
        maxChargeAmps = charge.chargeCurrentRequestMax,
        insideTempC = climate.insideTemp,
        climateOn = climate.isClimateOn,
        targetTempC = climate.driverTempSetting,
        minTargetTempC = climate.minAvailTemp,
        maxTargetTempC = climate.maxAvailTemp,
        overheatProtection = overheatProtection,
        dogModeOn = climate.climateKeeperMode == "dog",
        locked = vehicle.locked,
        windowsOpen = windowsOpen,
        asleep = state != "online",
    )
}
