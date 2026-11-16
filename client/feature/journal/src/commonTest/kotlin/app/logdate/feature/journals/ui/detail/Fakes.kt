package app.logdate.feature.journals.ui.detail

import app.logdate.client.media.audio.AudioDurationResolver
import app.logdate.client.media.audio.AudioPlaybackManager
import app.logdate.client.media.audio.AudioPlaybackMetadata
import app.logdate.client.media.audio.AudioPlaybackStatus
import app.logdate.client.media.audio.AudioPlaybackStatusProvider
import app.logdate.client.repository.audio.AudioTag
import app.logdate.client.repository.audio.AudioTagRepository
import app.logdate.client.repository.journals.JournalContentRepository
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.journals.JournalRepository
import app.logdate.shared.model.EditorDraft
import app.logdate.shared.model.Journal
import app.logdate.ui.audio.WaveformStorage
import app.logdate.ui.audio.extraction.AmplitudeExtractor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlin.time.Instant
import kotlin.uuid.Uuid

class FakeJournalNotesRepository(
    initialNotes: List<JournalNote> = emptyList(),
) : JournalNotesRepository {
    private val notesFlow = MutableStateFlow(initialNotes)

    var lastRemovedId: Uuid? = null

    /** How many times a caller subscribed to every note instead of a bounded query. */
    var wholeTableSubscriptions = 0
        private set

    override val allNotesObserved: Flow<List<JournalNote>> = notesFlow.onStart { wholeTableSubscriptions++ }

    override fun observeNoteById(noteId: Uuid): Flow<JournalNote?> =
        notesFlow.map { notes -> notes.firstOrNull { it.uid == noteId } }.distinctUntilChanged()

    override fun observeNotesInJournal(journalId: Uuid): Flow<List<JournalNote>> = flowOf(emptyList())

    override fun observeNotesInRange(
        start: Instant,
        end: Instant,
    ): Flow<List<JournalNote>> = notesFlow

    override fun observeNotesPage(
        pageSize: Int,
        offset: Int,
    ): Flow<List<JournalNote>> =
        notesFlow.map { notes ->
            notes
                .sortedByDescending(JournalNote::creationTimestamp)
                .drop(offset)
                .take(pageSize)
        }

    override fun observeNotesStream(pageSize: Int): Flow<List<JournalNote>> = observeNotesPage(pageSize, offset = 0)

    override fun observeRecentNotes(limit: Int): Flow<List<JournalNote>> = notesFlow

    override suspend fun getNoteById(noteId: Uuid): JournalNote? = notesFlow.value.firstOrNull { it.uid == noteId }

    override suspend fun create(note: JournalNote): Uuid {
        notesFlow.value = notesFlow.value + note
        return note.uid
    }

    override suspend fun remove(note: JournalNote) {
        notesFlow.value = notesFlow.value.filterNot { it.uid == note.uid }
    }

    override suspend fun removeById(noteId: Uuid) {
        lastRemovedId = noteId
        notesFlow.value = notesFlow.value.filterNot { it.uid == noteId }
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
        // No-op for tests.
    }

    override suspend fun getAllJournalNoteLinks(): List<Pair<Uuid, Uuid>> = emptyList()

    fun setNotes(notes: List<JournalNote>) {
        notesFlow.value = notes
    }
}

class FakeDetailJournalRepository(
    initialJournals: List<Journal> = emptyList(),
) : JournalRepository {
    private val journalsFlow = MutableStateFlow(initialJournals)

    override val allJournalsObserved: Flow<List<Journal>> = journalsFlow

    override fun observeJournalById(id: Uuid): Flow<Journal> = flowOf(journalsFlow.value.first { it.id == id })

    override suspend fun getJournalById(id: Uuid): Journal? = journalsFlow.value.firstOrNull { it.id == id }

    override suspend fun create(journal: Journal): Uuid {
        journalsFlow.value = journalsFlow.value + journal
        return journal.id
    }

    override suspend fun update(journal: Journal) = Unit

    override suspend fun delete(journalId: Uuid) = Unit

    override suspend fun saveDraft(draft: EditorDraft) = Unit

    override suspend fun getLatestDraft(): EditorDraft? = null

    override suspend fun getAllDrafts(): List<EditorDraft> = emptyList()

    override suspend fun getDraft(id: Uuid): EditorDraft? = null

    override suspend fun deleteDraft(id: Uuid) = Unit
}

