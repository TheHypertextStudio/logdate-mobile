package app.logdate.client.sync

import app.logdate.client.datastore.SessionStorage
import app.logdate.client.device.crypto.IdentityKeyManager
import app.logdate.client.device.identity.DeviceIdProvider
import app.logdate.client.media.MediaManager
import app.logdate.client.media.storage.StoredMediaReferences
import app.logdate.client.networking.DataUsagePolicy
import app.logdate.client.repository.journals.JournalContentRepository
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.journals.JournalRepository
import app.logdate.client.sync.cloud.CloudApiClient
import app.logdate.client.sync.cloud.CloudAssociationDataSource
import app.logdate.client.sync.cloud.CloudContentDataSource
import app.logdate.client.sync.cloud.CloudDraftDataSource
import app.logdate.client.sync.cloud.CloudJournalDataSource
import app.logdate.client.sync.cloud.CloudMediaDataSource
import app.logdate.client.sync.conflict.ConflictResolver
import app.logdate.client.sync.conflict.SyncConflictStore
import app.logdate.client.sync.crypto.MediaPayloadKeyProvider
import app.logdate.client.sync.location.LocationHistorySyncEngine
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.FirstSyncEnqueueStore
import app.logdate.client.sync.metadata.IdentityRecoveryNeededStore
import app.logdate.client.sync.metadata.InMemoryFirstSyncEnqueueStore
import app.logdate.client.sync.metadata.InMemoryIdentityRecoveryNeededStore
import app.logdate.client.sync.metadata.InMemoryLastSyncErrorStore
import app.logdate.client.sync.metadata.InMemoryUnreadableCloudRecordStore
import app.logdate.client.sync.metadata.LastSyncErrorStore
import app.logdate.client.sync.metadata.MediaSyncRefStore
import app.logdate.client.sync.metadata.SyncBackoff
import app.logdate.client.sync.metadata.SyncDeadLetterRecord
import app.logdate.client.sync.metadata.SyncDeadLetterStore
import app.logdate.client.sync.metadata.SyncMetadataService
import app.logdate.client.sync.metadata.SyncRetryScheduleStore
import app.logdate.client.sync.metadata.UnreadableCloudRecordStore
import app.logdate.client.util.platformIODispatcher
import app.logdate.shared.model.CloudAccountRepository
import app.logdate.shared.model.CloudQuotaManager
import app.logdate.shared.model.Journal
import app.logdate.shared.model.diagnostics.DiagnosticOutcome
import app.logdate.shared.model.diagnostics.DiagnosticPhase
import app.logdate.shared.model.diagnostics.SyncDiagnosticEvent
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Instant

/**
 * Default implementation of SyncManager that coordinates synchronization
 * across all data sources using the LogDate Cloud API.
 *
 * This class acts as an orchestrator, delegating work to injected dependencies.
 */
