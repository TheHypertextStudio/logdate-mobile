package app.logdate.client.sync

import app.logdate.client.datastore.SessionStorage
import app.logdate.client.device.crypto.IdentityKeyManager
import app.logdate.client.device.identity.DeviceIdProvider
import app.logdate.client.media.ManagedMediaPublicationCustody
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
import app.logdate.client.sync.cloud.DeviceSessionRevocationListener
import app.logdate.client.sync.cloud.account.ServerAccountRefresher
import app.logdate.client.sync.cloud.account.ServerAccountStore
import app.logdate.client.sync.cloud.account.ServerReportedAccount
import app.logdate.client.sync.conflict.ConflictResolver
import app.logdate.client.sync.conflict.SyncConflictStore
import app.logdate.client.sync.crypto.MediaPayloadKeyProvider
import app.logdate.client.sync.location.LocationHistorySyncEngine
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.FirstSyncEnqueueStore
import app.logdate.client.sync.metadata.IdentityRecoveryNeededStore
import app.logdate.client.sync.metadata.LastSyncErrorStore
import app.logdate.client.sync.metadata.MediaSyncRefStore
import app.logdate.client.sync.metadata.SyncBackoff
import app.logdate.client.sync.metadata.SyncDeadLetterRecord
import app.logdate.client.sync.metadata.SyncDeadLetterStore
import app.logdate.client.sync.metadata.SyncMetadataService
import app.logdate.client.sync.metadata.SyncRetryScheduleStore
import app.logdate.client.sync.metadata.UnreadableCloudRecordStore
import app.logdate.client.sync.moments.createMomentDownloader
import app.logdate.client.sync.moments.createMomentUploader
import app.logdate.client.sync.space.SpaceSync
import app.logdate.client.sync.space.SpaceSyncDependencies
import app.logdate.client.sync.space.migration.JournalStorageMove
import app.logdate.client.sync.space.migration.SpaceJournalMigration
import app.logdate.client.sync.space.migration.SpaceMigrationDependencies
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Instant

