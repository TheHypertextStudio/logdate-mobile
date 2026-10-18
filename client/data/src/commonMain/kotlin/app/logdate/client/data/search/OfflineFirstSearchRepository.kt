package app.logdate.client.data.search

import app.logdate.client.database.dao.SearchDao
import app.logdate.client.database.dao.SearchResultEntity
import app.logdate.client.database.dao.people.PersonDao
import app.logdate.client.database.entities.people.PersonEntity
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import app.logdate.client.repository.search.SearchContentType
import app.logdate.client.repository.search.SearchFacets
import app.logdate.client.repository.search.SearchRepository
import app.logdate.client.repository.search.SearchResult
import app.logdate.client.repository.search.SearchScope
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Offline-first implementation of [SearchRepository].
 *
 * Uses the local [SearchDao] to perform FTS5 full-text search queries.
 * Search re-runs whenever the runtime-managed search index is rebuilt so the UI
 * can react to repair/bootstrap events without requiring new input.
 *
 * Results come from the search index. While People is turned on in Labs, people whose name or an
 * alias contains every word of the query join the results; while it is off, the people directory
 * is not read and no person is returned, so its names never reach a search surface.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineFirstSearchRepository(
    private val searchDao: SearchDao,
    private val personDao: PersonDao,
    private val featureFlags: FeatureFlagStore,
) : SearchRepository {
    private val correctionCache = mutableMapOf<String, String?>()
    private val correctionCacheMutex = Mutex()
    private val indexCheckMutex = Mutex()
    private var indexChecked = false

    override fun search(query: String): Flow<List<SearchResult>> =
        observedFtsFlow(query) { preparedQuery ->
            searchDao.search(preparedQuery.query, MAX_SEARCH_RESULTS).map { it.toSearchResult() }
        }

    override fun searchWithLimit(
        query: String,
        limit: Int,
    ): Flow<List<SearchResult>> =
        observedFtsFlow(query) { preparedQuery ->
            searchDao.searchWithLimit(preparedQuery.query, limit).map { it.toSearchResult() }
        }

    override fun searchWithSnippets(query: String): Flow<List<SearchResult>> =
        observedFtsFlow(query) { preparedQuery ->
            searchDao.searchWithSnippets(preparedQuery.query, MAX_SEARCH_RESULTS).map { it.toSearchResult() }
        }

    override fun searchRanked(
        query: String,
        limit: Int,
    ): Flow<List<SearchResult>> =
        observedFtsFlow(query) { preparedQuery ->
            val effectiveLimit =
                if (preparedQuery.isSingleCharacterPlainQuery) {
                    minOf(limit, SHORT_QUERY_RESULT_LIMIT)
                } else {
                    limit
                }
            val results =
                if (preparedQuery.isSingleCharacterPlainQuery) {
                    searchDao.searchRankedShortQuery(preparedQuery.query, effectiveLimit)
                } else {
                    searchDao.searchRanked(preparedQuery.query, effectiveLimit)
                }
            results.map { entity ->
                SearchResult(
                    uid = entity.getUuid(),
                    content = entity.content,
                    created = entity.getCreatedInstant(),
                    contentType = SearchContentType.fromFtsValue(entity.contentType),
                    rank = entity.rank,
                )
            }
        }

    override fun searchScoped(
        query: String,
        scope: SearchScope,
        limit: Int,
    ): Flow<List<SearchResult>> =
        observedFtsFlow(query, includePerson = scope::includesPerson) { preparedQuery ->
            val effectiveLimit =
                if (preparedQuery.isSingleCharacterPlainQuery) {
                    minOf(limit, SHORT_QUERY_RESULT_LIMIT)
                } else {
                    limit
                }
            searchDao
                .searchScoped(
                    query = preparedQuery.query,
                    typesEnabled = scope.contentTypes != null,
                    types = scope.contentTypes?.map { it.ftsValue } ?: listOf(""),
                    personId = scope.personId?.toString(),
                    placeId = scope.placeId?.toString(),
                    createdFrom = scope.createdFrom?.toEpochMilliseconds(),
                    createdBefore = scope.createdBefore?.toEpochMilliseconds(),
                    limit = effectiveLimit,
                ).map { entity ->
                    SearchResult(
                        uid = entity.getUuid(),
                        content = entity.content,
                        created = entity.getCreatedInstant(),
                        contentType = SearchContentType.fromFtsValue(entity.contentType),
                        rank = entity.rank,
                    )
                }
        }

    override fun searchInJournal(
        query: String,
        journalId: Uuid,
        limit: Int,
    ): Flow<List<SearchResult>> =
        observedFtsFlow(query) { preparedQuery ->
            searchDao.searchInJournal(preparedQuery.query, journalId.toString(), limit).map { entity ->
                SearchResult(
                    uid = entity.getUuid(),
                    content = entity.content,
                    created = entity.getCreatedInstant(),
                    contentType = SearchContentType.fromFtsValue(entity.contentType),
                    rank = entity.rank,
                )
            }
        }

    override fun observeFacets(
        query: String,
        scope: SearchScope,
    ): Flow<SearchFacets> {
        val prepared = prepareFtsQuery(query) ?: return flowOf(SearchFacets.Empty)

        val indexFacets =
            searchDao
                .observeGeneration()
                .distinctUntilChanged()
                .mapLatest { executePreparedQuery(prepared, isEmpty = { it.isEmpty }) { countIndexFacets(it, scope) } }
                .catch { error ->
                    Napier.e("Search facet query failed for \"$query\": ${error.message}", error)
                    emit(IndexFacets())
                }

        val typeScope = scope.copy(contentTypes = null)
        return combine(indexFacets, observePeopleResults(query)) { index, people ->
            val matchingPeople = people.count(typeScope::includesPerson)
            SearchFacets(
                byType =
                    index.byType.let { counts ->
                        if (matchingPeople > 0) counts + (SearchContentType.PERSON to matchingPeople) else counts
                    },
                byPerson = index.byPerson,
                byPlace = index.byPlace,
                createdAt = index.createdAt,
            )
        }
    }

    /**
     * Counts matches once per narrowing option, each ignoring the option it counts so a count is
     * what choosing that option would leave.
     */
    private suspend fun countIndexFacets(
        prepared: PreparedFtsQuery,
        scope: SearchScope,
    ): IndexFacets {
        val typeScope = scope.copy(contentTypes = null)
        val personScope = scope.copy(personId = null)
        val placeScope = scope.copy(placeId = null)
        val timeScope = scope.copy(createdFrom = null, createdBefore = null)
        return IndexFacets(
            byType =
                searchDao
                    .countByContentType(
                        prepared.query,
                        typeScope.contentTypes != null,
                        typeScope.typeValues(),
                        typeScope.personId?.toString(),
                        typeScope.placeId?.toString(),
                        typeScope.createdFrom?.toEpochMilliseconds(),
                        typeScope.createdBefore?.toEpochMilliseconds(),
                    ).associate { SearchContentType.fromFtsValue(it.contentType) to it.total },
            byPerson =
                searchDao
                    .countByPerson(
                        prepared.query,
                        personScope.contentTypes != null,
                        personScope.typeValues(),
                        personScope.personId?.toString(),
                        personScope.placeId?.toString(),
                        personScope.createdFrom?.toEpochMilliseconds(),
                        personScope.createdBefore?.toEpochMilliseconds(),
                    ).associate { Uuid.parse(it.personId) to it.total },
            byPlace =
                searchDao
                    .countByPlace(
                        prepared.query,
                        placeScope.contentTypes != null,
                        placeScope.typeValues(),
                        placeScope.personId?.toString(),
                        placeScope.placeId?.toString(),
                        placeScope.createdFrom?.toEpochMilliseconds(),
                        placeScope.createdBefore?.toEpochMilliseconds(),
                    ).associate { Uuid.parse(it.placeId) to it.total },
            createdAt =
                searchDao
                    .findCreatedTimes(
                        prepared.query,
                        timeScope.contentTypes != null,
                        timeScope.typeValues(),
                        timeScope.personId?.toString(),
                        timeScope.placeId?.toString(),
                        timeScope.createdFrom?.toEpochMilliseconds(),
                        timeScope.createdBefore?.toEpochMilliseconds(),
                    ).map { Instant.fromEpochMilliseconds(it) },
        )
    }

    private fun SearchScope.typeValues(): List<String> = contentTypes?.map { it.ftsValue } ?: listOf("")

    /**
     * Re-runs the same prepared query when the search index changes. A burst of changes, such as
     * a sync applying many records, re-runs it once after the changes settle.
     */
    private fun observedFtsFlow(
        query: String,
        includePerson: (SearchResult) -> Boolean = { true },
        block: suspend (preparedQuery: PreparedFtsQuery) -> List<SearchResult>,
    ): Flow<List<SearchResult>> {
        val prepared = prepareFtsQuery(query) ?: return flowOf(emptyList())

        val ftsResults =
            searchDao
                .observeGeneration()
                .distinctUntilChanged()
                .debounceAfterFirst(INDEX_CHANGE_SETTLE_TIME)
                .mapLatest {
                    ensureIndexCurrent()
                    executePreparedQuery(prepared, isEmpty = List<SearchResult>::isEmpty, block = block)
                }.catch { error ->
                    Napier.e("Search query failed", error)
                    emit(emptyList())
                }

        return combine(ftsResults, observePeopleResults(query)) { fts, people ->
            mergeSearchResults(fts = fts, people = people.filter(includePerson))
        }
    }

    /** Observes the people matching [query], or nothing at all while People is turned off. */
    private fun observePeopleResults(query: String): Flow<List<SearchResult>> {
        val tokens =
            query
                .trim()
                .lowercase()
                .split(whitespaceRun)
                .filter(String::isNotBlank)
        if (tokens.isEmpty()) return flowOf(emptyList())

        return featureFlags
            .observe(FeatureFlag.PEOPLE)
            .distinctUntilChanged()
            .flatMapLatest { peopleEnabled ->
                if (!peopleEnabled) return@flatMapLatest flowOf(emptyList())
                personDao.observeAll().mapLatest { people -> people.mapNotNull { person -> person.toSearchResult(tokens) } }
            }.catch { error ->
                Napier.e("People could not be searched", error)
                emit(emptyList())
            }
    }

    private fun PersonEntity.toSearchResult(tokens: List<String>): SearchResult? {
        val haystack = (listOf(name) + aliases).joinToString(" ").lowercase()
        if (!tokens.all(haystack::contains)) return null
        return SearchResult(
            uid = id,
            content = name,
            created = lastUpdated,
            contentType = SearchContentType.PERSON,
            rank = PERSON_SEARCH_RANK,
        )
    }

    private fun mergeSearchResults(
        fts: List<SearchResult>,
        people: List<SearchResult>,
    ): List<SearchResult> = (people + fts).distinctBy { "${it.contentType.ftsValue}_${it.uid}" }

    /** Fills the index from existing content once per process, before the first search reads it. */
    private suspend fun ensureIndexCurrent() {
        indexCheckMutex.withLock {
            if (indexChecked) return
            try {
                if (searchDao.rebuildIfNeeded()) Napier.i("Rebuilt the search index from existing content")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Napier.e("Search index rebuild failed; results may be incomplete", error)
            }
            indexChecked = true
        }
    }

    private suspend fun <T> executePreparedQuery(
        prepared: PreparedFtsQuery,
        isEmpty: (T) -> Boolean,
        block: suspend (preparedQuery: PreparedFtsQuery) -> T,
    ): T {
        val primaryResults = block(prepared)
        if (!isEmpty(primaryResults) || prepared.usesExplicitSyntax || prepared.tokens.isEmpty()) {
            return primaryResults
        }

        val correctedToken = correctedLastToken(prepared.tokens.last()) ?: return primaryResults
        return block(prepared.withLastToken(correctedToken))
    }

    private suspend fun correctedLastToken(lastToken: String): String? {
        if (lastToken.length < MIN_FUZZY_TOKEN_LENGTH) {
            return null
        }

        correctionCacheMutex.withLock {
            correctionCache[lastToken]?.let { return it }
            if (lastToken in correctionCache) {
                return null
            }
        }

        val maxDistance = if (lastToken.length >= LONG_FUZZY_TOKEN_LENGTH) 2 else 1
        val prefixLength = if (lastToken.length >= TWO_CHARACTER_PREFIX_MIN_LENGTH) 2 else 1
        val candidates =
            runCatching {
                searchDao.findVocabularyTerms(
                    prefix = lastToken.take(prefixLength),
                    minLength = (lastToken.length - maxDistance).coerceAtLeast(MIN_FUZZY_TOKEN_LENGTH),
                    maxLength = lastToken.length + maxDistance,
                    limit = MAX_FUZZY_CANDIDATES,
                )
            }.getOrElse { error ->
                Napier.w("FTS vocabulary lookup unavailable; skipping fuzzy fallback", error)
                correctionCacheMutex.withLock {
                    correctionCache[lastToken] = null
                }
                return null
            }

        val corrected =
            candidates
                .asSequence()
                .filterNot { it == lastToken }
                .map { candidate -> candidate to levenshteinDistance(lastToken, candidate) }
                .filter { (_, distance) -> distance in 1..maxDistance }
                .sortedWith(compareBy<Pair<String, Int>>({ it.second }, { abs(it.first.length - lastToken.length) }, { it.first }))
                .map { (candidate, _) -> candidate }
                .firstOrNull()
        correctionCacheMutex.withLock {
            correctionCache[lastToken] = corrected
        }
        return corrected
    }

    private fun SearchResultEntity.toSearchResult(): SearchResult =
        SearchResult(
            uid = getUuid(),
            content = content,
            created = getCreatedInstant(),
            contentType =
                when {
                    isTextNote() -> {
                        SearchContentType.TEXT_NOTE
                    }

                    isTranscription() -> {
                        SearchContentType.TRANSCRIPTION
                    }

                    else -> {
                        Napier.w("Unknown search result content type: $contentType, falling back to TEXT_NOTE")
                        SearchContentType.fromFtsValue(contentType)
                    }
                },
        )

    private companion object {
        /** Bounds the queries that callers do not limit, so a common term cannot read the whole index. */
        const val MAX_SEARCH_RESULTS = 200
        val INDEX_CHANGE_SETTLE_TIME = 300.milliseconds
        const val LONG_FUZZY_TOKEN_LENGTH = 7
        const val MAX_FUZZY_CANDIDATES = 64
        const val MIN_FUZZY_TOKEN_LENGTH = 4
        const val PERSON_SEARCH_RANK = -1.0
        const val SHORT_QUERY_RESULT_LIMIT = 20
        const val TWO_CHARACTER_PREFIX_MIN_LENGTH = 5
    }
}

