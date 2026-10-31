package app.logdate.client.repository.search

import kotlinx.coroutines.flow.Flow
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
     * Searches entries within a specific journal.
     */
    fun searchInJournal(
        query: String,
        journalId: Uuid,
        limit: Int = 50,
    ): Flow<List<SearchResult>>
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
