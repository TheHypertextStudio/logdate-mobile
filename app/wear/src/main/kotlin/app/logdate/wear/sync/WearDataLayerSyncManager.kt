package app.logdate.wear.sync

import app.logdate.client.database.dao.HealthSnapshotDao
import app.logdate.client.media.storage.MediaFileResolver
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.journals.JournalRepository
import app.logdate.client.sync.SyncError
import app.logdate.client.sync.SyncErrorType
import app.logdate.client.sync.SyncManager
import app.logdate.client.sync.SyncResult
import app.logdate.client.sync.SyncStatus
import app.logdate.client.sync.datalayer.AssociationDataMapper
import app.logdate.client.sync.datalayer.HealthSnapshotDataMapper
import app.logdate.client.sync.datalayer.HealthSnapshotSyncData
import app.logdate.client.sync.datalayer.JournalDataMapper
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.PendingOperation
import app.logdate.client.sync.metadata.SyncDeadLetterRecord
import app.logdate.client.sync.metadata.SyncDeadLetterStore
import app.logdate.client.sync.metadata.SyncMetadataService
import app.logdate.client.sync.metadata.SyncRetryScheduleStore
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * [SyncManager] implementation that syncs journal data to a paired phone
 * via the Wear Data Layer API.
 *
 * Syncs notes, journals, journal-content associations, and health snapshots.
 * Text and metadata are sent as [com.google.android.gms.wearable.DataItem]s.
 * Audio files are streamed via [com.google.android.gms.wearable.ChannelClient].
 *
 * Resilience features:
 * - Exponential backoff (5s base, 5min cap) for transient failures
 * - Dead letter queue for permanently failed items (after 5 attempts)
 * - Parent-before-child ordering (journals → notes → associations → health)
 * - Audio notes stay pending until the phone acknowledges them, and are resent after
 *   [ACK_TIMEOUT_MS] without one
 *
 * Incoming data from the phone is handled by [WearDataLayerListenerService].
 */