/** Counts from the full-text index, before people are added to the type counts. */
private data class IndexFacets(
    val byType: Map<SearchContentType, Int> = emptyMap(),
    val byPerson: Map<Uuid, Int> = emptyMap(),
    val byPlace: Map<Uuid, Int> = emptyMap(),
    val createdAt: List<Instant> = emptyList(),
) {
    /** True when nothing in the index matched, which is when a typo-corrected retry is worth making. */
    val isEmpty: Boolean get() = byType.isEmpty()
}

/**
 * Whether this person result survives the scope.
 *
 * People are matched by name outside the FTS index and have no capture date or place, so any place
 * or date narrowing removes them. A person narrowing keeps only that person.
 */
private fun SearchScope.includesPerson(person: SearchResult): Boolean {
    val types = contentTypes
    return (types == null || SearchContentType.PERSON in types) &&
        placeId == null &&
        createdFrom == null &&
        createdBefore == null &&
        (personId == null || person.uid == personId)
}

/**
 * Prepared FTS query with optional token metadata for typo-tolerant fallback.
 */
internal data class PreparedFtsQuery(
    val query: String,
    val tokens: List<String>,
    val usesExplicitSyntax: Boolean,
) {
    val isSingleCharacterPlainQuery: Boolean
        get() = !usesExplicitSyntax && tokens.size == 1 && tokens.single().length == 1

    fun withLastToken(token: String): PreparedFtsQuery =
        copy(
            query = buildPrefixQuery(tokens.dropLast(1) + token),
            tokens = tokens.dropLast(1) + token,
        )
}

