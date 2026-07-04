package com.amolpurohit.tesla.store

interface KeyValueStore {
    /**
     * Null means "absent key" only. The DataStore-backed impl may throw
     * IOException if the underlying store file is corrupted (no corruption
     * handler in the SDK's DataStore) — callers needing crash-proof reads
     * (CredentialStore, StateCache) must wrap and degrade to null themselves.
     */
    suspend fun get(key: String): String?
    suspend fun put(key: String, value: String)
    suspend fun remove(key: String)
}

open class InMemoryKeyValueStore : KeyValueStore {
    private val map = mutableMapOf<String, String>()
    override suspend fun get(key: String) = map[key]
    override suspend fun put(key: String, value: String) { map[key] = value }
    override suspend fun remove(key: String) { map.remove(key) }
}
