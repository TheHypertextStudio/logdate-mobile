package app.logdate.client.database.dao.rewind

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import app.logdate.client.database.entities.rewind.RewindConstants
import app.logdate.client.database.entities.rewind.RewindEntity
import app.logdate.client.database.entities.rewind.RewindImageContentEntity
import app.logdate.client.database.entities.rewind.RewindTextContentEntity
import app.logdate.client.database.entities.rewind.RewindVideoContentEntity
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A data access object for LogDate Rewinds.
 *
 * This DAO provides methods for interacting with rewinds and their
 * associated content. It uses separate tables for different content
 * types to ensure type safety and efficiency.
 */
@Dao
interface CachedRewindDao {
    /**
     * Retrieves all rewinds.
     *
     * @return Flow emitting a list of all rewinds
     */
    @Query("SELECT * FROM rewinds ORDER BY generationDate DESC")
    fun getAllRewinds(): Flow<List<RewindEntity>>

    /**
     * Retrieves a rewind by its unique identifier.
     *
     * @param uid The unique identifier of the rewind to retrieve
     * @return Flow emitting the rewind if found
     */
    @Query("SELECT * FROM rewinds WHERE ${RewindConstants.COLUMN_UID} = :uid")
    fun getRewindById(uid: Uuid): Flow<RewindEntity>

    /**
     * Retrieves a rewind for a given time period.
     *
     * @param start The start of the time period
     * @param end The end of the time period
     * @return Flow emitting the rewind for the given period, or null if none exists
     */
    @Query(
        """
        SELECT * FROM rewinds 
        WHERE ${RewindConstants.COLUMN_START_DATE} = :start 
        AND ${RewindConstants.COLUMN_END_DATE} = :end
        LIMIT 1
    """,
    )
    fun getRewindForPeriod(
        start: Instant,
        end: Instant,
    ): Flow<RewindEntity?>

