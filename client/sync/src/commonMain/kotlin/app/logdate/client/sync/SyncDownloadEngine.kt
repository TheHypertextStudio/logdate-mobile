package app.logdate.client.sync

import app.logdate.client.sync.cloud.CloudApiException
import app.logdate.client.sync.conflict.ConflictResolution
import app.logdate.client.sync.conflict.ConflictResolver
import app.logdate.client.sync.conflict.SyncConflictRecord
import app.logdate.client.sync.conflict.SyncConflictStore
import app.logdate.client.sync.diagnostics.DiagnosticSource
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.InMemoryUnreadableCloudRecordStore
import app.logdate.client.sync.metadata.PendingOperation
import app.logdate.client.sync.metadata.SyncMetadataService
import app.logdate.client.sync.metadata.UnreadableCloudRecordStore
import app.logdate.client.sync.recovery.DownloadInbox
import app.logdate.client.sync.recovery.DownloadScope
import app.logdate.shared.model.diagnostics.DiagnosticReason
import app.logdate.shared.model.diagnostics.SyncDiagnosticEvent
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Runs the download-paginate-resolve-conflicts sequence shared by every [EntityType] whose
 * download-side sync fits a [DownloadStrategy]: an item that only exists remotely is created, one
 * that exists both places goes through [DownloadStrategy.conflictResolver] unless a local edit is
 * already queued for it (in which case the remote side loses and the conflict is just recorded for
 * review), and a remote deletion is applied unless a local edit is queued against it too.
 * Associations (additions/deletions, not changes/deletions) and drafts (a simpler newer-wins
 * heuristic instead of a pluggable [ConflictResolver]) don't fit this shape and are downloaded by
 * their own functions instead of being forced through this engine.
 *
 * @param mapCloudApiError,mapException Reuse [DefaultSyncManager]'s own error mapping (including
 *   its [lastErrorFlow][DefaultSyncManager] side effect) instead of duplicating it here, so a
 *   download failure is reported identically to an upload failure.
 */
