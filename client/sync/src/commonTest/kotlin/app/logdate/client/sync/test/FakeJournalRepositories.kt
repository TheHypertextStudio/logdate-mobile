package app.logdate.client.sync.test

import app.logdate.client.repository.journals.JournalContentRepository
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.journals.JournalRepository
import app.logdate.client.repository.journals.SyncableDraftRepository
import app.logdate.client.repository.journals.SyncableJournalNotesRepository
import app.logdate.shared.model.EditorDraft
import app.logdate.shared.model.Journal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

class FakeJournalNotesRepository : SyncableJournalNotesRepository {
    private val notes = mutableListOf<JournalNote>()
    private val notesStateFlow = MutableStateFlow<List<JournalNote>>(emptyList())
    private val associations = mutableListOf<Pair<Uuid, Uuid>>()

    override val allNotesObserved: Flow<List<JournalNote>> = notesStateFlow.asStateFlow()

    override fun observeNotesInJournal(journalId: Uuid): Flow<List<JournalNote>> = notesStateFlow.asStateFlow()

    override fun observeNotesInRange(
        start: Instant,
        end: Instant,
    ): Flow<List<JournalNote>> = notesStateFlow.asStateFlow()

    override fun observeNotesPage(
        pageSize: Int,
        offset: Int,
    ): Flow<List<JournalNote>> = notesStateFlow.asStateFlow()

    override fun observeNotesStream(pageSize: Int): Flow<List<JournalNote>> = notesStateFlow.asStateFlow()

    override fun observeRecentNotes(limit: Int): Flow<List<JournalNote>> = notesStateFlow.asStateFlow()

    override suspend fun create(note: JournalNote): Uuid {
        notes.add(note)
        notesStateFlow.value = notes.toList()
        return note.uid
    }

    override suspend fun remove(note: JournalNote) {
        notes.removeAll { it.uid == note.uid }
        notesStateFlow.value = notes.toList()
    }

    override suspend fun removeById(noteId: Uuid) {
        notes.removeAll { it.uid == noteId }
        notesStateFlow.value = notes.toList()
    }

    override suspend fun create(
        note: JournalNote,
        journalId: Uuid,
    ) {
        create(note)
    }

    override suspend fun removeFromJournal(
        noteId: Uuid,
        journalId: Uuid,
    ) {
        // No-op for testing
    }

    override suspend fun getNoteById(noteId: Uuid): JournalNote? = notes.find { it.uid == noteId }

    override suspend fun getAllJournalNoteLinks(): List<Pair<Uuid, Uuid>> = associations.toList()

    fun addTestAssociation(
        journalId: Uuid,
        contentId: Uuid,
    ) {
        associations += journalId to contentId
    }

    override suspend fun createFromSync(note: JournalNote) {
        create(note)
    }

    override suspend fun deleteFromSync(noteId: Uuid) {
        removeById(noteId)
    }

    override suspend fun updateSyncMetadata(
        note: JournalNote,
        syncVersion: Long,
        syncedAt: Instant,
    ) {
        // For testing, just update the note
        val index = notes.indexOfFirst { it.uid == note.uid }
        if (index >= 0) {
            notes[index] = note
            notesStateFlow.value = notes.toList()
        }
    }

    override suspend fun updateMediaRef(
        noteId: Uuid,
        mediaRef: String,
    ) {
        val index = notes.indexOfFirst { it.uid == noteId }
        if (index >= 0) {
            val current = notes[index]
            val updated =
                when (current) {
                    is JournalNote.Image -> current.copy(mediaRef = mediaRef)
                    is JournalNote.Video -> current.copy(mediaRef = mediaRef)
                    is JournalNote.Audio -> current.copy(mediaRef = mediaRef)
                    else -> current
                }
            notes[index] = updated
            notesStateFlow.value = notes.toList()
        }
    }

    fun addTestNote(content: String): JournalNote.Text {
        val note =
            JournalNote.Text(
                uid = Uuid.random(),
                creationTimestamp = Clock.System.now(),
                lastUpdated = Clock.System.now(),
                content = content,
            )
        notes.add(note)
        notesStateFlow.value = notes.toList()
        return note
    }

    fun clear() {
        notes.clear()
        notesStateFlow.value = emptyList()
    }
}

class FailingJournalNotesRepository(
    private val delegate: JournalNotesRepository = FakeJournalNotesRepository(),
) : JournalNotesRepository by delegate {
    override suspend fun create(note: JournalNote): Uuid = throw IllegalStateException("Simulated note create failure")
}

