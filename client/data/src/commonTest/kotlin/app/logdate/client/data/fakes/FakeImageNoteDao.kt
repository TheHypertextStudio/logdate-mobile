package app.logdate.client.data.fakes

import app.logdate.client.database.dao.ImageNoteDao
import app.logdate.client.database.entities.ImageNoteEntity
import app.logdate.client.database.entities.NoteContentUri
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlin.uuid.Uuid

/**
 * Fake implementation of [ImageNoteDao] for testing.
 */
class FakeImageNoteDao : ImageNoteDao {
    private val notes = mutableMapOf<Uuid, ImageNoteEntity>()
    private val notesFlow = MutableStateFlow<List<ImageNoteEntity>>(emptyList())

    override fun getNote(uid: Uuid): Flow<ImageNoteEntity> =
        notesFlow.map { notes ->
            notes.find { it.uid == uid } ?: throw NoSuchElementException("Image note with ID $uid not found")
        }

    override suspend fun getNoteOneOff(uid: Uuid): ImageNoteEntity =
        notes[uid] ?: throw NoSuchElementException("Image note with ID $uid not found")

    override suspend fun findNoteOneOff(uid: Uuid): ImageNoteEntity? = notes[uid]

    override suspend fun countNotes(): Int = notes.size

    override suspend fun findNotesByIds(uids: List<Uuid>): List<ImageNoteEntity> = uids.mapNotNull { notes[it] }

    override fun observeNoteById(uid: Uuid): Flow<ImageNoteEntity?> = notesFlow.map { notes -> notes.find { it.uid == uid } }

    override fun observeNotesByIds(uids: List<Uuid>): Flow<List<ImageNoteEntity>> =
        notesFlow.map { notes -> notes.filter { it.uid in uids }.sortedByDescending { it.created } }

    override fun getAllNotes(): Flow<List<ImageNoteEntity>> = notesFlow

    override fun observeCreatedTimestamps(): Flow<List<Long>> = notesFlow.map { notes -> notes.map { it.created.toEpochMilliseconds() } }

    override suspend fun getAll(): List<ImageNoteEntity> = notes.values.toList()

    override fun getRecentNotes(limit: Int): Flow<List<ImageNoteEntity>> =
        notesFlow.map { notes ->
            notes.sortedByDescending { it.created }.take(limit)
        }

    override suspend fun getRecentNotesBefore(
        beforeTimestamp: Long,
        limit: Int,
    ): List<ImageNoteEntity> =
        notes.values
            .filter { it.created.toEpochMilliseconds() < beforeTimestamp }
            .sortedByDescending { it.created }
            .take(limit)

    override suspend fun hasNotesBefore(beforeTimestamp: Long): Boolean =
        notes.values.any { it.created.toEpochMilliseconds() < beforeTimestamp }

    override fun getNotesPage(
        limit: Int,
        offset: Int,
    ): Flow<List<ImageNoteEntity>> =
        notesFlow.map { notes ->
            notes.sortedByDescending { it.created }.drop(offset).take(limit)
        }

    override fun getNotesInRange(
        startTimestamp: Long,
        endTimestamp: Long,
    ): Flow<List<ImageNoteEntity>> =
        notesFlow.map { notes ->
            notes
                .filter {
                    val createdMillis = it.created.toEpochMilliseconds()
                    createdMillis in startTimestamp..endTimestamp
                }.sortedByDescending { it.created }
        }

    override suspend fun addNote(note: ImageNoteEntity) {
        notes[note.uid] = note
        updateFlow()
    }

    override suspend fun localFileContentUris(): List<NoteContentUri> =
        notes.values
            .filter { it.contentUri.startsWith("file:") || it.contentUri.startsWith("/") || it.contentUri.getOrNull(1) == ':' }
            .map { NoteContentUri(it.uid, it.contentUri) }

    override suspend fun findReferencedContentUris(contentUris: List<String>): List<String> {
        val candidates = contentUris.toSet()
        return notes.values
            .map { it.contentUri }
            .filter { it in candidates }
            .distinct()
    }

    override suspend fun updateForRestore(note: ImageNoteEntity): Int {
        if (note.uid !in notes) return 0
        notes[note.uid] = note
        updateFlow()
        return 1
    }

    override suspend fun removeNote(noteId: Uuid) {
        notes.remove(noteId)
        updateFlow()
    }

    override suspend fun removeNote(noteIds: List<Uuid>) {
        noteIds.forEach { notes.remove(it) }
        updateFlow()
    }

    override suspend fun updateSyncMetadata(
        noteId: Uuid,
        syncVersion: Long,
        lastSynced: kotlin.time.Instant,
    ) {
        val existing = notes[noteId] ?: return
        notes[noteId] = existing.copy(syncVersion = syncVersion, lastSynced = lastSynced)
        updateFlow()
    }

    override suspend fun updateContentUri(
        noteId: Uuid,
        contentUri: String,
    ) {
        val existing = notes[noteId] ?: return
        notes[noteId] = existing.copy(contentUri = contentUri)
        updateFlow()
    }

    override suspend fun updateContentUriIfUnchanged(
        noteId: Uuid,
        previous: String,
        contentUri: String,
    ) {
        val existing = notes[noteId]?.takeIf { it.contentUri == previous } ?: return
        notes[noteId] = existing.copy(contentUri = contentUri)
        updateFlow()
    }

    /**
     * Clears all notes in the fake database.
     * This method is specific to the fake implementation for testing.
     */
    fun clear() {
        notes.clear()
        updateFlow()
    }

    private fun updateFlow() {
        notesFlow.value = notes.values.toList()
    }
}
