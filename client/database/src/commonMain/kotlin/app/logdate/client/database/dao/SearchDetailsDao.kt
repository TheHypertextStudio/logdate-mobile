package app.logdate.client.database.dao

import androidx.room.Dao
import androidx.room.Query

/**
 * Batched lookups that describe search results with the media, dates and covers their rows show.
 *
 * The full-text index stores only text and a date for each result. These queries fetch what each
 * kind of result needs from the table that owns it, many results at a time, so describing a page
 * of results costs one query per kind of result instead of one per result. Every query takes the
 * result ids as strings, which is how the index and the tables store them, and skips deleted rows.
 */
@Dao
interface SearchDetailsDao {
    /** The media file of each image note. */
    @Query("SELECT uid AS uid, contentUri AS contentUri FROM image_notes WHERE uid IN (:ids) AND deletedAt IS NULL")
    suspend fun imageDetails(ids: List<String>): List<MediaDetailRow>

    /** The media file and length of each video note. */
    @Query(
        "SELECT uid AS uid, contentUri AS contentUri, durationMs AS durationMs FROM video_notes WHERE uid IN (:ids) AND deletedAt IS NULL",
    )
    suspend fun videoDetails(ids: List<String>): List<VideoDetailRow>

    /** The length of each audio note. */
    @Query("SELECT uid AS uid, durationMs AS durationMs FROM audio_notes WHERE uid IN (:ids) AND deletedAt IS NULL")
    suspend fun audioDetails(ids: List<String>): List<AudioDetailRow>

    /** The cover and number of entries of each journal. */
    @Query(
        "SELECT j.id AS id, j.coverImageUri AS coverUri, " +
            "(SELECT COUNT(*) FROM journal_content_links l WHERE l.journal_id = j.id AND l.deletedAt IS NULL) AS entryCount " +
            "FROM journals j WHERE j.id IN (:ids) AND j.deletedAt IS NULL",
    )
    suspend fun journalDetails(ids: List<String>): List<JournalDetailRow>

    /** What a journal preview shows of one journal, or nothing when it is unknown or deleted. */
    @Query(
        "SELECT title AS title, description AS description, created AS created, coverImageUri AS coverUri " +
            "FROM journals WHERE id = :id AND deletedAt IS NULL",
    )
    suspend fun journalPreview(id: String): JournalPreviewRow?

    /** The newest [limit] text notes in a journal, newest first. */
    @Query(
        "SELECT t.uid AS uid, t.content AS content, t.created AS created FROM text_notes t " +
            "INNER JOIN journal_content_links l ON l.content_id = t.uid " +
            "WHERE l.journal_id = :journalId AND l.deletedAt IS NULL AND t.deletedAt IS NULL " +
            "ORDER BY t.created DESC LIMIT :limit",
    )
    suspend fun latestJournalEntries(
        journalId: String,
        limit: Int,
    ): List<JournalEntryRow>

    /** When each entry at a place was written, as epoch milliseconds, whatever kind of note it is. */
    @Query(
        "SELECT created FROM text_notes WHERE place_id = :placeId AND deletedAt IS NULL " +
            "UNION ALL SELECT created FROM audio_notes WHERE place_id = :placeId AND deletedAt IS NULL " +
            "UNION ALL SELECT created FROM image_notes WHERE place_id = :placeId AND deletedAt IS NULL " +
            "UNION ALL SELECT created FROM video_notes WHERE place_id = :placeId AND deletedAt IS NULL",
    )
    suspend fun placeEntryTimes(placeId: String): List<Long>

    /** The newest [limit] text notes at a place, newest first. */
    @Query(
        "SELECT uid AS uid, content AS content, created AS created FROM text_notes " +
            "WHERE place_id = :placeId AND deletedAt IS NULL ORDER BY created DESC LIMIT :limit",
    )
    suspend fun latestPlaceEntries(
        placeId: String,
        limit: Int,
    ): List<JournalEntryRow>

    /** When each entry linked to a person was written, as epoch milliseconds, whatever kind of note it is. */
    @Query(
        "SELECT n.created FROM text_notes n INNER JOIN person_links l ON l.target_id = n.uid " +
            "WHERE l.person_id = :personId AND l.target_type = 'ENTRY' AND l.status = 'ACTIVE' AND n.deletedAt IS NULL " +
            "UNION ALL SELECT n.created FROM audio_notes n INNER JOIN person_links l ON l.target_id = n.uid " +
            "WHERE l.person_id = :personId AND l.target_type = 'ENTRY' AND l.status = 'ACTIVE' AND n.deletedAt IS NULL " +
            "UNION ALL SELECT n.created FROM image_notes n INNER JOIN person_links l ON l.target_id = n.uid " +
            "WHERE l.person_id = :personId AND l.target_type = 'ENTRY' AND l.status = 'ACTIVE' AND n.deletedAt IS NULL " +
            "UNION ALL SELECT n.created FROM video_notes n INNER JOIN person_links l ON l.target_id = n.uid " +
            "WHERE l.person_id = :personId AND l.target_type = 'ENTRY' AND l.status = 'ACTIVE' AND n.deletedAt IS NULL",
    )
    suspend fun personEntryTimes(personId: String): List<Long>

