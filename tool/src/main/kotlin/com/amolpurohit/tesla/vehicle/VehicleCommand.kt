package com.amolpurohit.tesla.vehicle

import com.amolpurohit.tesla.vcp.CarServerActions
import com.amolpurohit.tesla.vcp.VcpDomain
import com.amolpurohit.tesla.vcp.VcsecMessages

/**
 * The in-scope Fleet API signed commands, each carrying its own parameters
 * (if any) and knowing which [domain] and plaintext application-proto bytes
 * ([plaintextAction]) it maps to. Domain routing and byte encoding are
 * reused as-is from [VcsecMessages]/[CarServerActions] (Tasks 20-22) — see
 * `scripts/tesla/vcp-fixtures/README.md` "Command -> domain routing" table.
 */
sealed class VehicleCommand(val domain: VcpDomain) {
    abstract val plaintextAction: ByteArray

    data object Lock : VehicleCommand(VcpDomain.VEHICLE_SECURITY) {
        override val plaintextAction get() = VcsecMessages.rkeAction(VcsecMessages.RKE_ACTION_LOCK)
    }

    data object Unlock : VehicleCommand(VcpDomain.VEHICLE_SECURITY) {
        override val plaintextAction get() = VcsecMessages.rkeAction(VcsecMessages.RKE_ACTION_UNLOCK)
    }

    data object StartCharging : VehicleCommand(VcpDomain.INFOTAINMENT) {
        override val plaintextAction get() = CarServerActions.chargeStart()
    }

    data object StopCharging : VehicleCommand(VcpDomain.INFOTAINMENT) {
        override val plaintextAction get() = CarServerActions.chargeStop()
    }

    data class SetChargeLimit(val percent: Int) : VehicleCommand(VcpDomain.INFOTAINMENT) {
        override val plaintextAction get() = CarServerActions.setChargeLimit(percent)
    }

    data class SetChargeAmps(val amps: Int) : VehicleCommand(VcpDomain.INFOTAINMENT) {
        override val plaintextAction get() = CarServerActions.setChargingAmps(amps)
    }

    data object ClimateOn : VehicleCommand(VcpDomain.INFOTAINMENT) {
        override val plaintextAction get() = CarServerActions.climate(powerOn = true)
    }

    data object ClimateOff : VehicleCommand(VcpDomain.INFOTAINMENT) {
        override val plaintextAction get() = CarServerActions.climate(powerOn = false)
    }

    /** Sets both driver and passenger target temperature to the same value, per the UI's single-temp control. */
    data class SetTemp(val celsius: Float) : VehicleCommand(VcpDomain.INFOTAINMENT) {
        override val plaintextAction get() = CarServerActions.setTemperature(celsius, celsius)
    }

    data class SetOverheatProtection(val mode: OverheatProtectionMode) : VehicleCommand(VcpDomain.INFOTAINMENT) {
        override val plaintextAction
            get() = when (mode) {
                OverheatProtectionMode.Off -> CarServerActions.setCabinOverheatProtection(on = false, fanOnly = false)
                OverheatProtectionMode.NoAc -> CarServerActions.setCabinOverheatProtection(on = true, fanOnly = true)
                OverheatProtectionMode.Ac -> CarServerActions.setCabinOverheatProtection(on = true, fanOnly = false)
            }
    }

    data class SetDogMode(val on: Boolean) : VehicleCommand(VcpDomain.INFOTAINMENT) {
        override val plaintextAction
            get() = CarServerActions.dogMode(
                if (on) CarServerActions.CLIMATE_KEEPER_ACTION_DOG else CarServerActions.CLIMATE_KEEPER_ACTION_OFF,
            )
    }

    data object VentWindows : VehicleCommand(VcpDomain.INFOTAINMENT) {
        override val plaintextAction get() = CarServerActions.ventWindows()
    }

    data object CloseWindows : VehicleCommand(VcpDomain.INFOTAINMENT) {
        override val plaintextAction get() = CarServerActions.closeWindows()
    }
}