class DefaultSyncManager(
    private val cloudContentDataSource: CloudContentDataSource,
    private val cloudJournalDataSource: CloudJournalDataSource,
    private val cloudAssociationDataSource: CloudAssociationDataSource,
    private val cloudMediaDataSource: CloudMediaDataSource,
    private val cloudDraftDataSource: CloudDraftDataSource,
    private val cloudAccountRepository: CloudAccountRepository,
    internal val sessionStorage: SessionStorage,
    private val mediaManager: MediaManager,
    private val mediaSyncRefStore: MediaSyncRefStore,
    private val journalRepository: JournalRepository,
    private val journalNotesRepository: JournalNotesRepository,
    private val journalContentRepository: JournalContentRepository,
    private val journalConflictResolver: ConflictResolver<Journal>,
    private val noteConflictResolver: ConflictResolver<JournalNote>,
    private val conflictStore: SyncConflictStore,
    private val deadLetterStore: SyncDeadLetterStore,
    private val retryScheduleStore: SyncRetryScheduleStore,
    internal val syncMetadataService: SyncMetadataService,
    private val transactionManager: SyncTransactionManager,
    private val dataUsagePolicy: DataUsagePolicy,
    private val deviceIdProvider: DeviceIdProvider? = null,
    /** Wired on every platform; optional only so tests that never encrypt can omit it. */
    internal val identityKeyManager: IdentityKeyManager? = null,
    /**
     * Wired on every platform; optional so tests that never exercise identity provisioning can
     * omit it. Used only to forget the cached media key when [provisionIdentityKey] silently
     * restores an identity from an [app.logdate.client.device.crypto.IdentityKeyBackupStore] --
     * the cache otherwise keeps serving a key derived from whatever identity minted it.
     */
    internal val mediaPayloadKeyProvider: MediaPayloadKeyProvider? = null,
    private val cloudQuotaManager: CloudQuotaManager? = null,
    private val backoff: SyncBackoff = SyncBackoff(),
    private val syncScope: CoroutineScope = CoroutineScope(platformIODispatcher),
    private val lastErrorStore: LastSyncErrorStore = InMemoryLastSyncErrorStore(),
    private val firstSyncEnqueueStore: FirstSyncEnqueueStore = InMemoryFirstSyncEnqueueStore(),
    /**
     * Wired on every platform; optional so tests that never exercise identity provisioning can
     * omit it. Without it, [provisionIdentityKey] cannot check whether the account already has
     * cloud data and falls back to minting a key on absence, matching behavior before that check
     * existed.
     */
    internal val cloudApiClient: CloudApiClient? = null,
    internal val identityRecoveryNeededStore: IdentityRecoveryNeededStore = InMemoryIdentityRecoveryNeededStore(),
    private val unreadableCloudRecordStore: UnreadableCloudRecordStore = InMemoryUnreadableCloudRecordStore(),
    internal val locationHistorySyncEngine: LocationHistorySyncEngine? = null,
    internal val downloadInbox: app.logdate.client.sync.recovery.DownloadInbox? = null,
    private val supportsRichDrafts: () -> Boolean = { false },
    internal val diagnostics: app.logdate.client.sync.diagnostics.SyncDiagnosticRecorder? = null,
    internal val diagnosticSource: () -> app.logdate.client.sync.diagnostics.DiagnosticSource? = { null },
    private val mediaReferences: StoredMediaReferences = StoredMediaReferences.Unchanged,
    val readinessGate: SyncReadinessGate = SyncReadinessGate(),
) : SyncManager {
    // Thread-safe state management using StateFlow and Mutex
    internal val syncStateFlow = MutableStateFlow<SyncState>(SyncState.Idle)
    internal val lastErrorFlow = MutableStateFlow<SyncError?>(null)
    internal val syncMutex = Mutex()

    internal var isEnabled = true
    internal val remoteRefreshSchedule = RemoteSyncRefreshSchedule()

    internal fun hasRemoteRefreshDue(): Boolean {
        if (!isEnabled || !readinessGate.canProbe()) return false
        val bound = sessionStorage.getOriginBoundSession() ?: return false
        return remoteRefreshSchedule.isDue(
            app.logdate.client.sync.metadata
                .UploadScope(bound.session.accountId, bound.origin),
        )
    }

    val deletionRecovery =
        app.logdate.client.sync.conflict.DeletionConflictRecovery(
            conflictStore,
            journalNotesRepository,
            journalRepository,
            journalContentRepository,
            transactionManager,
            {
                sessionStorage.getOriginBoundSession()?.let {
                    app.logdate.client.sync.metadata
                        .UploadScope(it.session.accountId, it.origin)
                }
            },
            mediaManager = mediaManager,
            mediaRefs = mediaSyncRefStore,
            metadata = syncMetadataService,
            mediaFiles = mediaReferences as? app.logdate.client.media.storage.MediaFileResolver,
        )

    internal val statusPublisher =
        SyncStatusPublisher(
            sessionStorage = sessionStorage,
            syncMetadataService = syncMetadataService,
            dataUsagePolicy = dataUsagePolicy,
            cloudQuotaManager = cloudQuotaManager,
            syncStateFlow = syncStateFlow,
            lastErrorFlow = lastErrorFlow,
            syncScope = syncScope,
            lastErrorStore = lastErrorStore,
            latestSyncTime = ::latestSyncTime,
            isEnabled = { isEnabled },
            conflictStore = conflictStore,
            identityRecoveryNeededStore = identityRecoveryNeededStore,
            unreadableCloudRecordStore = unreadableCloudRecordStore,
            pendingRecoveryCount = { downloadInbox?.count() ?: 0 },
            attentionRecoveryCount = { downloadInbox?.needingAttentionCount() ?: 0 },
            readinessGate = readinessGate,
        )
    override val syncStatusFlow: StateFlow<SyncStatus> = statusPublisher.syncStatusFlow

    private val downloadEngine =
        SyncDownloadEngine(
            transactionManager = transactionManager,
            syncMetadataService = syncMetadataService,
            conflictStore = conflictStore,
            retainDeletedEdit = deletionRecovery::retain,
            mapCloudApiError = statusPublisher::handleCloudApiError,
            mapException = statusPublisher::handleSyncException,
            unreadableCloudRecordStore = unreadableCloudRecordStore,
            downloadInbox = downloadInbox,
            diagnosticSource = diagnosticSource,
            diagnostics = { event, source -> diagnostics?.record(event, source?.scope, source?.epoch) },
        )

    /**
     * Arming a periodic/background schedule is a platform concern this class has no access to --
     * the platform-specific [SyncManager]s (Android's WorkManager-backed one, [ForegroundSyncManager]
     * elsewhere) each do their own scheduling and never call this method at all. So the only
     * meaningful case here is `startNow`: fire a sync immediately; per [SyncManager.sync]'s own
     * contract, `false` does not start syncing immediately.
     */
    override fun sync(startNow: Boolean) {
        if (startNow) {
            syncScope.launch {
                releaseUploadBackoff()
                SyncPassLoop(runPass = { fullSync() }).runUntilSettled()
            }
        }
    }

    override suspend fun uploadPendingChanges(): SyncResult = runPendingUploads()

    override suspend fun downloadRemoteChanges(): SyncResult = runRemoteDownload()

    override suspend fun fullSync(): SyncResult =
        try {
            runCompleteSync()
        } catch (e: Exception) {
            statusPublisher.handleSyncException(e, "Sync failed")
        }

    override suspend fun <T> whilePaused(block: suspend () -> T): T = syncMutex.withLock { block() }

    /**
     * The "disabled" / "not authenticated" guard shared by [syncContent]/[syncJournals]/[syncAssociations]
     * -- each reports disabled vs. unauthenticated as distinct [SyncError]s, unlike [syncDrafts]'s
     * own single combined check.
     */
    private suspend fun disabledOrUnauthenticatedResult(): SyncResult? =
        if (!isEnabled) {
            SyncResult(success = false, errors = listOf(SyncError(SyncErrorType.UNKNOWN_ERROR, "Sync is disabled")))
        } else if (!tokenRefresher.isAuthenticated()) {
            SyncResult(success = false, errors = listOf(SyncError(SyncErrorType.AUTHENTICATION_ERROR, "Not authenticated")))
        } else {
            null
        }

    override suspend fun syncContent(): SyncResult =
        syncEntityType(
            entityType = EntityType.NOTE,
            quotaContext = "content sync",
            disabledOrUnauthenticated = ::disabledOrUnauthenticatedResult,
            download = downloader::downloadContent,
            upload = uploader::uploadContent,
        )

    override suspend fun syncJournals(): SyncResult =
        syncEntityType(
            entityType = EntityType.JOURNAL,
            quotaContext = "journal sync",
            disabledOrUnauthenticated = ::disabledOrUnauthenticatedResult,
            download = downloader::downloadJournals,
            upload = uploader::uploadJournals,
        )

    override suspend fun syncAssociations(): SyncResult =
        syncEntityType(
            entityType = EntityType.ASSOCIATION,
            quotaContext = "association sync",
            disabledOrUnauthenticated = ::disabledOrUnauthenticatedResult,
            download = downloader::downloadAssociations,
            upload = uploader::uploadAssociations,
        )

    override suspend fun syncDrafts(): SyncResult =
        syncEntityType(
            entityType = EntityType.DRAFT,
            quotaContext = "draft sync",
            disabledOrUnauthenticated = {
                if (!isEnabled || !tokenRefresher.isAuthenticated()) SyncResult(success = false) else null
            },
            download = downloader::downloadDrafts,
            upload = uploader::uploadDrafts,
            updatesGlobalSyncState = false,
            onException = { e ->
                Napier.e("Draft sync failed")
                SyncResult(
                    success = false,
                    errors = listOf(SyncError(SyncErrorType.UNKNOWN_ERROR, "Draft sync failed")),
                )
            },
        )

    suspend fun isLocationHistorySyncEnabled(): Boolean = locationHistorySyncEngine?.isEnabled() == true

    /** Work a periodic cycle would actually do now. Backed-off and parked rows do not count. */
    suspend fun hasDueWork(): Boolean {
        if (!readinessGate.canProbe()) return false
        for (type in EntityType.entries) {
            if (syncMetadataService.getPendingUploads(type).any { retryCoordinator.shouldAttempt(type, it) }) return true
        }
        if ((downloadInbox?.dueCount() ?: 0) > 0) return true
        val accessToken = sessionStorage.getSession()?.accessToken ?: return false
        return locationHistorySyncEngine?.hasPendingUploads(accessToken) == true
    }

    private val recovery by lazy {
        SyncRecoveryCoordinator(
            downloadInbox,
            firstSyncEnqueueStore,
            journalRepository,
            journalNotesRepository,
            journalContentRepository,
            syncMetadataService,
            identityKeyManager,
            mediaTransfer,
            mediaSyncRefStore,
            transactionManager,
        )
    }
    internal val legacyBackfill get() = recovery.legacyBackfill

    internal suspend fun auditLegacyRecordsIfNeeded() = recovery.auditLegacyRecordsIfNeeded()

    override suspend fun getSyncStatus(): SyncStatus {
        statusPublisher.publish()
        return statusPublisher.syncStatusFlow.value
    }

    override fun observeDeadLetters(): Flow<List<SyncDeadLetterRecord>> =
        combine(deadLetterStore.observe(), syncMetadataService.observePendingUploads()) { issues, pending ->
            issues.filter { issue ->
                pending.any { queued ->
                    issue.entityType == queued.entityType?.name &&
                        issue.entityId == queued.entityId &&
                        issue.scope == queued.scope &&
                        issue.operationId == queued.operationId &&
                        issue.operation == queued.operation.name
                }
            }
        }

    /** Reconsider transient work after connectivity returns or an immediate backup is requested. */
    suspend fun releaseUploadBackoff() {
        syncMutex.withLock {
            readinessGate.allowProbe(manual = true)
            diagnostics?.record(SyncDiagnosticEvent(DiagnosticPhase.SCHEDULING, DiagnosticOutcome.QUEUED))
            retryCoordinator.releaseBackoff()
            downloadInbox?.release()
        }
    }

    internal suspend fun resumeAfterUpgrade(
        version: String,
        resumption: SyncUpgradeResumption,
    ) {
        val bound = sessionStorage.getOriginBoundSession() ?: return
        val selected =
            app.logdate.client.sync.metadata
                .UploadScope(bound.session.accountId, bound.origin)

        fun isCurrent(): Boolean =
            sessionStorage.getOriginBoundSession()?.let {
                it.origin == selected.serverOrigin && it.session.accountId == selected.ownerId
            } == true
        syncMutex.withLock {
            resumption.resume(version, selected, ::isCurrent) {
                retryCoordinator.releaseBackoff(afterUpgrade = true, expectedScope = selected)
                check(isCurrent()) { "Sync scope changed" }
                downloadInbox?.release()
            }
        }
    }

    override suspend fun retryDeadLetter(id: String) = retryCoordinator.retryDeadLetter(id)

    override suspend fun discardDeadLetter(id: String) = retryCoordinator.discardDeadLetter(id)

    /**
     * Gets the last sync error, used for network recovery decisions.
     * Returns null if the last sync succeeded.
     */
    fun getLastSyncError(): SyncError? = lastErrorFlow.value

    /**
     * Helper to create authentication error result.
     */
    internal fun authError() =
        SyncResult(
            success = false,
            errors = listOf(SyncError(SyncErrorType.AUTHENTICATION_ERROR, "No access token")),
        )

    internal val tokenRefresher = SyncTokenRefresher(sessionStorage, cloudAccountRepository)

    internal suspend fun cursorFor(entityType: EntityType): Instant =
        syncMetadataService.getLastSyncTime(entityType)
            ?: Instant.fromEpochMilliseconds(0)

    internal suspend fun latestSyncTime(): Instant? {
        val times =
            listOf(
                syncMetadataService.getLastSyncTime(EntityType.JOURNAL),
                syncMetadataService.getLastSyncTime(EntityType.NOTE),
                syncMetadataService.getLastSyncTime(EntityType.ASSOCIATION),
                syncMetadataService.getLastSyncTime(EntityType.MEDIA),
                syncMetadataService.getLastSyncTime(EntityType.DRAFT),
            ).filterNotNull()

        return times.maxOrNull()
    }

    internal suspend fun recoverMedia(
        accessToken: String,
        entityType: EntityType,
    ) = recovery.recoverMedia(accessToken, entityType)

    private val mediaTransfer =
        SyncMediaTransfer(
            mediaManager = mediaManager,
            mediaSyncRefStore = mediaSyncRefStore,
            cloudMediaDataSource = cloudMediaDataSource,
            mediaReferences = mediaReferences,
        )

    private val retryCoordinator =
        SyncRetryCoordinator(
            retryScheduleStore = retryScheduleStore,
            syncMetadataService = syncMetadataService,
            deadLetterStore = deadLetterStore,
            backoff = backoff,
            recordConflict = downloadEngine::recordConflict,
            preserveDeletedEdit = downloadEngine::preserveDeletionConflict,
            diagnosticSource = diagnosticSource,
            diagnostics = { event, source -> diagnostics?.record(event, source?.scope, source?.epoch) },
        )

    internal val downloader =
        SyncDownloader(
            journalRepository = journalRepository,
            journalNotesRepository = journalNotesRepository,
            journalContentRepository = journalContentRepository,
            cloudJournalDataSource = cloudJournalDataSource,
            cloudContentDataSource = cloudContentDataSource,
            cloudDraftDataSource = cloudDraftDataSource,
            cloudAssociationDataSource = cloudAssociationDataSource,
            journalConflictResolver = journalConflictResolver,
            noteConflictResolver = noteConflictResolver,
            syncMetadataService = syncMetadataService,
            transactionManager = transactionManager,
            mediaSyncRefStore = mediaSyncRefStore,
            downloadEngine = downloadEngine,
            mediaTransfer = mediaTransfer,
            tokenRefresher = tokenRefresher,
            mapCloudApiError = statusPublisher::handleCloudApiError,
            mapException = statusPublisher::handleSyncException,
            downloadInbox = downloadInbox,
        )

    internal val mergeUploader =
        cloudApiClient?.let { api ->
            JournalMergeUploader(
                journalRepository,
                api,
                syncMetadataService,
                tokenRefresher,
                retryCoordinator,
                statusPublisher::recordProgress,
            )
        }

    internal val uploader =
        SyncUploader(
            journalRepository = journalRepository,
            journalNotesRepository = journalNotesRepository,
            cloudJournalDataSource = cloudJournalDataSource,
            cloudContentDataSource = cloudContentDataSource,
            cloudAssociationDataSource = cloudAssociationDataSource,
            cloudDraftDataSource = cloudDraftDataSource,
            mediaSyncRefStore = mediaSyncRefStore,
            syncMetadataService = syncMetadataService,
            dataUsagePolicy = dataUsagePolicy,
            deviceIdProvider = deviceIdProvider,
            tokenRefresher = tokenRefresher,
            mediaTransfer = mediaTransfer,
            retryCoordinator = retryCoordinator,
            firstSyncEnqueueStore = firstSyncEnqueueStore,
            supportsRichDrafts = supportsRichDrafts,
            draftRepairScope = { downloadInbox?.currentScope() },
            mapCloudApiError = statusPublisher::handleCloudApiError,
            mapException = statusPublisher::handleSyncException,
            recordProgress = statusPublisher::recordProgress,
            setMediaDeferredForNetwork = statusPublisher::setMediaDeferredForNetwork,
        )
}
