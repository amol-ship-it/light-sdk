package com.amolpurohit.tesla.vcp

/**
 * Hand-written encoders for the `CarServer.Action` message tree, built strictly
 * on [ProtoWriter]. Field numbers are transcribed from the vendored
 * `car_server.proto` under `scripts/tesla/vcp-fixtures/upstream/pkg/protocol/protobuf/`
 * (pinned v0.4.1) — cited per constant below. `common.proto:12` defines
 * `message Void {}` (zero fields, so it always encodes as a zero-length
 * length-delimited value).
 *
 * These functions produce only the plaintext application-layer bytes
 * (`CarServer.Action`), matching `commands.json`'s `plaintext_action_b64` for
 * the 14 Infotainment-domain commands. No signing/encryption here (Task 23).
 */
object CarServerActions {

    // car_server.proto:13 `message Action { oneof action_msg { VehicleAction vehicleAction = 2; } }`
    private const val ACTION_VEHICLE_ACTION = 2

    private fun action(vehicleAction: ProtoWriter): ByteArray {
        return ProtoWriter().message(ACTION_VEHICLE_ACTION, vehicleAction).toByteArray()
    }

    // ---- VehicleAction oneof field numbers (car_server.proto:20-81) ----
    private const val VEHICLE_ACTION_CHARGING_SET_LIMIT = 5 // car_server.proto:26
    private const val VEHICLE_ACTION_CHARGING_START_STOP = 6 // car_server.proto:27
    private const val VEHICLE_ACTION_HVAC_AUTO = 10 // car_server.proto:31
    private const val VEHICLE_ACTION_HVAC_TEMPERATURE_ADJUSTMENT = 14 // car_server.proto:34
    private const val VEHICLE_ACTION_VEHICLE_CONTROL_WINDOW = 34 // car_server.proto:51
    private const val VEHICLE_ACTION_SET_CHARGING_AMPS = 43 // car_server.proto:56
    private const val VEHICLE_ACTION_HVAC_CLIMATE_KEEPER = 44 // car_server.proto:57
    private const val VEHICLE_ACTION_SET_CABIN_OVERHEAT_PROTECTION = 50 // car_server.proto:61

    /** An empty sub-message (`Void{}` etc.) — length-delimited, zero-length content. */
    private fun emptyMessage(): ProtoWriter = ProtoWriter()

    // ---- ChargingStartStopAction (car_server.proto:178-186) ----
    private const val CHARGING_START_STOP_START = 2 // car_server.proto:181
    private const val CHARGING_START_STOP_STOP = 5 // car_server.proto:184

    fun chargeStart(): ByteArray = action(
        ProtoWriter().message(
            VEHICLE_ACTION_CHARGING_START_STOP,
            ProtoWriter().message(CHARGING_START_STOP_START, emptyMessage())
        )
    )

    fun chargeStop(): ByteArray = action(
        ProtoWriter().message(
            VEHICLE_ACTION_CHARGING_START_STOP,
            ProtoWriter().message(CHARGING_START_STOP_STOP, emptyMessage())
        )
    )

    // ---- ChargingSetLimitAction (car_server.proto:174-176) ----
    private const val CHARGING_SET_LIMIT_PERCENT = 1 // car_server.proto:175

    fun setChargeLimit(percent: Int): ByteArray = action(
        ProtoWriter().message(
            VEHICLE_ACTION_CHARGING_SET_LIMIT,
            ProtoWriter().varint(CHARGING_SET_LIMIT_PERCENT, percent.toLong())
        )
    )

    // ---- SetChargingAmpsAction (car_server.proto:456-458) ----
    private const val SET_CHARGING_AMPS_AMPS = 1 // car_server.proto:457

    fun setChargingAmps(amps: Int): ByteArray = action(
        ProtoWriter().message(
            VEHICLE_ACTION_SET_CHARGING_AMPS,
            ProtoWriter().varint(SET_CHARGING_AMPS_AMPS, amps.toLong())
        )
    )

    // ---- HvacAutoAction (car_server.proto:204-207) ----
    private const val HVAC_AUTO_POWER_ON = 1 // car_server.proto:205

