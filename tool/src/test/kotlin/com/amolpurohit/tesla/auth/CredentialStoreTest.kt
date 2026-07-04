package com.amolpurohit.tesla.auth

import com.amolpurohit.tesla.store.InMemoryKeyValueStore
import com.amolpurohit.tesla.store.KeyValueStore
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CredentialStoreTest {
    @Test
    fun `save then load returns payload`() = runTest {
        val store = CredentialStore(InMemoryKeyValueStore())
        val payload = SetupPayload(
            refreshToken = "rt1",
            clientId = "cid1",
            clientSecret = "cs1",
            region = "na",
            privateKey = "pk1",
            domain = "example.com",
            version = 1
        )
        store.save(payload)
        val loaded = store.load()
        assertEquals(payload, loaded)
    }

    @Test
    fun `updateRefreshToken persists`() = runTest {
        val store = CredentialStore(InMemoryKeyValueStore())
        val payload = SetupPayload(
            refreshToken = "rt1",
            clientId = "cid1",
            clientSecret = "cs1",
            region = "na",
            privateKey = "pk1",
            domain = "example.com",
            version = 1
        )
        store.save(payload)
        store.updateRefreshToken("rt2")
        val loaded = store.load()
        assertEquals("rt2", loaded?.refreshToken)
        assertEquals("cid1", loaded?.clientId)
        assertEquals("cs1", loaded?.clientSecret)
        assertEquals("na", loaded?.region)
        assertEquals("pk1", loaded?.privateKey)
        assertEquals("example.com", loaded?.domain)
        assertEquals(1, loaded?.version)
    }

    @Test
    fun `saveVehicle then loadVehicle returns it`() = runTest {
        val store = CredentialStore(InMemoryKeyValueStore())
        store.saveVehicle(id = "123", vin = "5YJ3...", name = "My Model 3")
        val loaded = store.loadVehicle()
        assertEquals(SelectedVehicle(id = "123", vin = "5YJ3...", name = "My Model 3"), loaded)
    }

    @Test
    fun `clear empties both`() = runTest {
        val store = CredentialStore(InMemoryKeyValueStore())
        val payload = SetupPayload(
            refreshToken = "rt1",
            clientId = "cid1",
            clientSecret = "cs1",
            region = "na",
            privateKey = "pk1",
            domain = "example.com",
            version = 1
        )
        store.save(payload)
        store.saveVehicle(id = "123", vin = "5YJ3...", name = "My Model 3")
        store.clear()
        assertNull(store.load())
        assertNull(store.loadVehicle())
    }

    @Test
    fun `load on empty store returns null`() = runTest {
        val store = CredentialStore(InMemoryKeyValueStore())
        assertNull(store.load())
    }

    @Test
    fun `load degrades to null when the underlying store throws`() = runTest {
        val throwingStore = object : KeyValueStore {
            override suspend fun get(key: String): String? = throw IOException("corrupted store")
            override suspend fun put(key: String, value: String) {}
            override suspend fun remove(key: String) {}
        }
        val store = CredentialStore(throwingStore)
        assertNull(store.load())
        assertNull(store.loadVehicle())
    }
}