    /** The newest [limit] text notes linked to a person, newest first. */
    @Query(
        "SELECT n.uid AS uid, n.content AS content, n.created AS created FROM text_notes n " +
            "INNER JOIN person_links l ON l.target_id = n.uid " +
            "WHERE l.person_id = :personId AND l.target_type = 'ENTRY' AND l.status = 'ACTIVE' AND n.deletedAt IS NULL " +
            "ORDER BY n.created DESC LIMIT :limit",
    )
    suspend fun latestPersonEntries(
        personId: String,
        limit: Int,
    ): List<JournalEntryRow>

    /** The date range of each rewind, with the first image in it as a cover. */
    @Query(
        "SELECT r.uid AS uid, r.startDate AS startDate, r.endDate AS endDate, " +
            "(SELECT i.uri FROM rewind_image_content i WHERE i.rewindId = r.uid ORDER BY i.timestamp LIMIT 1) AS coverUri " +
            "FROM rewinds r WHERE r.uid IN (:ids)",
    )
    suspend fun rewindDetails(ids: List<String>): List<RewindDetailRow>

    /** The art of each sticker. */
    @Query("SELECT id AS id, image_uri AS imageUri FROM stickers WHERE id IN (:ids)")
    suspend fun stickerDetails(ids: List<String>): List<StickerDetailRow>

    /** The edit time of each postcard, with the photo it was made from as a cover. */
    @Query(
        "SELECT p.id AS id, p.modified_at AS modifiedAt, " +
            "(SELECT i.contentUri FROM image_notes i WHERE i.uid = p.source_moment_ref AND i.deletedAt IS NULL) AS coverUri " +
            "FROM postcards p WHERE p.id IN (:ids)",
    )
    suspend fun postcardDetails(ids: List<String>): List<PostcardDetailRow>

    /** The name of each place. */
    @Query("SELECT id AS id, name AS name FROM places WHERE id IN (:ids) AND deleted_at IS NULL")
    suspend fun placeNames(ids: List<String>): List<PlaceNameRow>

    /** The coordinates of each place. */
    @Query("SELECT id AS id, latitude AS latitude, longitude AS longitude FROM places WHERE id IN (:ids) AND deleted_at IS NULL")
    suspend fun placeDetails(ids: List<String>): List<PlaceDetailRow>
}

/** The media file of a note. */
data class MediaDetailRow(
    val uid: String,
    val contentUri: String,
)

/** The media file and length of a video note, in milliseconds. */
data class VideoDetailRow(
    val uid: String,
    val contentUri: String,
    val durationMs: Long?,
)

/** The length of an audio note, in milliseconds. */
data class AudioDetailRow(
    val uid: String,
    val durationMs: Long,
)

/** The cover and entry count of a journal. */
data class JournalDetailRow(
    val id: String,
    val coverUri: String?,
    val entryCount: Int,
)

/** A journal's title, description, creation time as epoch milliseconds, and cover if it has one. */
data class JournalPreviewRow(
    val title: String,
    val description: String,
    val created: Long,
    val coverUri: String?,
)

/** A text note in a journal and when it was written, as epoch milliseconds. */
data class JournalEntryRow(
    val uid: String,
    val content: String,
    val created: Long,
)

/** The range of a rewind as epoch milliseconds, and its cover image if it has one. */
data class RewindDetailRow(
    val uid: String,
    val startDate: Long,
    val endDate: Long,
    val coverUri: String?,
)

/** The art of a sticker. */
data class StickerDetailRow(
    val id: String,
    val imageUri: String,
)

/** The last edit of a postcard as epoch milliseconds, and its cover photo if it has one. */
data class PostcardDetailRow(
    val id: String,
    val modifiedAt: Long,
    val coverUri: String?,
)

/** The coordinates of a place. */
data class PlaceDetailRow(
    val id: String,
    val latitude: Double,
    val longitude: Double,
)

/** The name of a place. */
data class PlaceNameRow(
    val id: String,
    val name: String,
)