    fun climate(powerOn: Boolean): ByteArray {
        val hvacAuto = ProtoWriter()
        if (powerOn) {
            // proto3 default (false) is never encoded.
            hvacAuto.varint(HVAC_AUTO_POWER_ON, 1)
        }
        return action(ProtoWriter().message(VEHICLE_ACTION_HVAC_AUTO, hvacAuto))
    }

    // ---- HvacTemperatureAdjustmentAction (car_server.proto:270-293) ----
    private const val HVAC_TEMP_LEVEL = 5 // car_server.proto:289
    private const val HVAC_TEMP_DRIVER_CELSIUS = 6 // car_server.proto:291
    private const val HVAC_TEMP_PASSENGER_CELSIUS = 7 // car_server.proto:292
    private const val HVAC_TEMP_LEVEL_TEMP_MAX = 3 // car_server.proto:275 (Temperature.type oneof)

    fun setTemperature(driverCelsius: Float, passengerCelsius: Float): ByteArray {
        val level = ProtoWriter().message(HVAC_TEMP_LEVEL_TEMP_MAX, emptyMessage())
        val hvacTemp = ProtoWriter()
            .message(HVAC_TEMP_LEVEL, level)
            .fixed32(HVAC_TEMP_DRIVER_CELSIUS, floatToRawBits(driverCelsius))
            .fixed32(HVAC_TEMP_PASSENGER_CELSIUS, floatToRawBits(passengerCelsius))
        return action(ProtoWriter().message(VEHICLE_ACTION_HVAC_TEMPERATURE_ADJUSTMENT, hvacTemp))
    }

    private fun floatToRawBits(f: Float): Int = java.lang.Float.floatToRawIntBits(f)

    // ---- SetCabinOverheatProtectionAction (car_server.proto:480-483) ----
    private const val OVERHEAT_ON = 1 // car_server.proto:481
    private const val OVERHEAT_FAN_ONLY = 2 // car_server.proto:482

    fun setCabinOverheatProtection(on: Boolean, fanOnly: Boolean): ByteArray {
        val overheat = ProtoWriter()
        // proto3 defaults (false) are never encoded; order matches fixtures: on, then fan_only.
        if (on) overheat.varint(OVERHEAT_ON, 1)
        if (fanOnly) overheat.varint(OVERHEAT_FAN_ONLY, 1)
        return action(ProtoWriter().message(VEHICLE_ACTION_SET_CABIN_OVERHEAT_PROTECTION, overheat))
    }

    // ---- HvacClimateKeeperAction (car_server.proto:442-453) ----
    private const val CLIMATE_KEEPER_ACTION = 1 // car_server.proto:451
    const val CLIMATE_KEEPER_ACTION_OFF = 0 // car_server.proto:445
    const val CLIMATE_KEEPER_ACTION_DOG = 2 // car_server.proto:447

    fun dogMode(climateKeeperAction: Int): ByteArray {
        val keeper = ProtoWriter()
        if (climateKeeperAction != 0) {
            // proto3 default enum value (0) is never encoded.
            keeper.varint(CLIMATE_KEEPER_ACTION, climateKeeperAction.toLong())
        }
        return action(ProtoWriter().message(VEHICLE_ACTION_HVAC_CLIMATE_KEEPER, keeper))
    }

    // ---- VehicleControlWindowAction (car_server.proto:396-403) ----
    private const val WINDOW_VENT = 3 // car_server.proto:400
    private const val WINDOW_CLOSE = 4 // car_server.proto:401

    fun ventWindows(): ByteArray = action(
        ProtoWriter().message(
            VEHICLE_ACTION_VEHICLE_CONTROL_WINDOW,
            ProtoWriter().message(WINDOW_VENT, emptyMessage())
        )
    )

    fun closeWindows(): ByteArray = action(
        ProtoWriter().message(
            VEHICLE_ACTION_VEHICLE_CONTROL_WINDOW,
            ProtoWriter().message(WINDOW_CLOSE, emptyMessage())
        )
    )
}
