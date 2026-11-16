package app.logdate.feature.editor.ui.editor.fakes

import app.logdate.client.location.ClientLocationProvider
import app.logdate.client.location.settings.LocationTrackingSettings
import app.logdate.client.location.settings.LocationTrackingSettingsRepository
import app.logdate.client.media.MediaManager
import app.logdate.client.media.MediaObject
import app.logdate.client.media.MediaPayload
import app.logdate.client.repository.journals.EntryDraft
import app.logdate.client.repository.journals.EntryDraftRepository
import app.logdate.client.repository.journals.JournalContentRepository
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.journals.JournalRepository
import app.logdate.client.repository.journals.PendingMediaRecord
import app.logdate.client.repository.location.LocationHistoryItem
import app.logdate.client.repository.location.LocationHistoryRepository
import app.logdate.client.repository.timeline.ActivityTimelineRepository
import app.logdate.shared.model.ActivityTimelineItem
import app.logdate.shared.model.AltitudeUnit
import app.logdate.shared.model.EditorDraft
import app.logdate.shared.model.Journal
import app.logdate.shared.model.Location
import app.logdate.shared.model.LocationAltitude
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

class FakeJournalNotesRepository : JournalNotesRepository {
    private val notesFlow = MutableStateFlow<List<JournalNote>>(emptyList())
    val getNoteGates = mutableMapOf<Uuid, CompletableDeferred<Unit>>()
    var createFailure: Throwable? = null
    var createGate: CompletableDeferred<Unit>? = null

    /**
     * Invoked synchronously right after a note lands in [notesFlow], before [create] returns.
     * Real writes go through Room, whose own reactive query dispatches on a separate thread and
     * can complete before the calling coroutine resumes; tests that need to reproduce that
     * interleaving set this to drain the test dispatcher (e.g. `testScheduler.runCurrent()`) so
     * collectors of [allNotesObserved] observe the new row before the caller continues.
     */
    var afterCreate: (() -> Unit)? = null

    override val allNotesObserved: Flow<List<JournalNote>> = notesFlow

    override fun observeNotesInJournal(journalId: Uuid): Flow<List<JournalNote>> = flowOf(emptyList())

    override fun observeNotesInRange(
        start: Instant,
        end: Instant,
    ): Flow<List<JournalNote>> = notesFlow.map { notes -> notes.filter { it.creationTimestamp in start..end } }

    override fun observeNotesPage(
        pageSize: Int,
        offset: Int,
    ): Flow<List<JournalNote>> = flowOf(emptyList())

    override fun observeNotesStream(pageSize: Int): Flow<List<JournalNote>> = flowOf(emptyList())

    override fun observeRecentNotes(limit: Int): Flow<List<JournalNote>> = notesFlow

    override suspend fun getNoteById(noteId: Uuid): JournalNote? {
        getNoteGates[noteId]?.await()
        return notesFlow.value.firstOrNull { it.uid == noteId }
    }

    override suspend fun create(note: JournalNote): Uuid {
        createGate?.await()
        createFailure?.let { throw it }
        notesFlow.value = notesFlow.value + note
        afterCreate?.invoke()
        return note.uid
    }

    /** Test-only: injects a note directly, simulating a write this test didn't itself trigger. */
    fun seedExternally(note: JournalNote) {
        notesFlow.value = notesFlow.value + note
    }

    override suspend fun remove(note: JournalNote) {
        notesFlow.value = notesFlow.value.filterNot { it.uid == note.uid }
    }

    override suspend fun removeById(noteId: Uuid) {
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
        // No-op for tests
    }

    override suspend fun getAllJournalNoteLinks(): List<Pair<Uuid, Uuid>> = emptyList()
}

class FakeJournalContentRepository : JournalContentRepository {
    val addedJournalIds = mutableListOf<Uuid>()
    val journalsByContent = mutableMapOf<Uuid, List<Journal>>()
    val journalLoadGates = mutableMapOf<Uuid, CompletableDeferred<Unit>>()

    override fun observeContentForJournal(journalId: Uuid): Flow<List<JournalNote>> = flowOf(emptyList())

