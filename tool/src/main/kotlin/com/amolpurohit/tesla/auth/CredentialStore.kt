package com.amolpurohit.tesla.auth

import com.amolpurohit.tesla.store.KeyValueStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SelectedVehicle(
    val id: String,
    val vin: String,
    val name: String,
)

class CredentialStore(private val store: KeyValueStore) {
    private companion object {
        private const val CREDENTIALS_KEY = "credentials"
        private const val VEHICLE_KEY = "vehicle"
        private val jsonCodec = Json { ignoreUnknownKeys = true }
    }

    suspend fun save(payload: SetupPayload) {
        val jsonString = jsonCodec.encodeToString(SetupPayload.serializer(), payload)
        store.put(CREDENTIALS_KEY, jsonString)
    }

    suspend fun load(): SetupPayload? = try {
        val jsonString = store.get(CREDENTIALS_KEY) ?: return null
        jsonCodec.decodeFromString(SetupPayload.serializer(), jsonString)
    } catch (e: Exception) {
        null
    }

    suspend fun updateRefreshToken(newToken: String) {
        val payload = load() ?: return
        val updated = payload.copy(refreshToken = newToken)
        save(updated)
    }

    suspend fun saveVehicle(id: String, vin: String, name: String) {
        val vehicle = SelectedVehicle(id = id, vin = vin, name = name)
        val jsonString = jsonCodec.encodeToString(SelectedVehicle.serializer(), vehicle)
        store.put(VEHICLE_KEY, jsonString)
    }

    suspend fun loadVehicle(): SelectedVehicle? = try {
        val jsonString = store.get(VEHICLE_KEY) ?: return null
        jsonCodec.decodeFromString(SelectedVehicle.serializer(), jsonString)
    } catch (e: Exception) {
        null
    }

    suspend fun clear() {
        store.remove(CREDENTIALS_KEY)
        store.remove(VEHICLE_KEY)
    }
}