class FakeDetailJournalContentRepository(
    initialMemberships: Map<Uuid, List<Journal>> = emptyMap(),
) : JournalContentRepository {
    private val memberships = MutableStateFlow(initialMemberships)

    override fun observeContentForJournal(journalId: Uuid): Flow<List<JournalNote>> = flowOf(emptyList())

    override fun observeJournalsForContent(contentId: Uuid): Flow<List<Journal>> = flowOf(memberships.value[contentId].orEmpty())

    override suspend fun addContentToJournal(
        contentId: Uuid,
        journalId: Uuid,
    ) = Unit

    override suspend fun removeContentFromJournal(
        contentId: Uuid,
        journalId: Uuid,
    ) = Unit

    override suspend fun addContentToJournals(
        contentId: Uuid,
        journalIds: List<Uuid>,
    ) = Unit

    override suspend fun removeContentFromAllJournals(contentId: Uuid) = Unit

    override fun observeJournalsForContents(contentIds: Set<Uuid>): Flow<Map<Uuid, List<Journal>>> =
        flowOf(memberships.value.filterKeys { it in contentIds })
}

class FakeAudioDurationResolver(
    private val durationMs: Long = 0L,
) : AudioDurationResolver {
    override suspend fun resolveDurationMs(uri: String): Long? = durationMs
}

class FakeAudioPlaybackManager :
    AudioPlaybackManager,
    AudioPlaybackStatusProvider {
    private val statusFlow = MutableStateFlow(AudioPlaybackStatus())

    var startCalls = 0
    var pauseCalls = 0
    var stopCalls = 0
    var releaseCalls = 0
    var lastSeek: Float? = null
    var lastUri: String? = null
    var lastMetadata: AudioPlaybackMetadata? = null
    var onProgressUpdated: ((Float) -> Unit)? = null
    var onPlaybackCompleted: (() -> Unit)? = null

    override val playbackStatus: StateFlow<AudioPlaybackStatus> = statusFlow

    override fun startPlayback(
        uri: String,
        metadata: AudioPlaybackMetadata?,
        onProgressUpdated: (Float) -> Unit,
        onPlaybackCompleted: () -> Unit,
    ) {
        startCalls += 1
        lastUri = uri
        lastMetadata = metadata
        this.onProgressUpdated = onProgressUpdated
        this.onPlaybackCompleted = onPlaybackCompleted
        statusFlow.value = statusFlow.value.copy(isPlaying = true)
    }

    override fun pausePlayback() {
        pauseCalls += 1
        statusFlow.value = statusFlow.value.copy(isPlaying = false)
    }

    override fun stopPlayback() {
        stopCalls += 1
    }

    override fun seekTo(position: Float) {
        lastSeek = position
    }

    override fun release() {
        releaseCalls += 1
    }

    fun updateStatus(status: AudioPlaybackStatus) {
        statusFlow.value = status
    }
}

class FakeAmplitudeExtractor(
    private val amplitudes: List<Float> = listOf(0.1f, 0.2f, 0.3f),
) : AmplitudeExtractor {
    override suspend fun extractAmplitudes(
        uri: String,
        targetSampleCount: Int,
    ): List<Float> = amplitudes
}

class FakeWaveformStorage : WaveformStorage {
    override suspend fun save(
        audioUri: String,
        amplitudes: List<Float>,
    ) = Unit

    override suspend fun load(audioUri: String): List<Float>? = null

    override suspend fun exists(audioUri: String): Boolean = false

    override suspend fun delete(audioUri: String) = Unit
}

class FakeAudioTagRepository(
    private val tags: List<AudioTag> = emptyList(),
) : AudioTagRepository {
    override suspend fun replaceTagsForNote(
        noteId: Uuid,
        tags: List<AudioTag>,
    ) = Unit

    override suspend fun getTagsForNote(noteId: Uuid): List<AudioTag> = tags.filter { it.noteId == noteId }

    override fun observeTagsForNote(noteId: Uuid): Flow<List<AudioTag>> = flowOf(tags.filter { it.noteId == noteId })

    override suspend fun findNotesBySoundName(soundName: String): List<Uuid> =
        tags
            .filter { it.soundName.equals(soundName, ignoreCase = true) }
            .map { it.noteId }
            .distinct()
}
