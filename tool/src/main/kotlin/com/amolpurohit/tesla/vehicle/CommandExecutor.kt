package com.amolpurohit.tesla.vehicle

/**
 * Narrow seam over [SignedCommandService] so [RealVehicleRepository] (and its
 * tests) don't depend on the concrete signing/session-handshake stack — only
 * on "send this [VehicleCommand] to this vehicle, get back a [CommandResult]".
 * [SignedCommandService] implements this directly; tests substitute a
 * recording fake.
 */
interface CommandExecutor {
    suspend fun execute(vehicleId: String, command: VehicleCommand): CommandResult
}