class WearDataLayerSyncManager(
    private val dataLayerClient: WearDataLayerClient,
    private val syncMetadataService: SyncMetadataService,
    private val retryScheduleStore: SyncRetryScheduleStore,
    private val deadLetterStore: SyncDeadLetterStore,
    private val notesRepository: JournalNotesRepository,
    private val journalRepository: JournalRepository,
    private val healthSnapshotDao: HealthSnapshotDao,
    private val noteDataMapper: NoteDataMapper,
    private val journalDataMapper: JournalDataMapper,
    private val associationDataMapper: AssociationDataMapper,
    private val healthSnapshotDataMapper: HealthSnapshotDataMapper,
    private val mediaFiles: MediaFileResolver,
    private val clock: Clock = Clock.System,
    private val deliveryStore: WearAudioDeliveryStore,
) : SyncManager,
    WearNoteAckHandler,
    WearNoteRemovalNotifier {
    private val uploadTransport =
        WearSyncUploadTransport(
            dataLayerClient,
            notesRepository,
            journalRepository,
            noteDataMapper,
            journalDataMapper,
            associationDataMapper,
            mediaFiles,
            clock,
            deliveryStore,
        )
    private val retryPolicy = WearSyncRetryPolicy(syncMetadataService, retryScheduleStore, deadLetterStore, clock)

    @Volatile
    private var isSyncing = false

    /**
     * Sync entry points can overlap: a reconnect, a phone request, the repository's debounce and the
     * Sync now button all start one. Running them together would put the same audio note on the wire
     * twice, so each runs alone.
     */
    private val syncLock = Mutex()

    private val _syncStatusFlow =
        MutableStateFlow(
            SyncStatus(
                isEnabled = false,
                lastSyncTime = null,
                pendingUploads = 0,
                isSyncing = false,
                hasErrors = false,
            ),
        )
    override val syncStatusFlow: StateFlow<SyncStatus> = _syncStatusFlow.asStateFlow()

    override fun sync(startNow: Boolean) {
        Napier.d("Wear sync requested (startNow=$startNow)")
    }

    override suspend fun uploadPendingChanges(): SyncResult = syncLock.withLock { uploadPendingChangesLocked() }

    private suspend fun uploadPendingChangesLocked(): SyncResult {
        if (!dataLayerClient.isPhoneConnected()) {
            return notConnectedResult()
        }

        val pendingNotes = syncMetadataService.getPendingUploads(EntityType.NOTE)
        if (pendingNotes.isEmpty()) {
            syncMetadataService.updateLastSyncTime(EntityType.NOTE, clock.now())
            return SyncResult(success = true, uploadedItems = 0)
        }

        var uploaded = 0
        val errors = mutableListOf<SyncError>()

        for (pending in pendingNotes) {
            if (!retryPolicy.shouldAttempt(EntityType.NOTE, pending)) {
                Napier.d("Skipping note ${pending.entityId} — backoff not elapsed")
                continue
            }
            val outcome =
                when (pending.operation) {
                    PendingOperation.DELETE -> uploadTransport.uploadNoteDelete(pending)
                    PendingOperation.CREATE, PendingOperation.UPDATE -> uploadTransport.uploadNote(pending)
                }
            retryPolicy.handleOutcome(outcome, pending, EntityType.NOTE, errors) { uploaded++ }
        }

        val now = clock.now()
        syncMetadataService.updateLastSyncTime(EntityType.NOTE, now)
        return SyncResult(
            success = errors.isEmpty(),
            uploadedItems = uploaded,
            errors = errors,
            lastSyncTime = now,
        )
    }

    override suspend fun notifyRemoved(noteId: Uuid) = uploadTransport.notifyRemoved(noteId)

    override suspend fun onNoteAcknowledged(noteId: Uuid) = onNoteAcknowledged(noteId, null)

    override suspend fun onNoteAcknowledged(
        noteId: Uuid,
        transferId: String?,
    ) = syncLock.withLock {
        val deliveries = deliveryStore.deliveries(noteId.toString())
        val delivery =
            if (transferId != null) {
                deliveries.firstOrNull { it.token == transferId }
            } else {
                deliveries.singleOrNull()
            }
        if (delivery == null || (transferId == null && !delivery.allowsLegacy)) return@withLock
        val pending = syncMetadataService.getPendingUploads(EntityType.NOTE).firstOrNull { it.entityId == noteId.toString() }
        if (pending != null && delivery.matches(pending)) {
            val note = notesRepository.getNoteById(noteId) as? JournalNote.Audio ?: return@withLock
            if (!delivery.matchesSnapshot(uploadTransport.deliverySnapshot(note))) return@withLock
            if (!syncMetadataService.settleIfCurrent(EntityType.NOTE, pending, clock.now(), 1L)) return@withLock
            retryScheduleStore.clear(EntityType.NOTE, retryPolicy.retryKey(pending))
        } else if (transferId == null) {
            // A payload-free acknowledgement cannot identify an older operation after another edit.
            return@withLock
        }
        if (pending == null || delivery.operationId != null) retryScheduleStore.clear(EntityType.NOTE, delivery.retryKey)
        deadLetterStore
            .list()
            .filter {
                it.entityType == EntityType.NOTE.name && delivery.matchesFailure(it)
            }.forEach { deadLetterStore.remove(it.id) }
        deliveryStore.remove(delivery.token)
    }

    override suspend fun downloadRemoteChanges(): SyncResult {
        // Downloads are handled reactively by WearDataLayerListenerService.
        return SyncResult(success = true)
    }

    override suspend fun syncContent(): SyncResult {
        isSyncing = true
        try {
            return uploadPendingChanges()
        } finally {
            isSyncing = false
        }
    }

    override suspend fun syncJournals(): SyncResult = syncLock.withLock { syncJournalsLocked() }

    private suspend fun syncJournalsLocked(): SyncResult {
        if (!dataLayerClient.isPhoneConnected()) {
            return notConnectedResult()
        }

        val pendingJournals = syncMetadataService.getPendingUploads(EntityType.JOURNAL)
        if (pendingJournals.isEmpty()) {
            return SyncResult(success = true, uploadedItems = 0)
        }

        var uploaded = 0
        val errors = mutableListOf<SyncError>()

        for (pending in pendingJournals) {
            if (!retryPolicy.shouldAttempt(EntityType.JOURNAL, pending)) {
                Napier.d("Skipping journal ${pending.entityId} — backoff not elapsed")
                continue
            }
            val outcome =
                when (pending.operation) {
                    PendingOperation.DELETE -> uploadTransport.uploadJournalDelete(pending)
                    PendingOperation.CREATE, PendingOperation.UPDATE -> uploadTransport.uploadJournal(pending)
                }
            retryPolicy.handleOutcome(outcome, pending, EntityType.JOURNAL, errors) { uploaded++ }
        }

        return SyncResult(
            success = errors.isEmpty(),
            uploadedItems = uploaded,
            errors = errors,
            lastSyncTime = clock.now(),
        )
    }

    override suspend fun syncDrafts(): SyncResult = SyncResult(success = true)

    override suspend fun syncAssociations(): SyncResult = syncLock.withLock { syncAssociationsLocked() }

    private suspend fun syncAssociationsLocked(): SyncResult {
        if (!dataLayerClient.isPhoneConnected()) {
            return notConnectedResult()
        }

        val pendingAssociations = syncMetadataService.getPendingUploads(EntityType.ASSOCIATION)
        if (pendingAssociations.isEmpty()) {
            return SyncResult(success = true, uploadedItems = 0)
        }

        var uploaded = 0
        val errors = mutableListOf<SyncError>()

        for (pending in pendingAssociations) {
            if (!retryPolicy.shouldAttempt(EntityType.ASSOCIATION, pending)) {
                Napier.d("Skipping association ${pending.entityId} — backoff not elapsed")
                continue
            }
            val outcome =
                when (pending.operation) {
                    PendingOperation.DELETE -> uploadTransport.uploadAssociationDelete(pending)
                    PendingOperation.CREATE, PendingOperation.UPDATE -> uploadTransport.uploadAssociation(pending)
                }
            retryPolicy.handleOutcome(outcome, pending, EntityType.ASSOCIATION, errors) { uploaded++ }
        }

        return SyncResult(
            success = errors.isEmpty(),
            uploadedItems = uploaded,
            errors = errors,
            lastSyncTime = clock.now(),
        )
    }

    /**
     * Runs a full sync in dependency order: journals → notes → associations → health.
     *
     * This ordering ensures that:
     * 1. Journals exist on the phone before notes reference them
     * 2. Notes exist before associations link them to journals
     * 3. Health snapshots (which reference notes) are sent last
     *
     * If a parent sync phase fails, child phases still attempt — the phone
     * will buffer orphaned items and reconcile when the parent arrives.
     */
    override suspend fun fullSync(): SyncResult = syncLock.withLock { fullSyncLocked() }

    private suspend fun fullSyncLocked(): SyncResult {
        isSyncing = true
        try {
            // Phase 1: Journals first (parents)
            val journalsResult = syncJournalsLocked()
            // Phase 2: Notes second (children of journals)
            val notesResult = uploadPendingChangesLocked()
            // Phase 3: Associations third (links between journals and notes)
            val associationsResult = syncAssociationsLocked()
            // Phase 4: Health snapshots last (reference notes)
            val healthResult = syncHealthSnapshots()

            val totalUploaded =
                journalsResult.uploadedItems + notesResult.uploadedItems +
                    associationsResult.uploadedItems + healthResult.uploadedItems
            val allErrors =
                journalsResult.errors + notesResult.errors +
                    associationsResult.errors + healthResult.errors

            return SyncResult(
                success = allErrors.isEmpty(),
                uploadedItems = totalUploaded,
                errors = allErrors,
                lastSyncTime = clock.now(),
            )
        } finally {
            isSyncing = false
        }
    }

    override suspend fun getSyncStatus(): SyncStatus {
        val connected = dataLayerClient.isPhoneConnected()
        val pendingCount = syncMetadataService.getPendingCount()
        val lastSync = syncMetadataService.getLastSyncTime(EntityType.NOTE)
        val hasDeadLetters = deadLetterStore.list().isNotEmpty()

        return SyncStatus(
            isEnabled = connected,
            lastSyncTime = lastSync,
            pendingUploads = pendingCount,
            isSyncing = isSyncing,
            hasErrors = hasDeadLetters,
        )
    }

    override fun observeDeadLetters(): Flow<List<SyncDeadLetterRecord>> = deadLetterStore.observe()

    override suspend fun retryDeadLetter(id: String) = retryPolicy.retryDeadLetter(id)

    override suspend fun discardDeadLetter(id: String) {
        deadLetterStore.remove(id)
    }

    /**
     * Pushes recent health snapshots to the phone.
     *
     * Health snapshots are watch-only data (captured from on-wrist sensors).
     * They use the [EntityType.HEALTH] cursor for tracking the last sync time.
     */
    private suspend fun syncHealthSnapshots(): SyncResult {
        if (!dataLayerClient.isPhoneConnected()) {
            return notConnectedResult()
        }

        val lastHealthSync = syncMetadataService.getLastSyncTime(EntityType.HEALTH)
        val sinceEpoch = lastHealthSync?.toEpochMilliseconds() ?: 0L
        val recentSnapshots = healthSnapshotDao.getAfter(sinceEpoch)
        if (recentSnapshots.isEmpty()) {
            return SyncResult(success = true, uploadedItems = 0)
        }

        var uploaded = 0
        val errors = mutableListOf<SyncError>()

        for (entity in recentSnapshots) {
            val syncData =
                HealthSnapshotSyncData(
                    id = entity.id,
                    noteId = entity.noteId,
                    heartRateBpm = entity.heartRateBpm,
                    heartRateVariabilityMs = entity.heartRateVariabilityMs,
                    stepCount = entity.stepCount,
                    stressLevel = entity.stressLevel,
                    cumulativeCalories = entity.cumulativeCalories,
                    timestamp = entity.timestamp,
                    source = entity.source,
                )
            val dataMap = healthSnapshotDataMapper.toDataMap(syncData)
            val path = HealthSnapshotDataMapper.healthPath(entity.id)

            if (dataLayerClient.putDataItem(path, dataMap)) {
                uploaded++
            } else {
                errors.add(
                    SyncError(
                        type = SyncErrorType.NETWORK_ERROR,
                        message = "Failed to sync health snapshot ${entity.id}",
                        retryable = true,
                    ),
                )
            }
        }

        if (uploaded > 0) {
            syncMetadataService.updateLastSyncTime(EntityType.HEALTH, clock.now())
        }

        return SyncResult(
            success = errors.isEmpty(),
            uploadedItems = uploaded,
            errors = errors,
        )
    }

    // -----------------------------------------------------------------------
    // Shared helpers
    // -----------------------------------------------------------------------

    private fun notConnectedResult(): SyncResult =
        SyncResult(
            success = false,
            errors =
                listOf(
                    SyncError(
                        type = SyncErrorType.NETWORK_ERROR,
                        message = "Phone not connected",
                        retryable = true,
                    ),
                ),
        )

    companion object {
        const val KEY_SENT_AT = "sentAt"
        const val ACK_TIMEOUT_MS = 30_000L
        const val MAX_RETRY_ATTEMPTS = 5
        const val RETRY_BASE_DELAY_MS = 5_000L
        const val RETRY_MAX_DELAY_MS = 300_000L
    }
}