internal class SyncDownloadEngine(
    internal val transactionManager: SyncTransactionManager,
    internal val syncMetadataService: SyncMetadataService,
    private val conflictStore: SyncConflictStore,
    private val mapCloudApiError: (CloudApiException) -> SyncResult,
    private val mapException: (Exception, String) -> SyncResult,
    private val unreadableCloudRecordStore: UnreadableCloudRecordStore = InMemoryUnreadableCloudRecordStore(),
    internal val downloadInbox: DownloadInbox? = null,
    private val diagnosticSource: () -> DiagnosticSource? = { null },
    private val diagnostics: (SyncDiagnosticEvent, DiagnosticSource?) -> Unit = { _, _ -> },
    private val retainDeletedEdit: suspend (EntityType, String) -> Unit = { _, _ -> error("Recovery storage unavailable") },
) {
    internal val recordDiagnostics = SyncDownloadRecordDiagnostics(::report)

    private fun report(
        event: SyncDiagnosticEvent,
        source: DiagnosticSource?,
    ) {
        try {
            diagnostics(event, source)
        } catch (error: Exception) {
            throwIfDeviceBlocked(error)
            // A diagnostic sink cannot change record application or cancellation.
        }
    }

    /** Clear an unreadable marker only after applying a recovered version successfully. */
    suspend fun markRecovered(
        entityType: EntityType,
        id: Uuid,
    ) = unreadableCloudRecordStore.resolved(entityType, id)

    suspend fun repairUnreadable(
        entityType: EntityType,
        logLabel: String,
        unreadable: List<Uuid>,
        heldLocally: Set<Uuid>,
        selected: app.logdate.client.sync.recovery.DownloadScope? = downloadInbox?.currentScope(),
        observedVersions: Map<Uuid, Long> = emptyMap(),
    ) {
        enqueueUnreadableRepairs(
            entityType,
            logLabel,
            unreadable,
            heldLocally,
            selected,
            observedVersions,
            syncMetadataService,
            unreadableCloudRecordStore,
            ::ensureScope,
        )
    }

    suspend fun <T : Any> download(
        strategy: DownloadStrategy<T>,
        accessToken: String,
        since: Instant,
    ): SyncResult =
        try {
            val downloadScope = downloadInbox?.currentScope()
            val source = recordDiagnostics.captureSource(diagnosticSource, downloadScope)
            // Promote legacy pending rows before entering any Room apply transaction.
            val pendingById = syncMetadataService.getPendingUploads(strategy.entityType).associateBy { it.entityId }

            var cursor = downloadInbox?.cursor(strategy.entityType.name)?.let(Instant::fromEpochMilliseconds) ?: since
            var hasMore = true
            var totalDownloaded = 0
            var totalConflicts = 0
            var remoteInventoryComplete = false
            val errors = mutableListOf<SyncError>()

            while (hasMore) {
                val page =
                    strategy.fetchChanges(accessToken, cursor).getOrElse { fetchError ->
                        if (fetchError is CancellationException) throw fetchError
                        errors.add(SyncError(SyncErrorType.SERVER_ERROR, "Failed to fetch remote changes", cause = fetchError))
                        Napier.e("Failed to fetch remote changes: ${fetchError::class.simpleName}")
                        return SyncResult(
                            success = false,
                            downloadedItems = totalDownloaded,
                            conflictsResolved = totalConflicts,
                            errors = errors,
                        )
                    }
                val fetchFailure = downloadInbox?.fetchFailure(strategy.checkpoint)
                if (fetchFailure != null && fetchFailure != DiagnosticReason.NONE) {
                    errors.add(
                        SyncError(
                            when (fetchFailure) {
                                DiagnosticReason.SIGN_IN_REQUIRED -> SyncErrorType.AUTHENTICATION_ERROR
                                DiagnosticReason.OFFLINE -> SyncErrorType.NETWORK_ERROR
                                else -> SyncErrorType.SERVER_ERROR
                            },
                            "Remote fetch failed; saved records are still being recovered",
                        ),
                    )
                }
                val rows =
                    if (strategy.settle != null) {
                        emptyMap()
                    } else {
                        downloadInbox?.pending(strategy.entityType.name).orEmpty().associateBy { it.entityId }
                    }
                val hydrated =
                    page.changes.map { remote ->
                        val result =
                            try {
                                Result.success(strategy.hydrate(accessToken, remote))
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                Result.failure(error)
                            }
                        remote to result
                    }
                val appliedIds = mutableSetOf<String>()
                val skippedIds = mutableSetOf<String>()
                val outsideWindowIds = mutableSetOf<String>()
                val failedIds = mutableSetOf<String>()

                if (downloadScope != null) check(downloadScope == downloadInbox.currentScope()) { "Download scope changed" }
                val batchResult =
                    run {
                        var downloadedCount = 0
                        var conflictsResolved = 0
                        val batchErrors = mutableListOf<SyncError>()

                        for ((remoteItem, hydration) in hydrated) {
                            val recordId = strategy.idOf(remoteItem)
                            val diagnosticRow = rows[recordId.toString()]
                            val attemptId = if (diagnosticRow != null) Uuid.random().toString() else null
                            recordDiagnostics.started(diagnosticRow, attemptId, source)
                            val downloadedBefore = downloadedCount
                            val conflictsBefore = conflictsResolved
                            try {
                                transactionManager.withTransaction {
                                    ensureScope(downloadScope)
                                    var outcome = RemoteOutcome.STORED
                                    run record@{
                                        val item = hydration.getOrThrow()
                                        val id = strategy.idOf(item)
                                        val existing = strategy.localItem(id)
                                        val pendingLocal = syncMetadataService.hasPending(strategy.entityType, id.toString())

                                        if (existing != null &&
                                            acknowledgeUploadedOperation(
                                                strategy,
                                                existing,
                                                remoteItem,
                                                pendingById[id.toString()],
                                                syncMetadataService,
                                            )
                                        ) {
                                            return@record
                                        }

                                        if (existing == null && pendingLocal) {
                                            skippedIds += id.toString()
                                            outcome = RemoteOutcome.DECLINED
                                            return@record
                                        }
                                        if (existing == null && !strategy.appliesRemoteCreate(item)) {
                                            outsideWindowIds += id.toString()
                                            outcome = RemoteOutcome.DECLINED
                                            return@record
                                        }
                                        if (existing != null &&
                                            strategy.syncVersionOf(existing) > 0 &&
                                            strategy.syncVersionOf(existing) >= strategy.syncVersionOf(item)
                                        ) {
                                            if (existing != item || pendingLocal) skippedIds += id.toString()
                                            outcome = RemoteOutcome.DECLINED
                                            return@record
                                        }
                                        if (existing != null) {
                                            val hasPendingLocal = pendingLocal
                                            if (hasPendingLocal) {
                                                outcome = RemoteOutcome.DECLINED
                                                skippedIds += id.toString()
                                                conflictsResolved++
                                                Napier.w("Remote update conflicts with pending local work")
                                                recordConflict(
                                                    entityType = strategy.entityType,
                                                    entityId = id.toString(),
                                                    reason = "Local pending changes vs remote update",
                                                    localVersion = strategy.syncVersionOf(existing),
                                                    remoteVersion = strategy.syncVersionOf(item),
                                                    localUpdatedAt = strategy.lastUpdatedOf(existing),
                                                    remoteUpdatedAt = strategy.lastUpdatedOf(item),
                                                    textVersions = retainedTextVersions(existing, item, downloadScope),
                                                    audioVersions = retainedAudioVersions(existing, remoteItem, downloadScope),
                                                    selectedScope = downloadScope,
                                                )
                                                return@record
                                            }

                                            if (strategy.appliesRemoteOverUnchangedLocal) {
                                                strategy.applyReplace(existing, item)
                                                downloadedCount++
                                                return@record
                                            }
                                            val (localTimestamp, remoteTimestamp) =
                                                conflictTimestamps(
                                                    localSyncVersion = strategy.syncVersionOf(existing),
                                                    localUpdatedAt = strategy.lastUpdatedOf(existing),
                                                    remoteSyncVersion = strategy.syncVersionOf(item),
                                                    remoteUpdatedAt = strategy.lastUpdatedOf(item),
                                                )
                                            val resolution =
                                                strategy.conflictResolver.resolve(
                                                    local = existing,
                                                    remote = item,
                                                    localTimestamp = localTimestamp,
                                                    remoteTimestamp = remoteTimestamp,
                                                )

                                            when (resolution) {
                                                is ConflictResolution.KeepRemote -> {
                                                    strategy.applyReplace(existing, resolution.value)
                                                    conflictsResolved++
                                                    Napier.d("Conflict resolved using remote version")
                                                }

                                                is ConflictResolution.KeepLocal -> {
                                                    outcome = RemoteOutcome.KEPT_LOCAL
                                                    // Keeping the local copy is only half the resolution:
                                                    // without re-queueing it the server keeps its own
                                                    // version, the next download resolves the same conflict
                                                    // the same way, and the two never converge.
                                                    syncMetadataService.enqueuePending(
                                                        entityId = id.toString(),
                                                        entityType = strategy.entityType,
                                                        operation = PendingOperation.UPDATE,
                                                    )
                                                    conflictsResolved++
                                                    Napier.d("Conflict resolved using local version")
                                                }

                                                is ConflictResolution.Merge -> {
                                                    outcome = RemoteOutcome.MERGED
                                                    strategy.applyReplace(existing, resolution.merged)
                                                    syncMetadataService.enqueuePending(
                                                        entityId = id.toString(),
                                                        entityType = strategy.entityType,
                                                        operation = PendingOperation.UPDATE,
                                                    )
                                                    conflictsResolved++
                                                    Napier.d("Conflict resolved by merging")
                                                }

                                                is ConflictResolution.RequiresManualResolution -> {
                                                    outcome = RemoteOutcome.DECLINED
                                                    Napier.w(
                                                        "Conflict requires review",
                                                    )
                                                    recordConflict(
                                                        entityType = strategy.entityType,
                                                        entityId = id.toString(),
                                                        reason = resolution.reason,
                                                        localVersion = strategy.syncVersionOf(existing),
                                                        remoteVersion = strategy.syncVersionOf(item),
                                                        localUpdatedAt = strategy.lastUpdatedOf(existing),
                                                        remoteUpdatedAt = strategy.lastUpdatedOf(item),
                                                        textVersions = retainedTextVersions(existing, item, downloadScope),
                                                        audioVersions = retainedAudioVersions(existing, remoteItem, downloadScope),
                                                        selectedScope = downloadScope,
                                                    )
                                                }
                                            }
                                        } else {
                                            strategy.applyCreate(item)
                                            downloadedCount++
                                            Napier.d("Remote record applied")
                                        }
                                    }
                                    ensureScope(downloadScope)
                                    strategy.settle?.invoke(recordId, outcome)
                                    rows[recordId.toString()]?.let { row ->
                                        downloadInbox?.applied(
                                            strategy.entityType.name,
                                            row.entityId,
                                            row.version,
                                            requireNotNull(downloadScope),
                                            enqueueMedia =
                                                strategy.enqueuesMediaRecovery &&
                                                    row.entityId !in skippedIds &&
                                                    row.entityId !in outsideWindowIds,
                                        )
                                    }
                                    ensureScope(downloadScope)
                                }
                                appliedIds += recordId.toString()
                                recordDiagnostics.completed(diagnosticRow, attemptId, source, recordId.toString() in skippedIds)
                            } catch (e: Exception) {
                                throwIfDeviceBlocked(e)
                                downloadedCount = downloadedBefore
                                conflictsResolved = conflictsBefore
                                if (e is CancellationException) {
                                    recordDiagnostics.interrupted(diagnosticRow, attemptId, source)
                                    throw e
                                }
                                recordDiagnostics.failed(diagnosticRow, attemptId, source)
                                failedIds += recordId.toString()
                                if (strategy.settle != null) strategy.settleFailed(recordId)
                                batchErrors.add(
                                    SyncError(
                                        SyncErrorType.UNKNOWN_ERROR,
                                        "Failed to apply remote change",
                                        cause = e,
                                    ),
                                )
                                Napier.e("Failed to apply remote change: ${e::class.simpleName}")
                            }
                        }

                        val deletionResult =
                            applyRemoteDeletions(
                                strategy,
                                page.deletions,
                                DownloadDeletionContext(rows, downloadScope, source, skippedIds),
                            )
                        appliedIds += deletionResult.appliedIds
                        skippedIds += deletionResult.skippedIds
                        failedIds += deletionResult.failedIds
                        downloadedCount += deletionResult.batch.downloadedCount
                        conflictsResolved += deletionResult.batch.conflictsResolved
                        batchErrors += deletionResult.batch.errors

                        BatchResult(downloadedCount, conflictsResolved, batchErrors)
                    }

                totalDownloaded += batchResult.downloadedCount
                totalConflicts += batchResult.conflictsResolved
                errors.addAll(batchResult.errors)
                repairUnreadable(
                    strategy.entityType,
                    strategy.logLabel,
                    page.unreadable,
                    page.unreadable.filterTo(mutableSetOf()) { strategy.localItem(it) != null },
                    downloadScope,
                    page.unreadableVersions,
                )

                for (id in appliedIds) {
                    unreadableCloudRecordStore.resolved(strategy.entityType, Uuid.parse(id))
                }
                for (id in failedIds) {
                    rows[id]?.let {
                        downloadInbox?.failed(
                            strategy.entityType.name,
                            id,
                            DiagnosticReason.LOCAL_STORAGE.name,
                            it.version,
                            requireNotNull(downloadScope),
                            source = source,
                        )
                    }
                }
                for (id in page.unreadable) {
                    page.unreadableVersions[id]?.let { version ->
                        if (downloadScope != null) {
                            downloadInbox.failed(
                                strategy.entityType.name,
                                id.toString(),
                                DiagnosticReason.KEY_RECOVERY_REQUIRED.name,
                                version,
                                downloadScope,
                                source = source,
                            )
                        }
                    }
                }
                for (failure in page.failures) {
                    ensureScope(downloadScope)
                    if (downloadScope != null) {
                        downloadInbox.failed(
                            strategy.entityType.name,
                            failure.entityId,
                            failure.reason.name,
                            failure.serverVersion,
                            downloadScope,
                            source = source,
                        )
                    }
                    // Only an app update reads a record a newer version wrote. The inbox stores it
                    // and offers it again, so this sync has nothing left to finish. Without an inbox
                    // nothing stores it, and the page must stay unfinished.
                    if (failure.reason == DiagnosticReason.UNSUPPORTED_FORMAT && downloadScope != null) continue
                    errors += SyncError(SyncErrorType.UNKNOWN_ERROR, "Remote record remains queued for recovery")
                }
                // Without a durable inbox there is nowhere safe to retain failed records.
                if ((batchResult.errors.isNotEmpty() || page.unreadable.isNotEmpty() || page.failures.isNotEmpty()) &&
                    downloadInbox == null
                ) {
                    break
                }

                syncMetadataService.updateLastSyncTime(strategy.entityType, page.lastSyncTimestamp)

                if (!page.hasMore) {
                    ensureScope(downloadScope)
                    remoteInventoryComplete = fetchFailure == null || fetchFailure == DiagnosticReason.NONE
                    break
                }

                if (page.lastSyncTimestamp <= cursor) {
                    Napier.w(
                        "Remote pagination did not advance",
                    )
                    errors += SyncError(SyncErrorType.SERVER_ERROR, "Remote inventory remains incomplete", retryable = true)
                    break
                }

                cursor = page.lastSyncTimestamp
            }

            SyncResult(
                success = errors.isEmpty(),
                downloadedItems = totalDownloaded,
                conflictsResolved = totalConflicts,
                errors = errors,
                remoteInventoryComplete = remoteInventoryComplete,
            )
        } catch (e: CloudApiException) {
            mapCloudApiError(e)
        } catch (e: Exception) {
            throwIfDeviceBlocked(e)
            if (e is CancellationException) throw e
            mapException(e, "Download ${strategy.logLabel}s")
        }

    internal fun ensureScope(selected: app.logdate.client.sync.recovery.DownloadScope?) {
        if (selected != null && selected != downloadInbox?.currentScope()) throw CancellationException("Download scope changed")
    }

    suspend fun preserveDeletionConflict(
        entityType: EntityType,
        entityId: String,
    ) = preserveRemoteDeletionConflict(
        transactionManager,
        syncMetadataService,
        retainDeletedEdit,
        entityType,
        entityId,
    )

    internal suspend fun retainConflict(
        record: SyncConflictRecord,
        selectedScope: DownloadScope?,
    ) {
        ensureScope(selectedScope)
        conflictStore.add(record)
    }
}
