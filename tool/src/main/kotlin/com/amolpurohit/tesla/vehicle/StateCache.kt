package com.amolpurohit.tesla.vehicle

import com.amolpurohit.tesla.store.KeyValueStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CachedState(
    val state: VehicleState,
    val updatedAtMs: Long,
)

class StateCache(private val store: KeyValueStore) {
    private companion object {
        private const val STATE_CACHE_KEY = "state_cache"
        private val jsonCodec = Json { ignoreUnknownKeys = true }
    }

    suspend fun save(state: VehicleState, updatedAtMs: Long) {
        val cachedState = CachedState(state, updatedAtMs)
        val jsonString = jsonCodec.encodeToString(CachedState.serializer(), cachedState)
        store.put(STATE_CACHE_KEY, jsonString)
    }

    suspend fun load(): CachedState? = try {
        val jsonString = store.get(STATE_CACHE_KEY) ?: return null
        jsonCodec.decodeFromString(CachedState.serializer(), jsonString)
    } catch (e: Exception) {
        null
    }
}
