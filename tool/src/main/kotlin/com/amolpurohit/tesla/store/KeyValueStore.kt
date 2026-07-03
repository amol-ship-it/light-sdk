package com.amolpurohit.tesla.store

interface KeyValueStore {
    suspend fun get(key: String): String?
    suspend fun put(key: String, value: String)
    suspend fun remove(key: String)
}

class InMemoryKeyValueStore : KeyValueStore {
    private val map = mutableMapOf<String, String>()
    override suspend fun get(key: String) = map[key]
    override suspend fun put(key: String, value: String) { map[key] = value }
    override suspend fun remove(key: String) { map.remove(key) }
}
