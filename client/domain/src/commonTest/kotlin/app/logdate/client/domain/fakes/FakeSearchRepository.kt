package app.logdate.client.domain.fakes

import app.logdate.client.repository.search.SearchRepository
import app.logdate.client.repository.search.SearchResult
import app.logdate.client.repository.search.SearchScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlin.uuid.Uuid

/**
 * Minimal fake for testing use cases that depend on [SearchRepository].
 */
class FakeSearchRepository(
    initialResults: List<SearchResult> = emptyList(),
) : SearchRepository {
    private val resultsFlow = MutableStateFlow(initialResults)

    /** The scope of the most recent [searchScoped] call, or null if none has been made. */
    var lastScope: SearchScope? = null
        private set

    /** The limit of the most recent [searchScoped] call, or null if none has been made. */
    var lastScopedLimit: Int? = null
        private set

    fun setResults(results: List<SearchResult>) {
        resultsFlow.value = results
    }

    override fun search(query: String): Flow<List<SearchResult>> =
        resultsFlow.map { results ->
            if (query.isBlank()) {
                emptyList()
            } else {
                results.filter { it.content.contains(query, ignoreCase = true) }
            }
        }

    override fun searchWithLimit(
        query: String,
        limit: Int,
    ): Flow<List<SearchResult>> = search(query).map { it.take(limit) }

    override fun searchWithSnippets(query: String): Flow<List<SearchResult>> = search(query)

    override fun searchRanked(
        query: String,
        limit: Int,
    ): Flow<List<SearchResult>> = search(query).map { it.take(limit) }

    override fun searchScoped(
        query: String,
        scope: SearchScope,
        limit: Int,
    ): Flow<List<SearchResult>> {
        lastScope = scope
        lastScopedLimit = limit
        return super.searchScoped(query, scope, limit)
    }

    override fun searchInJournal(
        query: String,
        journalId: Uuid,
        limit: Int,
    ): Flow<List<SearchResult>> = search(query).map { it.take(limit) }
}
