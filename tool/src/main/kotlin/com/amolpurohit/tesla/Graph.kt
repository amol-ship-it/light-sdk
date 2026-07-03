package com.amolpurohit.tesla

import com.amolpurohit.tesla.vehicle.FakeVehicleRepository
import com.amolpurohit.tesla.vehicle.VehicleRepository

object Graph {
    @Volatile private var repo: VehicleRepository? = null
    fun repository(): VehicleRepository =
        repo ?: synchronized(this) { repo ?: FakeVehicleRepository(latencyMs = 400).also { repo = it } }
    // test seam:
    fun override(r: VehicleRepository) { repo = r }
}
