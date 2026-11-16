package app.logdate.client.data.search

import app.logdate.client.data.LenientJson
import app.logdate.client.datastore.KeyValueStorage
import app.logdate.client.repository.search.RecentSearchesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString

/**
 * [RecentSearchesRepository] implementation backed by [KeyValueStorage].
 *
 * Stores recent search queries as a JSON-encoded list of strings, capped
 * at [MAX_RECENT_SEARCHES]. Most recent queries appear first.
 */
class DataStoreRecentSearchesRepository(
    private val storage: KeyValueStorage,
) : RecentSearchesRepository {
    private val json = LenientJson

    override fun observeRecentSearches(): Flow<List<String>> =
        storage.observeString(KEY).map { raw ->
            if (raw.isNullOrBlank()) {
                emptyList()
            } else {
                try {
                    json.decodeFromString<List<String>>(raw)
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }

    override suspend fun addRecentSearch(query: String) {
        val current = getCurrent().toMutableList()
        current.remove(query)
        current.add(0, query)
        if (current.size > MAX_RECENT_SEARCHES) {
            current.removeLast()
        }
        storage.putString(KEY, json.encodeToString(current))
    }

    override suspend fun removeRecentSearch(query: String) {
        val current = getCurrent().toMutableList()
        current.remove(query)
        storage.putString(KEY, json.encodeToString(current))
    }

    override suspend fun clearRecentSearches() {
        storage.remove(KEY)
    }

    private suspend fun getCurrent(): List<String> {
        val raw = storage.getString(KEY) ?: return emptyList()
        return try {
            json.decodeFromString<List<String>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private companion object {
        const val KEY = "recent_searches"
        const val MAX_RECENT_SEARCHES = 20
    }
}
