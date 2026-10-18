package app.logdate.client.repository.search

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Repository for full-text search across all indexed content.
 *
 * Backed by FTS5. Supports text notes, transcriptions, journals, media captions,
 * places, rewinds, stickers, and postcards.
 */
interface SearchRepository {
    /**
     * Searches all indexed content using full-text search.
     *
     * @param query The search query (FTS5 syntax supported)
     * @return Flow of the best few hundred search results, ordered by relevance and date
     */
    fun search(query: String): Flow<List<SearchResult>>

    /**
     * Searches with a limit on the number of results.
     */
    fun searchWithLimit(
        query: String,
        limit: Int,
    ): Flow<List<SearchResult>>

    /**
     * Searches with highlighted snippets (FTS5 snippet markers), returning the best few hundred results.
     */
    fun searchWithSnippets(query: String): Flow<List<SearchResult>>

    /**
     * Searches all indexed content with FTS5 relevance ranking.
     *
     * Returns results with rank scores for proper ordering across all content types.
     * This is the primary query path for universal search.
     */
    fun searchRanked(
        query: String,
        limit: Int = 50,
    ): Flow<List<SearchResult>>

    /**
     * Searches like [searchRanked], narrowed by [scope] before [limit] is applied.
     *
     * Narrowing first means the best matches inside the scope are returned, instead of whatever
     * remains of the best matches overall. This default can only narrow by what a result carries
     * itself (its type and creation time); implementations backed by the index also narrow by
     * person and place, which a result does not carry.
     */
    fun searchScoped(
        query: String,
        scope: SearchScope,
        limit: Int = 50,
    ): Flow<List<SearchResult>> = searchRanked(query, limit).map { results -> results.filter(scope::allows) }

    /**
     * Searches entries within a specific journal.
     */
    fun searchInJournal(
        query: String,
        journalId: Uuid,
        limit: Int = 50,
    ): Flow<List<SearchResult>>
}

/**
 * What a search is narrowed to. A null option does not narrow.
 *
 * @property contentTypes Keep only these types of result.
 * @property personId Keep results linked to this person, and the person themself.
 * @property placeId Keep results captured at this place, and the place itself.
 * @property createdFrom Keep results created at or after this instant.
 * @property createdBefore Keep results created before this instant.
 */
data class SearchScope(
    val contentTypes: Set<SearchContentType>? = null,
    val personId: Uuid? = null,
    val placeId: Uuid? = null,
    val createdFrom: Instant? = null,
    val createdBefore: Instant? = null,
) {
    /** Whether [result] passes the narrowing that its own type and creation time can decide. */
    fun allows(result: SearchResult): Boolean =
        (contentTypes == null || result.contentType in contentTypes) &&
            (createdFrom == null || result.created >= createdFrom) &&
            (createdBefore == null || result.created < createdBefore)
}

/**
 * A search result from the FTS5 index.
 *
 * Represents any searchable entity in the app. The [contentType] field determines
 * what kind of entity matched and how to navigate to it.
 */
data class SearchResult(
    val uid: Uuid,
    val content: String,
    val created: Instant,
    val contentType: SearchContentType,
    val rank: Double = 0.0,
)

/**
 * The type of entity that produced a search result.
 *
 * Each value corresponds to a `contentType` string stored in the FTS5 index.
 */
enum class SearchContentType(
    val ftsValue: String,
) {
    TEXT_NOTE("text_note"),
    TRANSCRIPTION("transcription"),
    JOURNAL("journal"),
    MEDIA_CAPTION("media_caption"),
    PLACE("place"),
    REWIND("rewind"),
    STICKER("sticker"),
    POSTCARD("postcard"),
    AMBIENT_SOUND("ambient_sound"),
    PERSON("person"),
    ;

    companion object {
        fun fromFtsValue(value: String): SearchContentType = entries.find { it.ftsValue == value } ?: TEXT_NOTE
    }
}

/**
 * Legacy type alias for backward compatibility.
 */
@Deprecated("Use SearchContentType instead", ReplaceWith("SearchContentType"))
typealias SearchResultType = SearchContentType
