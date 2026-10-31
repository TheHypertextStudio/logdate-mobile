package app.logdate.client.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query
import androidx.room.SkipQueryVerification
import androidx.room.Transaction
import app.logdate.client.database.INDEX_AMBIENT_SOUNDS_SQL
import app.logdate.client.database.INDEX_JOURNALS_SQL
import app.logdate.client.database.INDEX_MEDIA_CAPTIONS_SQL
import app.logdate.client.database.INDEX_PLACES_SQL
import app.logdate.client.database.INDEX_POSTCARDS_SQL
import app.logdate.client.database.INDEX_REWINDS_SQL
import app.logdate.client.database.INDEX_STICKERS_SQL
import app.logdate.client.database.INDEX_TEXT_NOTES_SQL
import app.logdate.client.database.INDEX_TRANSCRIPTIONS_SQL
import app.logdate.client.database.SEARCH_INDEX_SCHEMA_VERSION
import app.logdate.client.database.SEARCH_SOURCE_ROWS_EXIST_SQL
import kotlinx.coroutines.flow.Flow
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Data Access Object for full-text search operations.
 *
 * Uses FTS5 (Full-Text Search) virtual table for fast text search across entries.
 * Supports fuzzy matching, relevance ranking, and boolean operators.
 *
 * Note: @SkipQueryVerification is used because Room cannot validate runtime-managed FTS5
 * virtual tables at compile time. The entries_fts table and its triggers are created when the
 * database opens; [rebuildIfNeeded] fills it from existing content afterwards.
 */
@Dao
@SkipQueryVerification
interface SearchDao {
    @Query("SELECT generation FROM search_index_metadata WHERE id = 1")
    suspend fun getGeneration(): Long?

    @Query("SELECT schemaVersion FROM search_index_metadata WHERE id = 1")
    suspend fun getIndexSchemaVersion(): Long?

    @Query("SELECT EXISTS(SELECT 1 FROM entries_fts LIMIT 1)")
    suspend fun hasIndexedEntries(): Boolean

    @Query(SEARCH_SOURCE_ROWS_EXIST_SQL)
    suspend fun hasIndexableContent(): Boolean

    @Query("DELETE FROM entries_fts")
    suspend fun clearIndex()

    @Query(INDEX_TEXT_NOTES_SQL)
    suspend fun indexTextNotes()

    @Query(INDEX_TRANSCRIPTIONS_SQL)
    suspend fun indexTranscriptions()

    @Query(INDEX_JOURNALS_SQL)
    suspend fun indexJournals()

    @Query(INDEX_MEDIA_CAPTIONS_SQL)
    suspend fun indexMediaCaptions()

    @Query(INDEX_PLACES_SQL)
    suspend fun indexPlaces()

    @Query(INDEX_REWINDS_SQL)
    suspend fun indexRewinds()

    @Query(INDEX_STICKERS_SQL)
    suspend fun indexStickers()

    @Query(INDEX_POSTCARDS_SQL)
    suspend fun indexPostcards()

    @Query(INDEX_AMBIENT_SOUNDS_SQL)
    suspend fun indexAmbientSounds()

    @Query("INSERT INTO entries_fts(entries_fts) VALUES('optimize')")
    suspend fun optimizeIndex()

    @Query(
        "UPDATE search_index_metadata SET schemaVersion = :schemaVersion, generation = generation + 1, " +
            "lastRebuiltAt = :rebuiltAt WHERE id = 1",
    )
    suspend fun markIndexRebuilt(
        schemaVersion: Long,
        rebuiltAt: Long,
    )

    /**
     * Fills the search index from existing content when it was built by an older version of the
     * index, or is empty while content exists. Writes made since the database opened are already
     * indexed by triggers, so rebuilding replaces them with identical rows.
     *
     * @return true when the index was rebuilt
     */
    @Transaction
    suspend fun rebuildIfNeeded(): Boolean {
        val current = getIndexSchemaVersion() == SEARCH_INDEX_SCHEMA_VERSION
        if (current && (hasIndexedEntries() || !hasIndexableContent())) return false
        clearIndex()
        indexTextNotes()
        indexTranscriptions()
        indexJournals()
        indexMediaCaptions()
        indexPlaces()
        indexRewinds()
        indexStickers()
        indexPostcards()
        indexAmbientSounds()
        optimizeIndex()
        markIndexRebuilt(SEARCH_INDEX_SCHEMA_VERSION, Clock.System.now().toEpochMilliseconds())
        return true
    }

    /**
     * Emits whenever the runtime-managed search index changes or is rebuilt.
     */
    @Query("SELECT generation FROM search_index_metadata WHERE id = 1")
    fun observeGeneration(): Flow<Long?>

    @Query(
        """
        SELECT uid, content, created, contentType
        FROM entries_fts
        ORDER BY created DESC
        """,
    )
    suspend fun getIndexedEntries(): List<SearchResultEntity>

    /**
     * Searches all entries using FTS5 MATCH operator.
     *
     * Returns results ordered by relevance (rank) and then by date (newest first).
     * The query supports:
     * - Simple text search: "hello world"
     * - Boolean operators: "hello AND world", "hello OR world", "hello NOT world"
     * - Phrase search: "\"hello world\""
     * - Prefix search: "hel*"
     *
     * @param query The search query (will be used in FTS5 MATCH clause)
     * @param limit Maximum number of results to return
     * @return List of search results ordered by relevance and date
     */
    @Query(
        """
        SELECT uid, content, created, contentType
        FROM entries_fts
        WHERE entries_fts MATCH :query
        ORDER BY bm25(entries_fts), created DESC
        LIMIT :limit
    """,
    )
    suspend fun search(
        query: String,
        limit: Int,
    ): List<SearchResultEntity>

