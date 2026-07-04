package com.amolpurohit.tesla

import com.amolpurohit.tesla.auth.CredentialStore
import com.amolpurohit.tesla.auth.SetupPayload
import com.amolpurohit.tesla.store.InMemoryKeyValueStore
import com.amolpurohit.tesla.vehicle.RealVehicleRepository
import com.amolpurohit.tesla.vehicle.StateCache
import com.amolpurohit.tesla.vehicle.VehicleUiState
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GraphTest {
    @Test
    fun `buildRepository returns NoCredentialsRepository when store empty`() = runTest {
        val credentials = CredentialStore(InMemoryKeyValueStore())
        val cache = StateCache(InMemoryKeyValueStore())

        val repo = Graph.buildRepository(credentials, cache)

        assertEquals(VehicleUiState.NoCredentials, repo.state.value)
    }

    @Test
    fun `buildRepository returns NoCredentialsRepository when only payload is present`() = runTest {
        val credentials = CredentialStore(InMemoryKeyValueStore())
        credentials.save(
            SetupPayload(
                refreshToken = "rt1",
                clientId = "cid1",
                region = "na",
                privateKey = "pk1",
            ),
        )
        val cache = StateCache(InMemoryKeyValueStore())

        val repo = Graph.buildRepository(credentials, cache)

        assertEquals(VehicleUiState.NoCredentials, repo.state.value)
    }

    @Test
    fun `buildRepository returns NoCredentialsRepository when only vehicle is present`() = runTest {
        val credentials = CredentialStore(InMemoryKeyValueStore())
        credentials.saveVehicle(id = "123", vin = "5YJ3...", name = "My Model 3")
        val cache = StateCache(InMemoryKeyValueStore())

        val repo = Graph.buildRepository(credentials, cache)

        assertEquals(VehicleUiState.NoCredentials, repo.state.value)
    }

    @Test
    fun `NoCredentialsRepository rejects commands with Set up first`() = runTest {
        val credentials = CredentialStore(InMemoryKeyValueStore())
        val cache = StateCache(InMemoryKeyValueStore())
        val repo = Graph.buildRepository(credentials, cache)

        val result = repo.lock()

        assertIs<com.amolpurohit.tesla.vehicle.CommandResult.Rejected>(result)
        assertEquals("Set up first", result.reason)
    }

    @Test
    fun `buildRepository returns a RealVehicleRepository when credentials and vehicle are present`() = runTest {
        val credentials = CredentialStore(InMemoryKeyValueStore())
        credentials.save(
            SetupPayload(
                refreshToken = "rt1",
                clientId = "cid1",
                region = "na",
                privateKey = "pk1",
            ),
        )
        credentials.saveVehicle(id = "123", vin = "5YJ3...", name = "My Model 3")
        val cache = StateCache(InMemoryKeyValueStore())

        // Constructing (and start()-ing) does no network I/O: start() only reads the
        // (empty) local cache, which safely emits Loading offline.
        val repo = Graph.buildRepository(credentials, cache)

        assertIs<RealVehicleRepository>(repo)
        assertEquals(VehicleUiState.Loading, repo.state.value)
    }

    @Test
    fun `reset clears the memoized repository seam`() {
        val repo = FakeRepoForOverrideTest()
        Graph.override(repo)
        Graph.reset()
        // No direct getter exists; verifying reset() doesn't throw and override() seam
        // still works is what's testable here without a DataStore.
        Graph.override(repo)
    }
}

private class FakeRepoForOverrideTest : com.amolpurohit.tesla.vehicle.VehicleRepository {
    override val state = kotlinx.coroutines.flow.MutableStateFlow<VehicleUiState>(VehicleUiState.Loading)
    override suspend fun refresh() {}
    override suspend fun wake() = com.amolpurohit.tesla.vehicle.CommandResult.Success
    override suspend fun lock() = com.amolpurohit.tesla.vehicle.CommandResult.Success
    override suspend fun unlock() = com.amolpurohit.tesla.vehicle.CommandResult.Success
    override suspend fun startCharging() = com.amolpurohit.tesla.vehicle.CommandResult.Success
    override suspend fun stopCharging() = com.amolpurohit.tesla.vehicle.CommandResult.Success
    override suspend fun setChargeLimit(percent: Int) = com.amolpurohit.tesla.vehicle.CommandResult.Success
    override suspend fun setChargeAmps(amps: Int) = com.amolpurohit.tesla.vehicle.CommandResult.Success
    override suspend fun setClimateOn(on: Boolean) = com.amolpurohit.tesla.vehicle.CommandResult.Success
    override suspend fun setTargetTemp(celsius: Double) = com.amolpurohit.tesla.vehicle.CommandResult.Success
    override suspend fun setOverheatProtection(mode: com.amolpurohit.tesla.vehicle.OverheatProtectionMode) =
        com.amolpurohit.tesla.vehicle.CommandResult.Success
    override suspend fun setDogMode(on: Boolean) = com.amolpurohit.tesla.vehicle.CommandResult.Success
    override suspend fun ventWindows() = com.amolpurohit.tesla.vehicle.CommandResult.Success
    override suspend fun closeWindows() = com.amolpurohit.tesla.vehicle.CommandResult.Success
}