/**
 * Prepares user input for the local FTS index.
 *
 * Plain typing defaults to prefix matching on every token so partial words feel responsive.
 * If the user enters explicit FTS syntax, the query is sanitized but otherwise preserved.
 */
internal fun prepareFtsQuery(query: String): PreparedFtsQuery? {
    val trimmed = query.trim()
    if (trimmed.isBlank()) return null

    if (looksLikeExplicitFtsSyntax(trimmed)) {
        val explicitQuery = sanitizeExplicitFtsQuery(trimmed)
        return if (explicitQuery.isBlank()) {
            null
        } else {
            PreparedFtsQuery(
                query = explicitQuery,
                tokens = emptyList(),
                usesExplicitSyntax = true,
            )
        }
    }

    val tokens =
        plainTokenRegex
            .findAll(trimmed.lowercase())
            .map { it.value }
            .toList()
    if (tokens.isEmpty()) {
        return null
    }

    return PreparedFtsQuery(
        query = buildPrefixQuery(tokens),
        tokens = tokens,
        usesExplicitSyntax = false,
    )
}

private fun sanitizeExplicitFtsQuery(query: String): String {
    val ftsOperators = listOf("AND", "OR", "NOT")
    val words = query.trim().split("\\s+".toRegex()).toMutableList()

    while (words.isNotEmpty() && words.first().uppercase() in ftsOperators) {
        words.removeFirst()
    }
    while (words.isNotEmpty() && words.last().uppercase() in ftsOperators) {
        words.removeLast()
    }

    if (words.isEmpty()) return ""

    var result = words.joinToString(" ")

    val quoteCount = result.count { it == '"' }
    if (quoteCount % 2 != 0) {
        result = result.replace("\"", "")
    }

    return result
}

