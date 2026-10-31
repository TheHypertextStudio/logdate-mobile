package app.logdate.client.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.logdate.client.database.entities.NoteContentUri
import app.logdate.client.database.entities.VideoNoteEntity
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

@Dao
interface VideoNoteDao {
    /**
     * Retrieves an observable [VideoNoteEntity] by the given ID.
     */
    @Query("SELECT * FROM video_notes WHERE uid = :uid")
    fun getNote(uid: Uuid): Flow<VideoNoteEntity>

    /**
     * Fetches a non-observable [VideoNoteEntity] by its ID.
     */
    @Query("SELECT * FROM video_notes WHERE uid = :uid")
    suspend fun getNoteOneOff(uid: Uuid): VideoNoteEntity

    @Query("SELECT * FROM video_notes WHERE uid = :uid")
    suspend fun findNoteOneOff(uid: Uuid): VideoNoteEntity?

    @Query("SELECT * FROM video_notes WHERE uid IN (:uids)")
    suspend fun findNotesByIds(uids: List<Uuid>): List<VideoNoteEntity>

    @Query("SELECT * FROM video_notes WHERE uid = :uid")
    fun observeNoteById(uid: Uuid): Flow<VideoNoteEntity?>

    @Query("SELECT * FROM video_notes WHERE uid IN (:uids) ORDER BY created DESC")
    fun observeNotesByIds(uids: List<Uuid>): Flow<List<VideoNoteEntity>>

    /**
     * Returns a flow of all [VideoNoteEntity]s.
     */
    @Query("SELECT * FROM video_notes ORDER BY created DESC")
    fun getAllNotes(): Flow<List<VideoNoteEntity>>

    /**
     * Returns the creation time, in epoch milliseconds, of every note in this table.
     *
     * Reads only the `created` column so callers that need when notes were written, such as
     * streak calculation, do not load whole notes.
     */
    @Query("SELECT created FROM video_notes")
    fun observeCreatedTimestamps(): Flow<List<Long>>

    /**
     * Returns all video notes as a list (for quota calculation).
     */
    @Query("SELECT * FROM video_notes")
    suspend fun getAll(): List<VideoNoteEntity>

    /**
     * Fast query for recent notes to enable immediate timeline display.
     */
    @Query("SELECT * FROM video_notes ORDER BY created DESC LIMIT :limit")
    fun getRecentNotes(limit: Int = 20): Flow<List<VideoNoteEntity>>

    @Query("SELECT * FROM video_notes WHERE created < :beforeTimestamp ORDER BY created DESC LIMIT :limit")
    suspend fun getRecentNotesBefore(
        beforeTimestamp: Long,
        limit: Int,
    ): List<VideoNoteEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM video_notes WHERE created < :beforeTimestamp)")
    suspend fun hasNotesBefore(beforeTimestamp: Long): Boolean

    /**
     * Returns notes within a specific date range.
     */
    @Query("SELECT * FROM video_notes WHERE created BETWEEN :startTimestamp AND :endTimestamp ORDER BY created DESC")
    fun getNotesInRange(
        startTimestamp: Long,
        endTimestamp: Long,
    ): Flow<List<VideoNoteEntity>>

    /**
     * Inserts a [note] into the DB if it doesn't exist, and ignores it if it does.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addNote(note: VideoNoteEntity)

    @Update
    suspend fun updateForRestore(note: VideoNoteEntity): Int

    /**
     * Returns the subset of [contentUris] that at least one video note still references.
     *
     * Indexed lookup used to check whether a handful of candidate paths are still owned
     * by a note, without loading every video note.
     */
    @Query("SELECT DISTINCT contentUri FROM video_notes WHERE contentUri IN (:contentUris)")
    suspend fun findReferencedContentUris(contentUris: List<String>): List<String>

    /**
     * Removes the given note from the DB.
     */
    @Query("DELETE FROM video_notes WHERE uid = :noteId")
    suspend fun removeNote(noteId: Uuid)

    /**
     * Removes the given notes from the DB.
     */
    @Query("DELETE FROM video_notes WHERE uid IN (:noteIds)")
    suspend fun removeNote(noteIds: List<Uuid>)

    @Query("UPDATE video_notes SET syncVersion = :syncVersion, lastSynced = :lastSynced WHERE uid = :noteId")
    suspend fun updateSyncMetadata(
        noteId: Uuid,
        syncVersion: Long,
        lastSynced: kotlin.time.Instant,
    )

    /**
     * Notes whose media reference is still a local file path or `file:` URI rather than a
     * `logdate-media://` reference, so a migration can rewrite them.
     */
    @Query("SELECT uid, contentUri FROM video_notes WHERE contentUri LIKE 'file:%' OR contentUri LIKE '/%' OR contentUri LIKE '_:%'")
    suspend fun localFileContentUris(): List<NoteContentUri>

    @Query("UPDATE video_notes SET contentUri = :contentUri WHERE uid = :noteId")
    suspend fun updateContentUri(
        noteId: Uuid,
        contentUri: String,
    )

    /** Rewrites [previous] to [contentUri] only while the note still stores [previous]. */
    @Query("UPDATE video_notes SET contentUri = :contentUri WHERE uid = :noteId AND contentUri = :previous")
    suspend fun updateContentUriIfUnchanged(
        noteId: Uuid,
        previous: String,
        contentUri: String,
    )

    @Query("SELECT COUNT(*) FROM video_notes")
    suspend fun countNotes(): Int
}