    override fun observeJournalsForContent(contentId: Uuid): Flow<List<Journal>> =
        flow {
            journalLoadGates[contentId]?.await()
            emit(journalsByContent[contentId].orEmpty())
        }

    override suspend fun addContentToJournal(
        contentId: Uuid,
        journalId: Uuid,
    ) {
        addedJournalIds += journalId
    }

    override suspend fun removeContentFromJournal(
        contentId: Uuid,
        journalId: Uuid,
    ) {
        // No-op for tests
    }

    override suspend fun addContentToJournals(
        contentId: Uuid,
        journalIds: List<Uuid>,
    ) {
        addedJournalIds += journalIds
    }

    override suspend fun removeContentFromAllJournals(contentId: Uuid) {
        // No-op for tests
    }

    override fun observeJournalsForContents(contentIds: Set<Uuid>): Flow<Map<Uuid, List<Journal>>> = flowOf(emptyMap())
}

class FakeJournalRepository : JournalRepository {
    private val journals = MutableStateFlow<List<Journal>>(emptyList())

    override val allJournalsObserved: Flow<List<Journal>> = journals

    override fun observeJournalById(id: Uuid): Flow<Journal> =
        flow {
            journals.value.firstOrNull { it.id == id }?.let { emit(it) }
        }

    override suspend fun getJournalById(id: Uuid): Journal? = journals.value.firstOrNull { it.id == id }

    override suspend fun create(journal: Journal): Uuid {
        journals.value = journals.value + journal
        return journal.id
    }

    override suspend fun update(journal: Journal) {
        journals.value =
            journals.value.map { existing ->
                if (existing.id == journal.id) journal else existing
            }
    }

    override suspend fun delete(journalId: Uuid) {
        journals.value = journals.value.filterNot { it.id == journalId }
    }

    override suspend fun saveDraft(draft: EditorDraft) {
        // No-op for tests
    }

    override suspend fun getLatestDraft(): EditorDraft? = null

    override suspend fun getAllDrafts(): List<EditorDraft> = emptyList()

    override suspend fun getDraft(id: Uuid): EditorDraft? = null

    override suspend fun deleteDraft(id: Uuid) {
        // No-op for tests
    }
}

class FakeEntryDraftRepository : EntryDraftRepository {
    private val drafts = MutableStateFlow<List<EntryDraft>>(emptyList())
    private val deletionFailures = mutableMapOf<Uuid, Throwable>()

    var getDraftFailure: Throwable? = null
    var deleteAllDraftsFailure: Throwable? = null
    var getDraftGate: CompletableDeferred<Unit>? = null

    /** Holds back the draft list, as a cold database does on first launch. */
    var draftListGate: CompletableDeferred<Unit>? = null
    var createDraftGate: CompletableDeferred<Unit>? = null
    var deleteDraftGate: CompletableDeferred<Unit>? = null
    var deleteAllDraftsGate: CompletableDeferred<Unit>? = null
    var createDraftFailure: Throwable? = null
    var createDraftFailureAfterCommit: Throwable? = null
    var createDraftCallCount: Int = 0
        private set
    var updateDraftCallCount: Int = 0
        private set

    /**
     * Configures [deleteDraft] to throw for the given [draftId].
     */
    fun setDeletionFailure(
        draftId: Uuid,
        failure: Throwable = IllegalStateException("Simulated deletion failure for draft $draftId"),
    ) {
        deletionFailures[draftId] = failure
    }

    override fun getDrafts(): Flow<List<EntryDraft>> =
        flow {
            draftListGate?.await()
            emitAll(drafts)
        }

    override fun getDraft(uid: Uuid): Flow<Result<EntryDraft>> =
        flow {
            getDraftGate?.await()
            getDraftFailure?.let { throw it }
            val draft = drafts.value.firstOrNull { it.id == uid }
            if (draft != null) {
                emit(Result.success(draft))
            } else {
                emit(Result.failure(NoSuchElementException("Draft not found")))
            }
        }

    override suspend fun createDraft(notes: List<JournalNote>): Uuid =
        createDraft(
            uid = Uuid.random(),
            notes = notes,
        )

