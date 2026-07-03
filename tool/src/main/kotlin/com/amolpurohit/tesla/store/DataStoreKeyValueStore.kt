package com.amolpurohit.tesla.store

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

class DataStoreKeyValueStore(private val dataStore: DataStore<Preferences>) : KeyValueStore {
    override suspend fun get(key: String): String? {
        val prefKey = stringPreferencesKey(key)
        return dataStore.data.first()[prefKey]
    }

    override suspend fun put(key: String, value: String) {
        val prefKey = stringPreferencesKey(key)
        dataStore.edit { it[prefKey] = value }
    }

    override suspend fun remove(key: String) {
        val prefKey = stringPreferencesKey(key)
        dataStore.edit { it.remove(prefKey) }
    }
}