private fun buildPrefixQuery(tokens: List<String>): String = tokens.joinToString(" ") { "$it*" }

private fun looksLikeExplicitFtsSyntax(query: String): Boolean =
    query.contains('"') ||
        query.contains('(') ||
        query.contains(')') ||
        query.contains('*') ||
        query.contains(':') ||
        explicitOperatorRegex.containsMatchIn(query)

private fun levenshteinDistance(
    source: String,
    target: String,
): Int {
    if (source == target) return 0
    if (source.isEmpty()) return target.length
    if (target.isEmpty()) return source.length

    val previous = IntArray(target.length + 1) { it }
    val current = IntArray(target.length + 1)

    for (sourceIndex in source.indices) {
        current[0] = sourceIndex + 1
        for (targetIndex in target.indices) {
            val substitutionCost = if (source[sourceIndex] == target[targetIndex]) 0 else 1
            current[targetIndex + 1] =
                minOf(
                    current[targetIndex] + 1,
                    previous[targetIndex + 1] + 1,
                    previous[targetIndex] + substitutionCost,
                )
        }
        previous.indices.forEach { index ->
            previous[index] = current[index]
        }
    }

    return previous[target.length]
}

private val whitespaceRun = Regex("\\s+")
private val explicitOperatorRegex = Regex("""\b(?:AND|OR|NOT|NEAR)\b""")
private val plainTokenRegex = Regex("""[\p{L}\p{N}]+""")