    override suspend fun createDraft(
        uid: Uuid,
        notes: List<JournalNote>,
        pendingMedia: List<PendingMediaRecord>,
        selectedJournalIds: List<Uuid>,
    ): Uuid {
        createDraftCallCount++
        createDraftGate?.await()
        createDraftFailure?.let { throw it }
        val now = Clock.System.now()
        val draft =
            EntryDraft(
                id = uid,
                notes = notes,
                createdAt = now,
                updatedAt = now,
                pendingMedia = pendingMedia,
                selectedJournalIds = selectedJournalIds,
            )
        drafts.value = drafts.value.filterNot { it.id == uid } + draft
        createDraftFailureAfterCommit?.let { failure ->
            createDraftFailureAfterCommit = null
            throw failure
        }
        return draft.id
    }

    override suspend fun updateDraft(
        uid: Uuid,
        notes: List<JournalNote>,
    ): Uuid {
        val existing = drafts.value.firstOrNull { it.id == uid }
        return updateDraft(
            uid = uid,
            notes = notes,
            pendingMedia = existing?.pendingMedia.orEmpty(),
            selectedJournalIds = existing?.selectedJournalIds.orEmpty(),
        )
    }

    override suspend fun updateDraft(
        uid: Uuid,
        notes: List<JournalNote>,
        pendingMedia: List<PendingMediaRecord>,
        selectedJournalIds: List<Uuid>,
    ): Uuid {
        updateDraftCallCount++
        val now = Clock.System.now()
        val existing = drafts.value.firstOrNull { it.id == uid }
        val updated =
            if (existing != null) {
                existing.copy(
                    notes = notes,
                    pendingMedia = pendingMedia,
                    selectedJournalIds = selectedJournalIds,
                    updatedAt = now,
                )
            } else {
                EntryDraft(
                    id = uid,
                    notes = notes,
                    createdAt = now,
                    updatedAt = now,
                    pendingMedia = pendingMedia,
                    selectedJournalIds = selectedJournalIds,
                )
            }
        drafts.value = drafts.value.filterNot { it.id == uid } + updated
        return uid
    }

    override suspend fun setPendingMedia(
        uid: Uuid,
        pendingMedia: List<PendingMediaRecord>,
    ) {
        val existing = drafts.value.firstOrNull { it.id == uid } ?: return
        val updated =
            existing.copy(
                pendingMedia = pendingMedia,
                updatedAt = Clock.System.now(),
            )
        drafts.value = drafts.value.filterNot { it.id == uid } + updated
    }

    override suspend fun setSelectedJournalIds(
        uid: Uuid,
        selectedJournalIds: List<Uuid>,
    ) {
        val existing = drafts.value.firstOrNull { it.id == uid } ?: return
        val updated =
            existing.copy(
                selectedJournalIds = selectedJournalIds,
                updatedAt = Clock.System.now(),
            )
        drafts.value = drafts.value.filterNot { it.id == uid } + updated
    }

    override suspend fun deleteDraft(uid: Uuid) {
        deleteDraftGate?.await()
        deletionFailures[uid]?.let { throw it }
        drafts.value = drafts.value.filterNot { it.id == uid }
    }

    override suspend fun deleteAllDrafts() {
        deleteAllDraftsGate?.await()
        deleteAllDraftsFailure?.let { throw it }
        drafts.value = emptyList()
    }

    override suspend fun deleteExpiredDrafts(maxAge: Duration): Int {
        val now = Clock.System.now()
        val expired = drafts.value.filter { now - it.updatedAt > maxAge }
        drafts.value = drafts.value - expired.toSet()
        return expired.size
    }
}

class FakeClientLocationProvider : ClientLocationProvider {
    private val defaultLocation =
        Location(
            latitude = 0.0,
            longitude = 0.0,
            altitude = LocationAltitude(0.0, AltitudeUnit.METERS),
        )
    private val locationFlow =
        MutableSharedFlow<Location>(replay = 1).apply {
            tryEmit(defaultLocation)
        }

    override val currentLocation: SharedFlow<Location> = locationFlow

