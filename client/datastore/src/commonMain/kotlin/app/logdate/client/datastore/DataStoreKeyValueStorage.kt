package app.logdate.client.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Implementation of KeyValueStorage using DataStore Preferences.
 * This works on all platforms supported by DataStore (Android, iOS, Desktop).
 */
class DataStoreKeyValueStorage(
    private val dataStore: DataStore<Preferences>,
    private val reportFailures: Boolean = false,
) : KeyValueStorage {
    internal fun reportingFailures(): KeyValueStorage =
        if (reportFailures) this else DataStoreKeyValueStorage(dataStore, reportFailures = true)

    override suspend fun getStringsOrThrow(keys: Set<String>): Map<String, String?> {
        val snapshot = dataStore.data.first()
        return keys.associateWith { snapshot[stringPreferencesKey(it)] }
    }

    override suspend fun updateStringsOrThrow(values: Map<String, String?>) {
        dataStore.edit { snapshot ->
            values.forEach { (key, value) ->
                if (value == null) snapshot.remove(stringPreferencesKey(key)) else snapshot[stringPreferencesKey(key)] = value
            }
        }
    }

    override suspend fun compareAndSetStringsOrThrow(
        expected: Map<String, String?>,
        values: Map<String, String?>,
    ): Boolean {
        var matched = false
        dataStore.edit { snapshot ->
            if (expected.all { (key, value) -> snapshot[stringPreferencesKey(key)] == value }) {
                values.forEach { (key, value) ->
                    if (value == null) snapshot.remove(stringPreferencesKey(key)) else snapshot[stringPreferencesKey(key)] = value
                }
                matched = true
            }
        }
        return matched
    }

    override suspend fun getStringOrThrow(key: String): String? = dataStore.data.first()[stringPreferencesKey(key)]

    override suspend fun getBooleanOrThrow(
        key: String,
        defaultValue: Boolean,
    ): Boolean = dataStore.data.first()[booleanPreferencesKey(key)] ?: defaultValue

    override suspend fun putStringOrThrow(
        key: String,
        value: String,
    ) {
        dataStore.edit { it[stringPreferencesKey(key)] = value }
    }

    override suspend fun getString(key: String): String? {
        val prefKey = stringPreferencesKey(key)
        return try {
            val preferences = dataStore.data.first()
            preferences[prefKey]
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (reportFailures) throw e
            Napier.e("Failed to get string for key: $key", e)
            null
        }
    }

    override suspend fun putString(
        key: String,
        value: String,
    ) = write("put string for key: $key") { it[stringPreferencesKey(key)] = value }

    override suspend fun getBoolean(
        key: String,
        defaultValue: Boolean,
    ): Boolean {
        val prefKey = booleanPreferencesKey(key)
        return try {
            val preferences = dataStore.data.first()
            preferences[prefKey] ?: defaultValue
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (reportFailures) throw e
            Napier.e("Failed to get boolean for key: $key", e)
            defaultValue
        }
    }

    override suspend fun putBoolean(
        key: String,
        value: Boolean,
    ) = write("put boolean for key: $key") { it[booleanPreferencesKey(key)] = value }

    override suspend fun getInt(
        key: String,
        defaultValue: Int,
    ): Int {
        val prefKey = intPreferencesKey(key)
        return try {
            val preferences = dataStore.data.first()
            preferences[prefKey] ?: defaultValue
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (reportFailures) throw e
            Napier.e("Failed to get int for key: $key", e)
            defaultValue
        }
    }

    override suspend fun putInt(
        key: String,
        value: Int,
    ) = write("put int for key: $key") { it[intPreferencesKey(key)] = value }

    override suspend fun getLong(
        key: String,
        defaultValue: Long,
    ): Long {
        val prefKey = longPreferencesKey(key)
        return try {
            val preferences = dataStore.data.first()
            preferences[prefKey] ?: defaultValue
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (reportFailures) throw e
            Napier.e("Failed to get long for key: $key", e)
            defaultValue
        }
    }

    override suspend fun putLong(
        key: String,
        value: Long,
    ) = write("put long for key: $key") { it[longPreferencesKey(key)] = value }

    override suspend fun getFloat(
        key: String,
        defaultValue: Float,
    ): Float {
        val prefKey = floatPreferencesKey(key)
        return try {
            val preferences = dataStore.data.first()
            preferences[prefKey] ?: defaultValue
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (reportFailures) throw e
            Napier.e("Failed to get float for key: $key", e)
            defaultValue
        }
    }

    override suspend fun putFloat(
        key: String,
        value: Float,
    ) = write("put float for key: $key") { it[floatPreferencesKey(key)] = value }

    override suspend fun remove(key: String) =
        write("remove key: $key") { preferences ->
            preferences
                .asMap()
                .keys
                .filter { it.name == key }
                .forEach { preferences.remove(it) }
        }

    override suspend fun contains(key: String): Boolean =
        try {
            val preferences = dataStore.data.first()
            preferences.asMap().keys.any { it.name == key }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (reportFailures) throw e
            Napier.e("Failed to check if contains key: $key", e)
            false
        }

    override suspend fun clear() = write("clear preferences") { it.clear() }

    /** Applies [change] and rethrows a failed save so a caller never treats unsaved data as stored. */
    private suspend fun write(
        description: String,
        change: (MutablePreferences) -> Unit,
    ) {
        try {
            dataStore.edit { change(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Napier.e("Failed to $description", e)
            throw e
        }
    }

    override fun observeString(key: String): Flow<String?> {
        val prefKey = stringPreferencesKey(key)
        return dataStore.data
            .catch { e ->
                if (e is CancellationException || reportFailures) throw e
                Napier.e("Error reading string for key: $key", e)
                emit(emptyPreferences())
            }.map { preferences ->
                preferences[prefKey]
            }
    }

    override fun observeBoolean(
        key: String,
        defaultValue: Boolean,
    ): Flow<Boolean> {
        val prefKey = booleanPreferencesKey(key)
        return dataStore.data
            .catch { e ->
                if (e is CancellationException || reportFailures) throw e
                Napier.e("Error reading boolean for key: $key", e)
                emit(emptyPreferences())
            }.map { preferences ->
                preferences[prefKey] ?: defaultValue
            }
    }

    override fun observeInt(
        key: String,
        defaultValue: Int,
    ): Flow<Int> {
        val prefKey = intPreferencesKey(key)
        return dataStore.data
            .catch { e ->
                if (e is CancellationException || reportFailures) throw e
                Napier.e("Error reading int for key: $key", e)
                emit(emptyPreferences())
            }.map { preferences ->
                preferences[prefKey] ?: defaultValue
            }
    }

    override fun observeLong(
        key: String,
        defaultValue: Long,
    ): Flow<Long> {
        val prefKey = longPreferencesKey(key)
        return dataStore.data
            .catch { e ->
                if (e is CancellationException || reportFailures) throw e
                Napier.e("Error reading long for key: $key", e)
                emit(emptyPreferences())
            }.map { preferences ->
                preferences[prefKey] ?: defaultValue
            }
    }

    override fun observeFloat(
        key: String,
        defaultValue: Float,
    ): Flow<Float> {
        val prefKey = floatPreferencesKey(key)
        return dataStore.data
            .catch { e ->
                if (e is CancellationException || reportFailures) throw e
                Napier.e("Error reading float for key: $key", e)
                emit(emptyPreferences())
            }.map { preferences ->
                preferences[prefKey] ?: defaultValue
            }
    }
}
