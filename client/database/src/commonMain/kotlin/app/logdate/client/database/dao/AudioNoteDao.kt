package app.logdate.client.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.logdate.client.database.entities.AudioNoteEntity
import app.logdate.client.database.entities.NoteContentUri
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Dao
interface AudioNoteDao {
    /**
     * Retrieves an observable [AudioNoteEntity] by the given ID.
     */
    @Query("SELECT * FROM audio_notes WHERE uid = :uid")
    fun getNote(uid: Uuid): Flow<AudioNoteEntity>

    /**
     * Fetches a non-observable [AudioNoteEntity] by its ID.
     */
    @Query("SELECT * FROM audio_notes WHERE uid = :uid")
    suspend fun getNoteOneOff(uid: Uuid): AudioNoteEntity

    @Query("SELECT * FROM audio_notes WHERE uid = :uid")
    suspend fun findNoteOneOff(uid: Uuid): AudioNoteEntity?

    @Query("SELECT * FROM audio_notes WHERE uid IN (:uids)")
    suspend fun findNotesByIds(uids: List<Uuid>): List<AudioNoteEntity>

    @Query("SELECT * FROM audio_notes WHERE uid = :uid")
    fun observeNoteById(uid: Uuid): Flow<AudioNoteEntity?>

    @Query("SELECT * FROM audio_notes WHERE uid IN (:uids) ORDER BY created DESC")
    fun observeNotesByIds(uids: List<Uuid>): Flow<List<AudioNoteEntity>>

    /**
     * Returns a flow of all [AudioNoteEntity]s.
     */
    @Query("SELECT * FROM audio_notes ORDER BY created DESC")
    fun getAllNotes(): Flow<List<AudioNoteEntity>>

    /**
     * Returns the creation time, in epoch milliseconds, of every note in this table.
     *
     * Reads only the `created` column so callers that need when notes were written, such as
     * streak calculation, do not load whole notes.
     */
    @Query("SELECT created FROM audio_notes")
    fun observeCreatedTimestamps(): Flow<List<Long>>

    /**
     * Returns all audio notes as a list (for quota calculation).
     */
    @Query("SELECT * FROM audio_notes")
    suspend fun getAll(): List<AudioNoteEntity>

    /**
     * Fast query for recent notes to enable immediate timeline display.
     */
    @Query("SELECT * FROM audio_notes ORDER BY created DESC LIMIT :limit")
    fun getRecentNotes(limit: Int = 20): Flow<List<AudioNoteEntity>>

    @Query("SELECT * FROM audio_notes WHERE created < :beforeTimestamp ORDER BY created DESC LIMIT :limit")
    suspend fun getRecentNotesBefore(
        beforeTimestamp: Long,
        limit: Int,
    ): List<AudioNoteEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM audio_notes WHERE created < :beforeTimestamp)")
    suspend fun hasNotesBefore(beforeTimestamp: Long): Boolean

    /**
     * Returns notes within a specific date range.
     */
    @Query("SELECT * FROM audio_notes WHERE created BETWEEN :startTimestamp AND :endTimestamp ORDER BY created DESC")
    fun getNotesInRange(
        startTimestamp: Long,
        endTimestamp: Long,
    ): Flow<List<AudioNoteEntity>>

    /**
     * Inserts a [note] into the DB if it doesn't exist, and ignores it if it does.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addNote(note: AudioNoteEntity)

    @Update
    suspend fun updateForRestore(note: AudioNoteEntity): Int

    /**
     * Returns the subset of [contentUris] that at least one audio note still references.
     *
     * Indexed lookup used to check whether a handful of candidate paths are still owned
     * by a note, without loading every audio note.
     */
    @Query("SELECT DISTINCT contentUri FROM audio_notes WHERE contentUri IN (:contentUris)")
    suspend fun findReferencedContentUris(contentUris: List<String>): List<String>

    /**
     * Removes the given note from the DB.
     */
    @Query("DELETE FROM audio_notes WHERE uid = :noteId")
    suspend fun removeNote(noteId: Uuid)

    /**
     * Removes the given notes from the DB.
     */
    @Query("DELETE FROM audio_notes WHERE uid IN (:noteIds)")
    suspend fun removeNote(noteIds: List<Uuid>)

    @Query("UPDATE audio_notes SET syncVersion = :syncVersion, lastSynced = :lastSynced WHERE uid = :noteId")
    suspend fun updateSyncMetadata(
        noteId: Uuid,
        syncVersion: Long,
        lastSynced: Instant,
    )

    /**
     * Notes whose media reference is still a local file path or `file:` URI rather than a
     * `logdate-media://` reference, so a migration can rewrite them.
     */
    @Query("SELECT uid, contentUri FROM audio_notes WHERE contentUri LIKE 'file:%' OR contentUri LIKE '/%' OR contentUri LIKE '_:%'")
    suspend fun localFileContentUris(): List<NoteContentUri>

    @Query("UPDATE audio_notes SET contentUri = :contentUri WHERE uid = :noteId")
    suspend fun updateContentUri(
        noteId: Uuid,
        contentUri: String,
    )

    /** Fills metadata without changing content, capture/edit times, or a newer known duration. */
    @Query(
        "UPDATE audio_notes SET durationMs = :durationMs WHERE uid = :noteId AND contentUri = :contentUri " +
            "AND durationMs <= 0 AND deletedAt IS NULL AND :durationMs > 0",
    )
    suspend fun updateDurationIfUnknown(
        noteId: Uuid,
        contentUri: String,
        durationMs: Long,
    ): Int

    /** Rewrites [previous] to [contentUri] only while the note still stores [previous]. */
    @Query("UPDATE audio_notes SET contentUri = :contentUri WHERE uid = :noteId AND contentUri = :previous")
    suspend fun updateContentUriIfUnchanged(
        noteId: Uuid,
        previous: String,
        contentUri: String,
    )

    @Query("SELECT COUNT(*) FROM audio_notes")
    suspend fun countNotes(): Int
}