    /**
     * Searches entries with a limit on the number of results.
     *
     * Useful for showing a limited number of search suggestions.
     *
     * @param query The search query
     * @param limit Maximum number of results to return
     * @return List of limited search results
     */
    @Query(
        """
        SELECT
            uid,
            snippet(entries_fts, 1, '[', ']', '...', 16) as content,
            created,
            contentType
        FROM entries_fts
        WHERE entries_fts MATCH :query
        ORDER BY bm25(entries_fts), created DESC
        LIMIT :limit
    """,
    )
    suspend fun searchWithLimit(
        query: String,
        limit: Int,
    ): List<SearchResultEntity>

    /**
     * Searches entries with highlighted snippets.
     *
     * Uses FTS5's snippet() function to return excerpts with search term highlighting.
     * Markers: '[' and ']' surround matching terms in the snippet.
     *
     * @param query The search query
     * @param limit Maximum number of results to return
     * @return List of search results with highlighted snippets
     */
    @Query(
        """
        SELECT
            uid,
            snippet(entries_fts, 1, '[', ']', '...', 20) as content,
            created,
            contentType
        FROM entries_fts
        WHERE entries_fts MATCH :query
        ORDER BY bm25(entries_fts), created DESC
        LIMIT :limit
    """,
    )
    suspend fun searchWithSnippets(
        query: String,
        limit: Int,
    ): List<SearchResultEntity>

    /**
     * Searches all indexed content with FTS5 relevance ranking.
     *
     * Returns results with snippet highlighting and rank scores. This is the
     * primary query path for universal search — a single query covers all
     * content types (text notes, transcriptions, journals, places, etc.).
     *
     * @param query The search query (FTS5 syntax)
     * @param limit Maximum number of results
     * @return Ranked results with snippet content
     */
    @Query(
        """
        SELECT
            uid,
            snippet(entries_fts, 1, '[', ']', '...', 24) as content,
            created,
            contentType,
            bm25(entries_fts) as result_rank
        FROM entries_fts
        WHERE entries_fts MATCH :query
        ORDER BY bm25(entries_fts), created DESC
        LIMIT :limit
    """,
    )
    suspend fun searchRanked(
        query: String,
        limit: Int = 50,
    ): List<RankedSearchResultEntity>

    /**
     * Short-query ranked search with a smaller snippet window and capped result count.
     */
    @Query(
        """
        SELECT
            uid,
            snippet(entries_fts, 1, '[', ']', '...', 16) as content,
            created,
            contentType,
            bm25(entries_fts) as result_rank
        FROM entries_fts
        WHERE entries_fts MATCH :query
        ORDER BY bm25(entries_fts), created DESC
        LIMIT :limit
        """,
    )
    suspend fun searchRankedShortQuery(
        query: String,
        limit: Int,
    ): List<RankedSearchResultEntity>

    /**
     * Searches entries that belong to a specific journal.
     *
     * Joins the FTS index with the journal_content_links table to scope results.
     *
     * @param query The search query (FTS5 syntax)
     * @param journalId The journal to search within
     * @param limit Maximum number of results
     * @return Ranked results scoped to the journal
     */
    @Query(
        """
        SELECT
            f.uid,
            snippet(entries_fts, 1, '[', ']', '...', 24) as content,
            f.created,
            f.contentType,
            bm25(entries_fts) as result_rank
        FROM entries_fts f
        INNER JOIN journal_content_links jcl ON f.uid = jcl.content_id
        WHERE entries_fts MATCH :query
          AND jcl.journal_id = :journalId
        ORDER BY bm25(entries_fts), f.created DESC
        LIMIT :limit
        """,
    )
    suspend fun searchInJournal(
        query: String,
        journalId: String,
        limit: Int = 50,
    ): List<RankedSearchResultEntity>

    /**
     * Returns indexed terms for typo-tolerant fallback against the FTS vocabulary.
     */
    @Query(
        """
        SELECT term
        FROM entries_fts_vocab
        WHERE term LIKE :prefix || '%'
          AND length(term) BETWEEN :minLength AND :maxLength
        ORDER BY doc DESC, cnt DESC
        LIMIT :limit
        """,
    )
    suspend fun findVocabularyTerms(
        prefix: String,
        minLength: Int,
        maxLength: Int,
        limit: Int,
    ): List<String>
}

/**
 * Entity representing a ranked search result with FTS5 relevance score.
 */
data class RankedSearchResultEntity(
    val uid: String,
    val content: String,
    val created: Long,
    val contentType: String,
    @ColumnInfo(name = "result_rank")
    val rank: Double,
) {
    /** Parses the string [uid] into a [Uuid]. */
    fun getUuid(): Uuid = Uuid.parse(uid)

    /** Converts the epoch-millisecond [created] timestamp to an [Instant]. */
    fun getCreatedInstant(): Instant = Instant.fromEpochMilliseconds(created)
}

/**
 * Entity representing a search result from the FTS table.
 *
 * This is a simplified entity that contains just the essential search result information.
 * The actual full entity can be loaded from the source tables using the uid.
 */
data class SearchResultEntity(
    val uid: String,
    val content: String,
    val created: Long,
    val contentType: String,
) {
    /**
     * Converts the string UID to a Uuid.
     */
    fun getUuid(): Uuid = Uuid.parse(uid)

    /**
     * Converts the timestamp to an Instant.
     */
    fun getCreatedInstant(): Instant = Instant.fromEpochMilliseconds(created)

    /**
     * Returns true if this is a text note result.
     */
    fun isTextNote(): Boolean = contentType == "text_note"

    /**
     * Returns true if this is a transcription result.
     */
    fun isTranscription(): Boolean = contentType == "transcription"
}
