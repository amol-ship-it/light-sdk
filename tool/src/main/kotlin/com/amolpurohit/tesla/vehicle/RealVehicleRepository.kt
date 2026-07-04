package com.amolpurohit.tesla.vehicle

import com.amolpurohit.tesla.auth.AuthExpiredException
import com.amolpurohit.tesla.fleet.FleetApi
import com.amolpurohit.tesla.fleet.FleetOfflineException
import com.amolpurohit.tesla.fleet.FleetPartialDataException
import com.amolpurohit.tesla.fleet.RateLimitedException
import com.amolpurohit.tesla.fleet.VehicleAsleepException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Production [VehicleRepository] backed by the Fleet API. Read paths only —
 * all 13 command methods are stubbed as [CommandResult.Rejected] until
 * Chunk 4 wires real signed commands through them.
 *
 * [scope] is accepted for interface symmetry with future work (e.g.
 * background polling hooks) but is currently unused: this repository does
 * no polling of its own outside [wake]'s bounded loop.
 */
class RealVehicleRepository(
    private val api: FleetApi,
    private val cache: StateCache,
    private val vehicleId: String,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
    private val pollDelayMs: Long = 3_000,
    private val wakeBudgetMs: Long = 30_000,
) : VehicleRepository {

    private val mutex = Mutex()

    private val _state = MutableStateFlow<VehicleUiState>(VehicleUiState.Loading)
    override val state: StateFlow<VehicleUiState> = _state.asStateFlow()

    /** Last known good fetch (in-memory fresh Ready, or the initial cache load), with its timestamp. */
    private var lastGood: Pair<VehicleState, Long>? = null

    /**
     * Loads cached state (if any) and emits it as stale, or Loading if no cache exists.
     * Does no network I/O. Implemented as a plain suspend function (not launched into
     * [scope]) so callers — and tests under `runTest` — can await completion directly
     * without needing to pump the dispatcher; the UI's VM calls `start()` then `refresh()`
     * in sequence.
     */
    suspend fun start() {
        val cached = cache.load()
        if (cached == null) {
            _state.value = VehicleUiState.Loading
            return
        }
        lastGood = cached.state to cached.updatedAtMs
        _state.value = VehicleUiState.Ready(
            state = cached.state,
            updatedAtMs = cached.updatedAtMs,
            stale = true,
        )
    }

    override suspend fun refresh() {
        mutex.withLock {
            fetchAndEmitLocked()
        }
    }

    /**
     * Wakes the vehicle then polls its cheap summary state until "online" or the wake
     * budget is exhausted. No separate "Waking" UI state is emitted during the poll —
     * per spec, the per-button pending indicator IS the wake progress UI; the underlying
     * VehicleUiState stays whatever it was (Asleep/Error/stale Ready) until the outcome
     * (fresh Ready or Error(WakeTimeout)) lands.
     */
    override suspend fun wake(): CommandResult {
        return mutex.withLock {
            api.wakeUp(vehicleId)

            var waited = 0L
            var online = false
            while (waited < wakeBudgetMs) {
                val summary = api.vehicleSummary(vehicleId)
                if (summary?.state == "online") {
                    online = true
                    break
                }
                delay(pollDelayMs)
                waited += pollDelayMs
            }

            if (!online) {
                val (cached, cachedAt) = lastGood ?: (null to null)
                _state.value = VehicleUiState.Error(ErrorKind.WakeTimeout, cached, cachedAt)
                return@withLock CommandResult.Failed(ErrorKind.WakeTimeout)
            }

            fetchAndEmitLocked()
            CommandResult.Success
        }
    }

    /** One vehicleData call, mapped to a UI state and (on success) persisted. Must be called under [mutex]. */
    private suspend fun fetchAndEmitLocked() {
        try {
            val fresh = api.vehicleData(vehicleId)
            val at = nowMs()
            lastGood = fresh to at
            cache.save(fresh, at)
            _state.value = VehicleUiState.Ready(state = fresh, updatedAtMs = at, stale = false)
        } catch (e: VehicleAsleepException) {
            val (cached, cachedAt) = lastGood ?: (null to null)
            _state.value = VehicleUiState.Asleep(cached, cachedAt)
        } catch (e: AuthExpiredException) {
            emitError(ErrorKind.AuthExpired)
        } catch (e: FleetOfflineException) {
            emitError(ErrorKind.Offline)
        } catch (e: RateLimitedException) {
            emitError(ErrorKind.RateLimited)
        } catch (e: FleetPartialDataException) {
            emitError(ErrorKind.Unknown)
        } catch (e: Exception) {
            emitError(ErrorKind.Unknown)
        }
    }

    private fun emitError(kind: ErrorKind) {
        val (cached, cachedAt) = lastGood ?: (null to null)
        _state.value = VehicleUiState.Error(kind, cached, cachedAt)
    }

    override suspend fun lock(): CommandResult = notYetSupported()
    override suspend fun unlock(): CommandResult = notYetSupported()
    override suspend fun startCharging(): CommandResult = notYetSupported()
    override suspend fun stopCharging(): CommandResult = notYetSupported()
    override suspend fun setChargeLimit(percent: Int): CommandResult = notYetSupported()
    override suspend fun setChargeAmps(amps: Int): CommandResult = notYetSupported()
    override suspend fun setClimateOn(on: Boolean): CommandResult = notYetSupported()
    override suspend fun setTargetTemp(celsius: Double): CommandResult = notYetSupported()
    override suspend fun setOverheatProtection(mode: OverheatProtectionMode): CommandResult = notYetSupported()
    override suspend fun setDogMode(on: Boolean): CommandResult = notYetSupported()
    override suspend fun ventWindows(): CommandResult = notYetSupported()
    override suspend fun closeWindows(): CommandResult = notYetSupported()

    private fun notYetSupported(): CommandResult =
        CommandResult.Rejected("Not yet supported in this build")
}
