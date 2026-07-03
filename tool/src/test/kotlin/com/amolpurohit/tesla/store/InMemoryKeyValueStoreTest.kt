package com.amolpurohit.tesla.store

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class InMemoryKeyValueStoreTest {
    private lateinit var store: InMemoryKeyValueStore

    @BeforeTest
    fun setup() {
        store = InMemoryKeyValueStore()
    }

    @Test
    fun `put and get returns value`() = runTest {
        store.put("key1", "value1")
        assertEquals("value1", store.get("key1"))
    }

    @Test
    fun `get of missing key returns null`() = runTest {
        assertNull(store.get("missing"))
    }

    @Test
    fun `remove then get returns null`() = runTest {
        store.put("key1", "value1")
        store.remove("key1")
        assertNull(store.get("key1"))
    }

    @Test
    fun `put overwrites existing value`() = runTest {
        store.put("key1", "value1")
        store.put("key1", "value2")
        assertEquals("value2", store.get("key1"))
    }
}
