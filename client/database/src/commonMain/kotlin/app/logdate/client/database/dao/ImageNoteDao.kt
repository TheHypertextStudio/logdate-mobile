package app.logdate.client.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.logdate.client.database.entities.ImageNoteEntity
import app.logdate.client.database.entities.NoteContentUri
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

@Dao
interface ImageNoteDao {
    /**
     * Retrieves an observable [ImageNoteEntity] by the given ID.
     */
    @Query("SELECT * FROM image_notes WHERE uid = :uid")
    fun getNote(uid: Uuid): Flow<ImageNoteEntity>

    /**
     * Fetches a non-observable [ImageNoteEntity] by its ID.
     */
    @Query("SELECT * FROM image_notes WHERE uid = :uid")
    suspend fun getNoteOneOff(uid: Uuid): ImageNoteEntity

    @Query("SELECT * FROM image_notes WHERE uid = :uid")
    suspend fun findNoteOneOff(uid: Uuid): ImageNoteEntity?

    @Query("SELECT * FROM image_notes WHERE uid IN (:uids)")
    suspend fun findNotesByIds(uids: List<Uuid>): List<ImageNoteEntity>

    @Query("SELECT * FROM image_notes WHERE uid = :uid")
    fun observeNoteById(uid: Uuid): Flow<ImageNoteEntity?>

    @Query("SELECT * FROM image_notes WHERE uid IN (:uids) ORDER BY created DESC")
    fun observeNotesByIds(uids: List<Uuid>): Flow<List<ImageNoteEntity>>

    /**
     * Returns a flow of all [ImageNoteEntity]s.
     */
    @Query("SELECT * FROM image_notes ORDER BY created DESC")
    fun getAllNotes(): Flow<List<ImageNoteEntity>>

    /**
     * Returns the creation time, in epoch milliseconds, of every note in this table.
     *
     * Reads only the `created` column so callers that need when notes were written, such as
     * streak calculation, do not load whole notes.
     */
    @Query("SELECT created FROM image_notes")
    fun observeCreatedTimestamps(): Flow<List<Long>>

    /**
     * Returns all image notes as a list (for quota calculation).
     */
    @Query("SELECT * FROM image_notes")
    suspend fun getAll(): List<ImageNoteEntity>

    /**
     * Fast query for recent notes to enable immediate timeline display.
     */
    @Query("SELECT * FROM image_notes ORDER BY created DESC LIMIT :limit")
    fun getRecentNotes(limit: Int = 20): Flow<List<ImageNoteEntity>>

    @Query("SELECT * FROM image_notes WHERE created < :beforeTimestamp ORDER BY created DESC LIMIT :limit")
    suspend fun getRecentNotesBefore(
        beforeTimestamp: Long,
        limit: Int,
    ): List<ImageNoteEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM image_notes WHERE created < :beforeTimestamp)")
    suspend fun hasNotesBefore(beforeTimestamp: Long): Boolean

    /**
     * Returns a paginated flow of [ImageNoteEntity]s ordered by creation date.
     */
    @Query("SELECT * FROM image_notes ORDER BY created DESC LIMIT :limit OFFSET :offset")
    fun getNotesPage(
        limit: Int,
        offset: Int,
    ): Flow<List<ImageNoteEntity>>

    /**
     * Returns notes within a specific date range.
     */
    @Query("SELECT * FROM image_notes WHERE created BETWEEN :startTimestamp AND :endTimestamp ORDER BY created DESC")
    fun getNotesInRange(
        startTimestamp: Long,
        endTimestamp: Long,
    ): Flow<List<ImageNoteEntity>>

    /**
     * Inserts a [note] into the DB if it doesn't exist, and ignores it if it does.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addNote(note: ImageNoteEntity)

    @Update
    suspend fun updateForRestore(note: ImageNoteEntity): Int

    /**
     * Returns the subset of [contentUris] that at least one image note still references.
     *
     * Indexed lookup used to check whether a handful of candidate paths are still owned
     * by a note, without loading every image note.
     */
    @Query("SELECT DISTINCT contentUri FROM image_notes WHERE contentUri IN (:contentUris)")
    suspend fun findReferencedContentUris(contentUris: List<String>): List<String>

    /**
     * Removes the given note from the DB.
     */
    @Query("DELETE FROM image_notes WHERE uid = :noteId")
    suspend fun removeNote(noteId: Uuid)

    /**
     * Removes the given note from the DB.
     */
    @Query("DELETE FROM image_notes WHERE uid IN (:noteIds)")
    suspend fun removeNote(noteIds: List<Uuid>)

    @Query("UPDATE image_notes SET syncVersion = :syncVersion, lastSynced = :lastSynced WHERE uid = :noteId")
    suspend fun updateSyncMetadata(
        noteId: Uuid,
        syncVersion: Long,
        lastSynced: kotlin.time.Instant,
    )

    /**
     * Notes whose media reference is still a local file path or `file:` URI rather than a
     * `logdate-media://` reference, so a migration can rewrite them.
     */
    @Query("SELECT uid, contentUri FROM image_notes WHERE contentUri LIKE 'file:%' OR contentUri LIKE '/%' OR contentUri LIKE '_:%'")
    suspend fun localFileContentUris(): List<NoteContentUri>

    @Query("UPDATE image_notes SET contentUri = :contentUri WHERE uid = :noteId")
    suspend fun updateContentUri(
        noteId: Uuid,
        contentUri: String,
    )

    /** Rewrites [previous] to [contentUri] only while the note still stores [previous]. */
    @Query("UPDATE image_notes SET contentUri = :contentUri WHERE uid = :noteId AND contentUri = :previous")
    suspend fun updateContentUriIfUnchanged(
        noteId: Uuid,
        previous: String,
        contentUri: String,
    )

    @Query("SELECT COUNT(*) FROM image_notes")
    suspend fun countNotes(): Int
}
