package app.logdate.client.data.fakes

import app.logdate.client.database.dao.VideoNoteDao
import app.logdate.client.database.entities.NoteContentUri
import app.logdate.client.database.entities.VideoNoteEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlin.uuid.Uuid

/**
 * Fake implementation of [VideoNoteDao] for testing.
 */
class FakeVideoNoteDao : VideoNoteDao {
    private val notes = mutableMapOf<Uuid, VideoNoteEntity>()
    private val notesFlow = MutableStateFlow<List<VideoNoteEntity>>(emptyList())

    override fun getNote(uid: Uuid): Flow<VideoNoteEntity> =
        notesFlow.map { notes ->
            notes.find { it.uid == uid } ?: throw NoSuchElementException("Video note with ID $uid not found")
        }

    override suspend fun getNoteOneOff(uid: Uuid): VideoNoteEntity =
        notes[uid] ?: throw NoSuchElementException("Video note with ID $uid not found")

    override suspend fun findNoteOneOff(uid: Uuid): VideoNoteEntity? = notes[uid]

    override suspend fun countNotes(): Int = notes.size

    override suspend fun findNotesByIds(uids: List<Uuid>): List<VideoNoteEntity> = uids.mapNotNull { notes[it] }

    override fun observeNoteById(uid: Uuid): Flow<VideoNoteEntity?> = notesFlow.map { notes -> notes.find { it.uid == uid } }

    override fun observeNotesByIds(uids: List<Uuid>): Flow<List<VideoNoteEntity>> =
        notesFlow.map { notes -> notes.filter { it.uid in uids }.sortedByDescending { it.created } }

    override fun getAllNotes(): Flow<List<VideoNoteEntity>> = notesFlow

    override fun observeCreatedTimestamps(): Flow<List<Long>> = notesFlow.map { notes -> notes.map { it.created.toEpochMilliseconds() } }

    override suspend fun getAll(): List<VideoNoteEntity> = notes.values.toList()

    override fun getRecentNotes(limit: Int): Flow<List<VideoNoteEntity>> =
        notesFlow.map { notes ->
            notes.sortedByDescending { it.created }.take(limit)
        }

    override suspend fun getRecentNotesBefore(
        beforeTimestamp: Long,
        limit: Int,
    ): List<VideoNoteEntity> =
        notes.values
            .filter { it.created.toEpochMilliseconds() < beforeTimestamp }
            .sortedByDescending { it.created }
            .take(limit)

    override suspend fun hasNotesBefore(beforeTimestamp: Long): Boolean =
        notes.values.any { it.created.toEpochMilliseconds() < beforeTimestamp }

    override fun getNotesInRange(
        startTimestamp: Long,
        endTimestamp: Long,
    ): Flow<List<VideoNoteEntity>> =
        notesFlow.map { notes ->
            notes
                .filter {
                    val createdMillis = it.created.toEpochMilliseconds()
                    createdMillis in startTimestamp..endTimestamp
                }.sortedByDescending { it.created }
        }

    override suspend fun addNote(note: VideoNoteEntity) {
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

    override suspend fun updateForRestore(note: VideoNoteEntity): Int {
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

    fun clear() {
        notes.clear()
        updateFlow()
    }

    private fun updateFlow() {
        notesFlow.value = notes.values.toList()
    }
}
