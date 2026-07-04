package com.amolpurohit.tesla.vehicle

import com.amolpurohit.tesla.store.InMemoryKeyValueStore
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StateCacheTest {
    private val store = InMemoryKeyValueStore()
    private val cache = StateCache(store)

    @Test
    fun `save then load round-trips state and timestamp`() = runTest {
        val state = VehicleState(
            batteryPercent = 85,
            rangeKm = 420.5,
            chargingState = ChargingState.Charging,
            pluggedIn = true,
            chargeLimitPercent = 90,
            chargeAmps = 16,
            maxChargeAmps = 32,
            insideTempC = 22.5,
            climateOn = true,
            targetTempC = 21.0,
            overheatProtection = OverheatProtectionMode.Off,
            dogModeOn = false,
            locked = true,
            windowsOpen = false,
            asleep = false,
        )
        val updatedAtMs = 12345L

        cache.save(state, updatedAtMs)
        val loaded = cache.load()

        assertEquals(CachedState(state, updatedAtMs), loaded)
    }

    @Test
    fun `load on empty store returns null`() = runTest {
        val loaded = cache.load()
        assertNull(loaded)
    }

    @Test
    fun `corrupted JSON returns null not crash`() = runTest {
        // Put literal garbage under the cache key
        store.put("state_cache", "not valid json at all {{{")
        val loaded = cache.load()
        assertNull(loaded)
    }

    @Test
    fun `throwing store returns null`() = runTest {
        val throwingStore = object : InMemoryKeyValueStore() {
            override suspend fun get(key: String): String? {
                throw java.io.IOException("Simulated store failure")
            }
        }
        val cacheWithThrowingStore = StateCache(throwingStore)
        val loaded = cacheWithThrowingStore.load()
        assertNull(loaded)
    }
}