    override fun hasLocationPermission(): Boolean = true

    override suspend fun getCurrentLocation(): Location = defaultLocation

    override suspend fun refreshLocation() {
        locationFlow.emit(defaultLocation)
    }
}

class FakeActivityTimelineRepository : ActivityTimelineRepository {
    override val allItemsObserved: Flow<List<ActivityTimelineItem>> = flowOf(emptyList())

    override fun observeModelById(id: Uuid): Flow<ActivityTimelineItem> =
        flow {
            // No items to emit in tests
        }

    override suspend fun addActivity(item: ActivityTimelineItem) {
        // No-op for tests
    }

    override suspend fun removeActivity(item: ActivityTimelineItem) {
        // No-op for tests
    }

    override suspend fun updateActivity(item: ActivityTimelineItem) {
        // No-op for tests
    }

    override fun fetchActivitiesByType(type: String): Flow<List<ActivityTimelineItem>> = flowOf(emptyList())
}

class FakeLocationHistoryRepository : LocationHistoryRepository {
    override suspend fun getAllLocationHistory(): List<LocationHistoryItem> = emptyList()

    override fun observeLocationHistory(): Flow<List<LocationHistoryItem>> = flowOf(emptyList())

    override suspend fun getRecentLocationHistory(limit: Int): List<LocationHistoryItem> = emptyList()

    override suspend fun getLocationHistoryBetween(
        startTime: Instant,
        endTime: Instant,
    ): List<LocationHistoryItem> = emptyList()

    override suspend fun getLastLocation(): LocationHistoryItem? = null

    override fun observeLastLocation(): Flow<LocationHistoryItem?> = flowOf(null)

    override suspend fun logLocation(
        location: Location,
        userId: String,
        deviceId: String,
        confidence: Float,
        isGenuine: Boolean,
    ): Result<Unit> = Result.success(Unit)

    override suspend fun deleteLocationEntry(
        userId: String,
        deviceId: String,
        timestamp: Instant,
    ): Result<Unit> = Result.success(Unit)

    override suspend fun deleteLocationsBetween(
        startTime: Instant,
        endTime: Instant,
    ): Result<Unit> = Result.success(Unit)

    override suspend fun getLocationCount(): Int = 0
}

class FakeMediaManager : MediaManager {
    override suspend fun getMedia(uri: String): MediaObject =
        MediaObject.Image(
            uri = uri,
            size = 0,
            name = "media",
            timestamp = Clock.System.now(),
        )

    override suspend fun deleteOwnedMedia(uri: String): Boolean = false

    override suspend fun exists(mediaId: String): Boolean = false

    override suspend fun getRecentMedia(limit: Int): Flow<List<MediaObject>> = flowOf(emptyList())

    override suspend fun queryMediaByDate(
        start: Instant,
        end: Instant,
    ): Flow<List<MediaObject>> = flowOf(emptyList())

    override suspend fun addToDefaultCollection(uri: String) {
        // No-op for tests
    }

    override suspend fun readMedia(uri: String): MediaPayload =
        MediaPayload(
            fileName = "media.bin",
            mimeType = "application/octet-stream",
            sizeBytes = 0,
            data = ByteArray(0),
        )

    override suspend fun saveMedia(payload: MediaPayload): String = "file:///tmp/${payload.fileName}"

    override suspend fun saveMediaFromFile(
        sourceFilePath: String,
        fileName: String,
        mimeType: String,
    ): String = "file:///tmp/$fileName"
}

class FakeLocationTrackingSettingsRepository : LocationTrackingSettingsRepository {
    private val settings = LocationTrackingSettings()

    override suspend fun getSettings(): LocationTrackingSettings = settings

    override fun observeSettings(): kotlinx.coroutines.flow.Flow<LocationTrackingSettings> = flowOf(settings)

    override suspend fun updateSettings(settings: LocationTrackingSettings) {
        // No-op for tests
    }

    override suspend fun setBackgroundTrackingEnabled(enabled: Boolean) {
        // No-op for tests
    }

    override suspend fun setTrackingInterval(intervalMinutes: Long) {
        // No-op for tests
    }
}