    /**
     * Checks if a rewind exists for the given time period.
     *
     * @param start The start of the time period
     * @param end The end of the time period
     * @return True if a rewind exists for the period, false otherwise
     */
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM rewinds 
            WHERE ${RewindConstants.COLUMN_START_DATE} = :start 
            AND ${RewindConstants.COLUMN_END_DATE} = :end
        )
    """,
    )
    suspend fun rewindExistsForPeriod(
        start: Instant,
        end: Instant,
    ): Boolean

    /**
     * Retrieves all rewinds whose date range falls entirely within [rangeStart]..[rangeEnd].
     *
     * Used by the annual rewind pipeline to collect all weekly and milestone rewinds
     * for a given year. Results are ordered chronologically by start date.
     */
    @Query(
        """
        SELECT * FROM rewinds
        WHERE ${RewindConstants.COLUMN_START_DATE} >= :rangeStart
        AND ${RewindConstants.COLUMN_END_DATE} <= :rangeEnd
        ORDER BY ${RewindConstants.COLUMN_START_DATE} ASC
    """,
    )
    fun getRewindsContainedIn(
        rangeStart: Instant,
        rangeEnd: Instant,
    ): Flow<List<RewindEntity>>

    /**
     * Inserts a new rewind.
     *
     * @param rewind The rewind to insert
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRewind(rewind: RewindEntity)

    @Update
    suspend fun updateRewind(rewind: RewindEntity): Int

    /**
     * Retrieves all text content for a given rewind.
     *
     * @param rewindId The ID of the rewind
     * @return List of text content entities
     */
    @Query("SELECT * FROM rewind_text_content WHERE ${RewindConstants.COLUMN_REWIND_ID} = :rewindId ORDER BY timestamp ASC")
    suspend fun getTextContentForRewind(rewindId: Uuid): List<RewindTextContentEntity>

    /**
     * Retrieves all image content for a given rewind.
     *
     * @param rewindId The ID of the rewind
     * @return List of image content entities
     */
    @Query("SELECT * FROM rewind_image_content WHERE ${RewindConstants.COLUMN_REWIND_ID} = :rewindId ORDER BY timestamp ASC")
    suspend fun getImageContentForRewind(rewindId: Uuid): List<RewindImageContentEntity>

    /**
     * Retrieves all video content for a given rewind.
     *
     * @param rewindId The ID of the rewind
     * @return List of video content entities
     */
    @Query("SELECT * FROM rewind_video_content WHERE ${RewindConstants.COLUMN_REWIND_ID} = :rewindId ORDER BY timestamp ASC")
    suspend fun getVideoContentForRewind(rewindId: Uuid): List<RewindVideoContentEntity>

    /**
     * Inserts text content.
     *
     * @param content The text content to insert
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTextContent(content: List<RewindTextContentEntity>)

    /**
     * Inserts image content.
     *
     * @param content The image content to insert
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertImageContent(content: List<RewindImageContentEntity>)

    /**
     * Inserts video content.
     *
     * @param content The video content to insert
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVideoContent(content: List<RewindVideoContentEntity>)

    /**
     * Marks a rewind as viewed for the first time: sets the viewed flag, records the
     * timestamp, and sets the view count to 1. No-ops if already viewed.
     *
     * @return The number of rows affected (1 on first view, 0 if already viewed).
     */
    @Query(
        """
        UPDATE rewinds
        SET ${RewindConstants.COLUMN_IS_VIEWED} = 1,
            ${RewindConstants.COLUMN_FIRST_VIEWED_AT} = :now,
            ${RewindConstants.COLUMN_VIEW_COUNT} = 1
        WHERE ${RewindConstants.COLUMN_UID} = :uid
        AND ${RewindConstants.COLUMN_IS_VIEWED} = 0
    """,
    )
    suspend fun markAsFirstViewed(
        uid: Uuid,
        now: Instant,
    ): Int

    /**
     * Increments the view count for a rewind that has already been viewed at least once.
     */
    @Query(
        """
        UPDATE rewinds
        SET ${RewindConstants.COLUMN_VIEW_COUNT} = ${RewindConstants.COLUMN_VIEW_COUNT} + 1
        WHERE ${RewindConstants.COLUMN_UID} = :uid
        AND ${RewindConstants.COLUMN_IS_VIEWED} = 1
    """,
    )
    suspend fun incrementViewCount(uid: Uuid)

    /**
     * Deletes a rewind and all its content.
     * Due to foreign key constraints with CASCADE delete,
     * this will automatically delete all associated content.
     *
     * @param uid The unique identifier of the rewind to delete
     */
    @Query("DELETE FROM rewinds WHERE ${RewindConstants.COLUMN_UID} = :uid")
    suspend fun deleteRewind(uid: Uuid)

    @Query("DELETE FROM rewind_text_content WHERE ${RewindConstants.COLUMN_REWIND_ID} = :uid")
    suspend fun deleteTextContentForRewind(uid: Uuid)

    @Query("DELETE FROM rewind_image_content WHERE ${RewindConstants.COLUMN_REWIND_ID} = :uid")
    suspend fun deleteImageContentForRewind(uid: Uuid)

    @Query("DELETE FROM rewind_video_content WHERE ${RewindConstants.COLUMN_REWIND_ID} = :uid")
    suspend fun deleteVideoContentForRewind(uid: Uuid)

    /**
     * Helper method to get all content for a rewind.
     * This combines text, image, and video content into respective lists.
     *
     * @param rewindId The ID of the rewind
     * @return RewindContent containing lists of text, image, and video content
     */
    @Transaction
    suspend fun getContentForRewind(rewindId: Uuid): RewindContent {
        val textContent = getTextContentForRewind(rewindId)
        val imageContent = getImageContentForRewind(rewindId)
        val videoContent = getVideoContentForRewind(rewindId)
        return RewindContent(textContent, imageContent, videoContent)
    }

    @Query(
        "SELECT * FROM rewind_text_content WHERE ${RewindConstants.COLUMN_REWIND_ID} IN (:rewindIds) ORDER BY timestamp ASC",
    )
    suspend fun getTextContentForRewinds(rewindIds: List<Uuid>): List<RewindTextContentEntity>

    @Query(
        "SELECT * FROM rewind_image_content WHERE ${RewindConstants.COLUMN_REWIND_ID} IN (:rewindIds) ORDER BY timestamp ASC",
    )
    suspend fun getImageContentForRewinds(rewindIds: List<Uuid>): List<RewindImageContentEntity>

    @Query(
        "SELECT * FROM rewind_video_content WHERE ${RewindConstants.COLUMN_REWIND_ID} IN (:rewindIds) ORDER BY timestamp ASC",
    )
    suspend fun getVideoContentForRewinds(rewindIds: List<Uuid>): List<RewindVideoContentEntity>

    /**
     * Reads the content of every rewind in [rewindIds] with one query per content table, keyed by
     * rewind. A rewind without content maps to empty lists.
     */
    @Transaction
    suspend fun getContentForRewinds(rewindIds: List<Uuid>): Map<Uuid, RewindContent> {
        if (rewindIds.isEmpty()) return emptyMap()
        val text = rewindIds.chunked(CONTENT_BATCH_SIZE).flatMap { getTextContentForRewinds(it) }.groupBy { it.rewindId }
        val images = rewindIds.chunked(CONTENT_BATCH_SIZE).flatMap { getImageContentForRewinds(it) }.groupBy { it.rewindId }
        val videos = rewindIds.chunked(CONTENT_BATCH_SIZE).flatMap { getVideoContentForRewinds(it) }.groupBy { it.rewindId }
        return rewindIds.associateWith { id ->
            RewindContent(text[id].orEmpty(), images[id].orEmpty(), videos[id].orEmpty())
        }
    }

    /**
     * Helper method to insert all content for a rewind.
     * This splits the content into the appropriate tables based on type.
     *
     * @param textContent List of text content entities
     * @param imageContent List of image content entities
     * @param videoContent List of video content entities
     */
    @Transaction
    suspend fun insertRewindContent(
        textContent: List<RewindTextContentEntity> = emptyList(),
        imageContent: List<RewindImageContentEntity> = emptyList(),
        videoContent: List<RewindVideoContentEntity> = emptyList(),
    ) {
        if (textContent.isNotEmpty()) {
            insertTextContent(textContent)
        }
        if (imageContent.isNotEmpty()) {
            insertImageContent(imageContent)
        }
        if (videoContent.isNotEmpty()) {
            insertVideoContent(videoContent)
        }
    }

    /** Publishes a newly generated story only when all its panels have been stored. */
    @Transaction
    suspend fun insertRewindWithContent(
        rewind: RewindEntity,
        textContent: List<RewindTextContentEntity>,
        imageContent: List<RewindImageContentEntity>,
        videoContent: List<RewindVideoContentEntity>,
    ) {
        insertRewind(rewind)
        insertRewindContent(textContent, imageContent, videoContent)
    }

    /** Updates panels without deleting the parent row or its attached replies. */
    @Transaction
    suspend fun replaceRewind(
        existingUid: Uuid,
        replacement: RewindEntity,
        textContent: List<RewindTextContentEntity>,
        imageContent: List<RewindImageContentEntity>,
        videoContent: List<RewindVideoContentEntity>,
    ) {
        require(replacement.uid == existingUid)
        check(updateRewind(replacement) == 1) { "Rewind to refresh no longer exists" }
        deleteTextContentForRewind(existingUid)
        deleteImageContentForRewind(existingUid)
        deleteVideoContentForRewind(existingUid)
        insertRewindContent(textContent, imageContent, videoContent)
    }
}

/** Stays well under SQLite's bound-parameter limit. */
private const val CONTENT_BATCH_SIZE = 500

/**
 * Helper data class to hold all content for a rewind.
 */
data class RewindContent(
    val textContent: List<RewindTextContentEntity>,
    val imageContent: List<RewindImageContentEntity>,
    val videoContent: List<RewindVideoContentEntity>,
)