/**
 * Fake JournalRepository for testing.
 */
class FakeJournalRepository :
    JournalRepository,
    SyncableDraftRepository {
    private val journals = mutableListOf<Journal>()
    private val journalsStateFlow = MutableStateFlow<List<Journal>>(emptyList())
    private val drafts = mutableMapOf<Uuid, EditorDraft>()

    override val allJournalsObserved: Flow<List<Journal>> = journalsStateFlow.asStateFlow()

    override fun observeJournalById(id: Uuid): Flow<Journal> = throw NotImplementedError("Not implemented in fake")

    override suspend fun getJournalById(id: Uuid): Journal? = journals.find { it.id == id }

    override suspend fun create(journal: Journal): Uuid {
        journals.add(journal)
        journalsStateFlow.value = journals.toList()
        return journal.id
    }

    override suspend fun update(journal: Journal) {
        val index = journals.indexOfFirst { it.id == journal.id }
        if (index >= 0) {
            journals[index] = journal
            journalsStateFlow.value = journals.toList()
        }
    }

    override suspend fun delete(journalId: Uuid) {
        journals.removeAll { it.id == journalId }
        journalsStateFlow.value = journals.toList()
    }

    override suspend fun saveDraft(draft: EditorDraft) {
        drafts[draft.id] = draft
    }

    override suspend fun getLatestDraft(): EditorDraft? = drafts.values.maxByOrNull { it.lastModifiedAt }

    override suspend fun getAllDrafts(): List<EditorDraft> = drafts.values.toList()

    override suspend fun getDraft(id: Uuid): EditorDraft? = drafts[id]

    override suspend fun deleteDraft(id: Uuid) {
        drafts.remove(id)
    }

    override suspend fun saveDraftFromSync(draft: EditorDraft) {
        drafts[draft.id] = draft
    }

    override suspend fun deleteDraftFromSync(id: Uuid) {
        drafts.remove(id)
    }

    fun clear() {
        journals.clear()
        journalsStateFlow.value = emptyList()
        drafts.clear()
    }
}

/**
 * Fake JournalContentRepository for testing.
 */
class FakeJournalContentRepository : JournalContentRepository {
    override fun observeContentForJournal(journalId: Uuid): Flow<List<JournalNote>> = MutableStateFlow(emptyList())

    override fun observeJournalsForContent(contentId: Uuid): Flow<List<Journal>> = MutableStateFlow(emptyList())

    override suspend fun addContentToJournal(
        contentId: Uuid,
        journalId: Uuid,
    ) {}

    override suspend fun removeContentFromJournal(
        contentId: Uuid,
        journalId: Uuid,
    ) {}

    override suspend fun addContentToJournals(
        contentId: Uuid,
        journalIds: List<Uuid>,
    ) {}

    override suspend fun removeContentFromAllJournals(contentId: Uuid) {}

    override fun observeJournalsForContents(contentIds: Set<Uuid>): Flow<Map<Uuid, List<Journal>>> = flowOf(emptyMap())
}

class TrackingJournalContentRepository : JournalContentRepository {
    private data class AssociationKey(
        val contentId: Uuid,
        val journalId: Uuid,
    )

    private val associations = mutableSetOf<AssociationKey>()

    override fun observeContentForJournal(journalId: Uuid): Flow<List<JournalNote>> = MutableStateFlow(emptyList())

    override fun observeJournalsForContent(contentId: Uuid): Flow<List<Journal>> = MutableStateFlow(emptyList())

    override suspend fun addContentToJournal(
        contentId: Uuid,
        journalId: Uuid,
    ) {
        associations.add(AssociationKey(contentId, journalId))
    }

    override suspend fun removeContentFromJournal(
        contentId: Uuid,
        journalId: Uuid,
    ) {
        associations.remove(AssociationKey(contentId, journalId))
    }

    override suspend fun addContentToJournals(
        contentId: Uuid,
        journalIds: List<Uuid>,
    ) {
        journalIds.forEach { journalId ->
            addContentToJournal(contentId, journalId)
        }
    }

    override suspend fun removeContentFromAllJournals(contentId: Uuid) {
        associations.removeAll { it.contentId == contentId }
    }

    override fun observeJournalsForContents(contentIds: Set<Uuid>): Flow<Map<Uuid, List<Journal>>> = flowOf(emptyMap())

    fun hasAssociation(
        contentId: Uuid,
        journalId: Uuid,
    ): Boolean = associations.contains(AssociationKey(contentId, journalId))
}

/*
 * SyncManager implementation that tracks all method calls for testing.
 */
