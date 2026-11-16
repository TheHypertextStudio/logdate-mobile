package app.logdate.client.data.fakes

import app.logdate.client.database.dao.TextNoteDao
import app.logdate.client.database.entities.TextNoteEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlin.uuid.Uuid

/**
 * Fake implementation of [TextNoteDao] for testing.
 */
class FakeTextNoteDao : TextNoteDao {
    private val notes = mutableMapOf<Uuid, TextNoteEntity>()
    private val notesFlow = MutableStateFlow<List<TextNoteEntity>>(emptyList())

    override fun getNote(uid: Uuid): Flow<TextNoteEntity> =
        notesFlow.map { notes ->
            notes.find { it.uid == uid } ?: throw NoSuchElementException("Text note with ID $uid not found")
        }

    override suspend fun getNoteOneOff(uid: Uuid): TextNoteEntity =
        notes[uid] ?: throw NoSuchElementException("Text note with ID $uid not found")

    override suspend fun findNoteOneOff(uid: Uuid): TextNoteEntity? = notes[uid]

    override suspend fun countNotes(): Int = notes.size

    override suspend fun findNotesByIds(uids: List<Uuid>): List<TextNoteEntity> = uids.mapNotNull { notes[it] }

    override fun observeNoteById(uid: Uuid): Flow<TextNoteEntity?> = notesFlow.map { notes -> notes.find { it.uid == uid } }

    override fun observeNotesByIds(uids: List<Uuid>): Flow<List<TextNoteEntity>> =
        notesFlow.map { notes -> notes.filter { it.uid in uids }.sortedByDescending { it.created } }

    /** How many times a caller collected the whole table. */
    var wholeTableReads = 0
        private set

    /** The row limits callers asked [getRecentNotes] for. */
    val recentLimits = mutableListOf<Int>()

    override fun getAllNotes(): Flow<List<TextNoteEntity>> = notesFlow.onStart { wholeTableReads++ }

    override fun observeCreatedTimestamps(): Flow<List<Long>> = notesFlow.map { notes -> notes.map { it.created.toEpochMilliseconds() } }

    override suspend fun getAll(): List<TextNoteEntity> = notes.values.toList()

    override fun getRecentNotes(limit: Int): Flow<List<TextNoteEntity>> {
        recentLimits += limit
        return notesFlow.map { notes ->
            notes.sortedByDescending { it.created }.take(limit)
        }
    }

    override suspend fun getRecentNotesBefore(
        beforeTimestamp: Long,
        limit: Int,
    ): List<TextNoteEntity> =
        notes.values
            .filter { it.created.toEpochMilliseconds() < beforeTimestamp }
            .sortedByDescending { it.created }
            .take(limit)

    override suspend fun hasNotesBefore(beforeTimestamp: Long): Boolean =
        notes.values.any { it.created.toEpochMilliseconds() < beforeTimestamp }

    override fun getNotesPage(
        limit: Int,
        offset: Int,
    ): Flow<List<TextNoteEntity>> =
        notesFlow.map { notes ->
            notes.sortedByDescending { it.created }.drop(offset).take(limit)
        }

    override fun getNotesInRange(
        startTimestamp: Long,
        endTimestamp: Long,
    ): Flow<List<TextNoteEntity>> =
        notesFlow.map { notes ->
            notes
                .filter {
                    val createdMillis = it.created.toEpochMilliseconds()
                    createdMillis in startTimestamp..endTimestamp
                }.sortedByDescending { it.created }
        }

    override suspend fun addNote(note: TextNoteEntity) {
        notes[note.uid] = note
        updateFlow()
    }

    override suspend fun updateForRestore(note: TextNoteEntity): Int {
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
