package com.amolpurohit.tesla.ui

import com.amolpurohit.tesla.auth.CredentialStore
import com.amolpurohit.tesla.auth.SetupPayload
import com.amolpurohit.tesla.fleet.FleetApi
import com.amolpurohit.tesla.fleet.FleetOfflineException
import com.amolpurohit.tesla.fleet.SignedCommandResponse
import com.amolpurohit.tesla.fleet.VehicleSummary
import com.amolpurohit.tesla.store.InMemoryKeyValueStore
import com.amolpurohit.tesla.vehicle.VehicleState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SetupScreenViewModelTest {

    /** Scriptable [FleetApi] fake; only listVehicles matters for this VM. */
    private class FakeFleetApi(
        private val vehiclesResult: suspend () -> List<VehicleSummary> = { emptyList() },
    ) : FleetApi {
        var listVehiclesCount = 0
            private set

        override suspend fun listVehicles(): List<VehicleSummary> {
            listVehiclesCount++
            return vehiclesResult()
        }

        override suspend fun vehicleSummary(id: String): VehicleSummary? = null
        override suspend fun vehicleData(id: String): VehicleState = throw NotImplementedError()
        override suspend fun wakeUp(id: String) {}
        override suspend fun signedCommand(id: String, routableMessageB64: String): SignedCommandResponse =
            SignedCommandResponse("")
    }

    private fun encode(json: String): String {   // mirrors SetupPayloadTest's helper / the login script
        val deflated = java.util.zip.Deflater(9, /*nowrap=*/true).let { d ->
            d.setInput(json.toByteArray()); d.finish()
            val buf = ByteArray(json.length * 2 + 64)
            val n = d.deflate(buf); buf.copyOf(n)
        }
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(deflated)
    }

    private val validJson = """{"v":1,"refresh_token":"rt","client_id":"cid","region":"na","private_key":"pk"}"""

    private val sampleVehicle = VehicleSummary(id = "v1", vin = "VIN1", name = "Model 3", state = "online")

    private fun vm(
        store: InMemoryKeyValueStore = InMemoryKeyValueStore(),
        apiFactory: (SetupPayload) -> FleetApi = { FakeFleetApi() },
    ) = SetupScreenViewModel(CredentialStore(store), apiFactory)

    @Test fun `single valid scan persists credentials then lists vehicles`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val store = InMemoryKeyValueStore()
            val credentials = CredentialStore(store)
            // Read the store AT listVehicles-invocation time: this fails if the VM ever
            // reorders to list before persisting (Task 18 depends on persist-before-list).
            var credentialsPresentAtListTime = false
            val api = FakeFleetApi(vehiclesResult = {
                credentialsPresentAtListTime = credentials.load() != null
                listOf(sampleVehicle)
            })
            val viewModel = SetupScreenViewModel(credentials, apiFactory = { api })

            assertIs<SetupStep.Scanning>(viewModel.step.value)
            viewModel.onScan(encode(validJson))
            advanceUntilIdle()

            val step = viewModel.step.value
            assertIs<SetupStep.PickingVehicle>(step)
            assertEquals(listOf(sampleVehicle), step.vehicles)
            assertTrue(credentialsPresentAtListTime, "credentials must be persisted before listVehicles runs")
            assertTrue(credentials.load() != null)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `multi-part scans show NeedMore progress`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val body = encode(validJson)
            val partA = "LTP/1/2/" + body.substring(0, body.length / 2)
            val partB = "LTP/2/2/" + body.substring(body.length / 2)

            val viewModel = vm(apiFactory = { FakeFleetApi(vehiclesResult = { listOf(sampleVehicle) }) })

            viewModel.onScan(partB)
            advanceUntilIdle()
            val needMore = viewModel.step.value
            assertIs<SetupStep.NeedMore>(needMore)
            assertEquals(1, needMore.have)
            assertEquals(2, needMore.of)

            viewModel.onScan(partA)
            advanceUntilIdle()
            assertIs<SetupStep.PickingVehicle>(viewModel.step.value)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `invalid scan shows retryable error`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val viewModel = vm()

            viewModel.onScan("not-a-payload")
            advanceUntilIdle()
            assertIs<SetupStep.Failed>(viewModel.step.value)

            viewModel.retry()
            assertIs<SetupStep.Scanning>(viewModel.step.value)

            // Retry clears accumulated scans and starts over: feeding only part 2 of a
            // previously-in-progress multi-part scan should NOT resolve to Complete.
            val body = encode(validJson)
            val partB = "LTP/2/2/" + body.substring(body.length / 2)
            viewModel.onScan(partB)
            advanceUntilIdle()
            assertIs<SetupStep.NeedMore>(viewModel.step.value)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `vehicle pick persists and completes`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val store = InMemoryKeyValueStore()
            val credentials = CredentialStore(store)
            val api = FakeFleetApi(vehiclesResult = { listOf(sampleVehicle) })
            val viewModel = SetupScreenViewModel(credentials, apiFactory = { api })

            viewModel.onScan(encode(validJson))
            advanceUntilIdle()
            assertIs<SetupStep.PickingVehicle>(viewModel.step.value)

            viewModel.pick(sampleVehicle)
            advanceUntilIdle()

            val savedVehicle = credentials.loadVehicle()
            assertEquals(sampleVehicle.id, savedVehicle?.id)
            assertEquals(sampleVehicle.vin, savedVehicle?.vin)
            assertEquals(sampleVehicle.name, savedVehicle?.name)
            assertIs<SetupStep.Done>(viewModel.step.value)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `listVehicles failure surfaces Failed with retry`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var attempts = 0
            val api = FakeFleetApi(vehiclesResult = {
                attempts++
                if (attempts == 1) throw FleetOfflineException(IOException("boom"))
                listOf(sampleVehicle)
            })
            val viewModel = vm(apiFactory = { api })

            viewModel.onScan(encode(validJson))
            advanceUntilIdle()
            assertIs<SetupStep.Failed>(viewModel.step.value)
            assertEquals(1, attempts)

            viewModel.retry()
            advanceUntilIdle()

            assertEquals(2, attempts)  // re-attempted listing, did NOT re-scan
            val step = viewModel.step.value
            assertIs<SetupStep.PickingVehicle>(step)
            assertEquals(listOf(sampleVehicle), step.vehicles)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `empty vehicle list surfaces Failed`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val api = FakeFleetApi(vehiclesResult = { emptyList() })
            val viewModel = vm(apiFactory = { api })

            viewModel.onScan(encode(validJson))
            advanceUntilIdle()

            assertIs<SetupStep.Failed>(viewModel.step.value)
        } finally { Dispatchers.resetMain() }
    }
}