/** Coordinates Cloud sync through the upload, download, and recovery services. */
class DefaultSyncManager(
    private val cloudContentDataSource: CloudContentDataSource,
    private val cloudJournalDataSource: CloudJournalDataSource,
    private val cloudAssociationDataSource: CloudAssociationDataSource,
    internal val cloudMediaDataSource: CloudMediaDataSource,
    private val cloudDraftDataSource: CloudDraftDataSource,
    private val cloudAccountRepository: CloudAccountRepository,
    internal val sessionStorage: SessionStorage,
    internal val mediaManager: MediaManager,
    internal val mediaSyncRefStore: MediaSyncRefStore,
    internal val journalRepository: JournalRepository,
    internal val journalNotesRepository: JournalNotesRepository,
    internal val journalContentRepository: JournalContentRepository,
    private val journalConflictResolver: ConflictResolver<Journal>,
    private val noteConflictResolver: ConflictResolver<JournalNote>,
    private val conflictStore: SyncConflictStore,
    private val deadLetterStore: SyncDeadLetterStore,
    private val retryScheduleStore: SyncRetryScheduleStore,
    internal val syncMetadataService: SyncMetadataService,
    internal val transactionManager: SyncTransactionManager,
    private val dataUsagePolicy: DataUsagePolicy,
    internal val deviceIdProvider: DeviceIdProvider? = null,
    /** Wired on every platform; optional only so tests that never encrypt can omit it. */
    internal val identityKeyManager: IdentityKeyManager? = null,
    /**
     * Wired on every platform; optional so tests that never exercise identity provisioning can
     * omit it. Used only to forget the cached media key when [IdentityKeyProvisioner.provision] silently
     * restores an identity from an [app.logdate.client.device.crypto.IdentityKeyBackupStore] --
     * the cache otherwise keeps serving a key derived from whatever identity minted it.
     */
    private val mediaPayloadKeyProvider: MediaPayloadKeyProvider? = null,
    private val cloudQuotaManager: CloudQuotaManager? = null,
    private val backoff: SyncBackoff = SyncBackoff(),
    private val syncScope: CoroutineScope = backgroundSyncScope(),
    private val lastErrorStore: LastSyncErrorStore,
    internal val firstSyncEnqueueStore: FirstSyncEnqueueStore,
    /**
     * Wired on every platform; optional so tests that never exercise identity provisioning can
     * omit it. Without it, [IdentityKeyProvisioner.provision] cannot check whether the account already has
     * cloud data and falls back to minting a key on absence, matching behavior before that check
     * existed.
     */
    private val cloudApiClient: CloudApiClient? = null,
    internal val identityRecoveryNeededStore: IdentityRecoveryNeededStore,
    internal val unreadableCloudRecordStore: UnreadableCloudRecordStore,
    private val locationHistorySyncEngine: LocationHistorySyncEngine? = null,
    internal val downloadInbox: app.logdate.client.sync.recovery.DownloadInbox? = null,
    private val supportsRichDrafts: () -> Boolean = { false },
    internal val diagnostics: app.logdate.client.sync.diagnostics.SyncDiagnosticRecorder? = null,
    internal val diagnosticSource: () -> app.logdate.client.sync.diagnostics.DiagnosticSource? = { null },
    internal val mediaReferences: StoredMediaReferences = StoredMediaReferences.Unchanged,
    /** Told when the server revokes this device's session; a linked watch erases itself on it. */
    private val deviceSessionRevocationListener: DeviceSessionRevocationListener = DeviceSessionRevocationListener.None,
    /** How this device syncs; a linked watch uses [SyncProfile.LinkedWatch]. */
    internal val profile: SyncProfile = SyncProfile.Standard,
    private val createCollisionPolicy: CreateCollisionPolicy = CreateCollisionPolicy.None,
    internal val momentSyncConfig: app.logdate.client.sync.moments.MomentSyncConfig? = null,
    internal val deviceEnrollment: suspend () -> Unit = {},
    /**
     * Where the account `/auth/me` describes is saved when sync starts. Wired on every platform;
     * optional so tests that never read the account can omit it.
     */
    serverAccounts: ServerAccountStore? = null,
    /** Syncs registered kinds through the account's personal space when its gate is open; null keeps every kind on its own endpoints. */
    spaceSync: SpaceSyncDependencies? = null,
    private val entryDraftRecovery: app.logdate.client.repository.journals.EntryDraftRecoveryRepository? = null,
    /** Moves an existing journal from legacy storage into its personal space; null never moves one. */
    spaceMigration: SpaceMigrationDependencies? = null,
    /** Which store this device last synced each account through, to notice when the account moves. */
    internal val syncRouteStore: SyncRouteStore,
    private val publicationCustody: ManagedMediaPublicationCustody = ManagedMediaPublicationCustody.None,
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

    /** Work a periodic cycle can attempt now; backed-off and parked records do not count. */
    suspend fun hasDueWork(): Boolean {
        if (!isEnabled || !readinessGate.canProbe()) return false
        for (type in handledUploadTypes()) {
            if (syncMetadataService.getPendingUploads(type).any {
                    (it.operation != app.logdate.client.sync.metadata.PendingOperation.UPDATE || profile.uploadsUpdates) &&
                        retryCoordinator.shouldAttempt(type, it)
                }
            ) {
                return true
            }
        }
        if ((downloadInbox?.dueCount() ?: 0) > 0) return true
        val token = sessionStorage.getSession()?.accessToken ?: return false
        return historyEngine?.hasPendingUploads(token) == true
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

    internal val momentDownloader by lazy { momentSyncConfig?.let(::createMomentDownloader) }
    internal val momentUploader by lazy { momentSyncConfig?.let(::createMomentUploader) }

    internal val historyEngine = locationHistorySyncEngine.takeIf { profile.syncsLocationHistory }

    internal val identityProvisioner =
        IdentityKeyProvisioner(
            identityKeyManager = identityKeyManager,
            cloudApiClient = cloudApiClient,
            sessionStorage = sessionStorage,
            identityRecoveryNeededStore = identityRecoveryNeededStore,
            mediaPayloadKeyProvider = mediaPayloadKeyProvider,
            syncMetadataService = syncMetadataService,
            downloadInbox = downloadInbox,
            locationHistorySyncEngine = historyEngine,
            mintsIdentityKey = profile.mintsIdentityKey,
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
            linkedDevice = !profile.mintsIdentityKey,
            identityKeyMissing = { identityKeyManager?.hasIdentityKey() == false },
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
                try {
                    releaseUploadBackoff()
                    SyncPassLoop(runPass = { fullSync() }).runUntilSettled()
                } catch (failure: Exception) {
                    statusPublisher.handleSyncException(failure, "Background sync")
                }
            }
        }
    }

    override suspend fun uploadPendingChanges(): SyncResult = runPendingUploads()

    override suspend fun downloadRemoteChanges(): SyncResult = runRemoteDownload()

    override suspend fun fullSync(): SyncResult =
        try {
            runCompleteSync()
        } catch (error: Exception) {
            statusPublisher.handleSyncException(error, "Sync failed")
        }

    private val enrollment by lazy {
        SyncDeviceEnrollment(sessionStorage, deviceIdProvider, tokenRefresher::isAuthenticated, deviceEnrollment)
    }

    internal suspend fun ensureDeviceEnrolled() = enrollment.ensureEnrolled()

    internal suspend fun mediaRecoveryAllowed() =
        mediaDownloadAllowed(
            profile.downloadsMedia,
            dataUsagePolicy,
            downloadInbox?.currentScope(),
        )

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

    override suspend fun syncDrafts(): SyncResult {
        if (!profile.syncsDrafts) return SyncResult(success = true)
        return syncEntityType(
            entityType = EntityType.DRAFT,
            quotaContext = "draft sync",
            disabledOrUnauthenticated = {
                if (!isEnabled || !tokenRefresher.isAuthenticated()) SyncResult(success = false) else null
            },
            download = downloader::downloadDrafts,
            upload = uploader::uploadDrafts,
            updatesGlobalSyncState = false,
            onException = { e ->
                Napier.e("Draft sync failed", e)
                SyncResult(
                    success = false,
                    errors =
                        listOf(
                            SyncError(SyncErrorType.UNKNOWN_ERROR, "Draft sync failed: ${e.message ?: e::class.simpleName}", cause = e),
                        ),
                )
            },
        )
    }

    suspend fun isLocationHistorySyncEnabled(): Boolean = historyEngine?.isEnabled() == true

    /** Null where the profile does not queue what is already on the device, as with the first sync's sweep. */
    internal val legacyBackfill =
        LegacyLocalBackfill(
            downloadInbox,
            firstSyncEnqueueStore,
            journalRepository,
            journalNotesRepository,
            journalContentRepository,
            syncMetadataService,
        ).takeIf { profile.sweepsOnFirstSync }

    internal suspend fun auditLegacyRecordsIfNeeded() {
        if (!profile.sweepsOnFirstSync) return
        val inbox = downloadInbox ?: return
        if (identityKeyManager?.hasIdentityKey() != true) return
        val selected = inbox.currentScope()
        if (firstSyncEnqueueStore.hasAuditedLegacyScope(selected.owner, selected.origin)) return
        inbox.rewindForLegacyAudit(selected)
        check(selected == inbox.currentScope()) { "Download scope changed" }
        firstSyncEnqueueStore.markAuditedLegacyScope(selected.owner, selected.origin)
    }

    override suspend fun getSyncStatus(): SyncStatus {
        statusPublisher.publish()
        return statusPublisher.syncStatusFlow.value
    }

    override fun observeDeadLetters(): Flow<List<SyncDeadLetterRecord>> = deadLetterStore.observeQueued(syncMetadataService)

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

    suspend fun retryDeadLetterIfCurrent(id: String): Boolean = retryCoordinator.retryDeadLetterIfCurrent(id)

    override suspend fun discardDeadLetter(id: String) = retryCoordinator.discardDeadLetter(id)

    /**
     * Gets the last sync error, used for network recovery decisions.
     * Returns null if the last sync succeeded.
     */
    fun getLastSyncError(): SyncError? = lastErrorFlow.value

    internal fun authError() =
        SyncResult(
            success = false,
            errors = listOf(SyncError(SyncErrorType.AUTHENTICATION_ERROR, "No access token")),
        )

    internal val tokenRefresher = SyncTokenRefresher(sessionStorage, cloudAccountRepository, deviceSessionRevocationListener)

    private val accountRefresher =
        serverAccounts?.let { store -> cloudApiClient?.let { api -> ServerAccountRefresher(api, tokenRefresher, sessionStorage, store) } }

    /**
     * Reads the signed-in account from `/auth/me` and saves its DID and storage mode, or null when this
     * manager doesn't keep the account. A full sync does this first; a linked watch also does it as
     * soon as its phone links it.
     */
    suspend fun refreshServerAccount(): Result<ServerReportedAccount>? = accountRefresher?.refresh()

    internal suspend fun cursorFor(entityType: EntityType): Instant =
        syncMetadataService.getLastSyncTime(entityType)
            ?: Instant.fromEpochMilliseconds(0)

    internal suspend fun latestSyncTime(): Instant? = syncMetadataService.latestEntitySyncTime()

    private val mediaRecovery by lazy {
        SyncMediaRecoveryCoordinator(
            downloadInbox,
            journalNotesRepository,
            journalRepository,
            mediaManager,
            mediaTransfer,
            mediaSyncRefStore,
            transactionManager,
            entryDraftRecovery,
            mediaReferences,
        )
    }

    internal suspend fun recoverMedia(
        accessToken: String,
        entityType: EntityType,
    ) = mediaRecovery.recover(accessToken, entityType)

    private val mediaTransfer =
        SyncMediaTransfer(
            mediaManager = mediaManager,
            mediaSyncRefStore = mediaSyncRefStore,
            cloudMediaDataSource = cloudMediaDataSource,
            mediaReferences = mediaReferences,
            mayDownload = { mediaRecoveryAllowed() },
            publicationCustody = publicationCustody,
        )

    internal val retryCoordinator =
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

    internal val spaceSyncDependencies: SpaceSyncDependencies? = spaceSync

    internal val space =
        spaceSync?.let {
            SpaceSync(it, tokenRefresher, syncMetadataService, retryCoordinator, downloadInbox, statusPublisher::recordProgress)
        }

    /** Location history syncing through the personal space, when this device syncs location history at all. */
    internal val locationHistorySpace =
        space?.let { sync ->
            historyEngine?.forSpace(sync, syncMetadataService, transactionManager, downloadInbox, profile.uploadsUpdates)
        }

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
            downloadsMedia = profile.downloadsMedia,
            remoteApplyFilter = profile.remoteApplyFilter,
            space = space,
        )

    internal val mergeUploader =
        JournalMergeUploader(
            journalRepository,
            cloudApiClient,
            syncMetadataService,
            tokenRefresher,
            retryCoordinator,
            statusPublisher::recordProgress,
            space,
            profile.uploadsUpdates,
        )

    /** This device's move of the signed-in account's journal into its personal space, or null when it never moves one. */
    internal val journalMove: SpaceJournalMigration? by lazy { spaceMigration?.let { createJournalMove(it) } }

    /** What the person can see and do about moving their journal into its personal space. */
    val journalStorageMove: JournalStorageMove get() = journalMove ?: JournalStorageMove.Off

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
            profile = profile,
            createCollisionPolicy = createCollisionPolicy,
            transactionManager = transactionManager,
            space = space,
        )
}
