package com.amolpurohit.tesla.vehicle

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class VehicleStateTest {
    @Test fun `state round-trips through json`() {
        val s = VehicleState(
            batteryPercent = 72, rangeKm = 340.5, chargingState = ChargingState.Charging,
            pluggedIn = true, chargeLimitPercent = 80, chargeAmps = 16, maxChargeAmps = 32,
            insideTempC = 24.5, climateOn = false, targetTempC = 21.0,
            overheatProtection = OverheatProtectionMode.NoAc, dogModeOn = false,
            locked = true, windowsOpen = false, asleep = false,
        )
        val json = Json { ignoreUnknownKeys = true }
        assertEquals(s, json.decodeFromString<VehicleState>(json.encodeToString(VehicleState.serializer(), s)))
    }
}
