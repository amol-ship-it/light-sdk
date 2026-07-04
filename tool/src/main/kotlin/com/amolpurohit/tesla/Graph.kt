package com.amolpurohit.tesla

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.amolpurohit.tesla.auth.CredentialStore
import com.amolpurohit.tesla.auth.TokenManager
import com.amolpurohit.tesla.fleet.FleetClient
import com.amolpurohit.tesla.store.DataStoreKeyValueStore
import com.amolpurohit.tesla.vehicle.CommandResult
import com.amolpurohit.tesla.vehicle.FakeVehicleRepository
import com.amolpurohit.tesla.vehicle.OverheatProtectionMode
import com.amolpurohit.tesla.vehicle.RealVehicleRepository
import com.amolpurohit.tesla.vehicle.StateCache
import com.amolpurohit.tesla.vehicle.VehicleRepository
import com.amolpurohit.tesla.vehicle.VehicleUiState
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable

/** Flip to true to force the in-memory fake (emulator demos without a live Tesla account). */
const val USE_FAKE = false

/**
 * App-wide access point for the [VehicleRepository] singleton.
 *
 * Screens cannot hand `Graph` a `lightContext` (it's `protected` on [com.thelightphone.sdk.SimpleLightScreen]),
 * so each screen resolves its own `lightContext.dataStore` and passes it into [repository].
 */
object Graph {
    private val mutex = Mutex()

    @Volatile private var repo: VehicleRepository? = null

    // Kept only so reset() can close the underlying HTTP engine of a real stack.
    @Volatile private var closeable: Closeable? = null

    suspend fun repository(dataStore: DataStore<Preferences>): VehicleRepository {
        repo?.let { return it }
        return mutex.withLock {
            repo?.let { return it }
            val built = if (USE_FAKE) {
                FakeVehicleRepository(latencyMs = 400)
            } else {
                val credentials = CredentialStore(DataStoreKeyValueStore(dataStore))
                val cache = StateCache(DataStoreKeyValueStore(dataStore))
                buildRepository(credentials, cache)
            }
            repo = built
            built
        }
    }

    /**
     * Decision logic factored out of [repository] so it's unit-testable without a real
     * DataStore: given a [CredentialStore] and a [StateCache] (both backed by any
     * [com.amolpurohit.tesla.store.KeyValueStore], including an in-memory one in tests),
     * decide between [NoCredentialsRepository] and the real Fleet-API-backed stack. [cache]
     * is only needed to build the real stack; it's unused on the no-credentials path.
     */
    internal suspend fun buildRepository(
        credentials: CredentialStore,
        cache: StateCache,
    ): VehicleRepository {
        val payload = credentials.load()
        val vehicle = credentials.loadVehicle()
        if (payload == null || vehicle == null) {
            return NoCredentialsRepository
        }

        val engine = OkHttp.create()
        val tokens = TokenManager(HttpClient(engine), credentials)
        val fleetClient = FleetClient(engine, tokens, payload.region)
        val real = RealVehicleRepository(
            api = fleetClient,
            cache = cache,
            vehicleId = vehicle.id,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            nowMs = System::currentTimeMillis,
        )
        real.start()
        closeable = fleetClient
        return real
    }

    /** Clears the memoized repository and closes the previous real stack's HTTP engine, if any. Next [repository] call rebuilds. */
    fun reset() {
        closeable?.close()
        closeable = null
        repo = null
    }

    // test seam:
    fun override(r: VehicleRepository) { repo = r }
}

/** Sentinel repository used while no credentials/vehicle have been set up yet. */
private object NoCredentialsRepository : VehicleRepository {
    override val state: StateFlow<VehicleUiState> = MutableStateFlow(VehicleUiState.NoCredentials)

    override suspend fun refresh() {}
    override suspend fun wake(): CommandResult = rejected()
    override suspend fun lock(): CommandResult = rejected()
    override suspend fun unlock(): CommandResult = rejected()
    override suspend fun startCharging(): CommandResult = rejected()
    override suspend fun stopCharging(): CommandResult = rejected()
    override suspend fun setChargeLimit(percent: Int): CommandResult = rejected()
    override suspend fun setChargeAmps(amps: Int): CommandResult = rejected()
    override suspend fun setClimateOn(on: Boolean): CommandResult = rejected()
    override suspend fun setTargetTemp(celsius: Double): CommandResult = rejected()
    override suspend fun setOverheatProtection(mode: OverheatProtectionMode): CommandResult = rejected()
    override suspend fun setDogMode(on: Boolean): CommandResult = rejected()
    override suspend fun ventWindows(): CommandResult = rejected()
    override suspend fun closeWindows(): CommandResult = rejected()

    private fun rejected(): CommandResult = CommandResult.Rejected("Set up first")
}
