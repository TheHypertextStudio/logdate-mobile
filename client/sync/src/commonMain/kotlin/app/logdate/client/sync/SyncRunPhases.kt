package app.logdate.client.sync

import app.logdate.client.sync.diagnostics.SyncRunDiagnostics
import app.logdate.client.sync.metadata.EntityType
import app.logdate.shared.model.diagnostics.DiagnosticPhase
import io.github.aakira.napier.Napier
import kotlinx.coroutines.sync.withLock
import kotlin.time.Instant

/**
 * Shared shape behind [uploadPendingChanges] and [downloadRemoteChanges]: the same
 * disabled/unauthenticated guard, a [syncStateFlow] transition around the body, unexpected
 * exceptions mapped through [SyncStatusPublisher.handleSyncException], and [SyncState.Idle]
 * always restored.
 *
 * @param beforeAccessToken Runs once the guard has passed and [syncStateFlow] is
 *   [SyncState.Syncing], but before the access token is fetched -- [uploadPendingChanges]
 *   needs to capture the pending count as the run's total *before* a possible auth failure,
 *   since once the queue starts draining, the original count is gone.
 */
internal suspend fun DefaultSyncManager.runFullSyncPhase(
    operationName: String,
    exceptionLabel: String,
    beforeAccessToken: suspend () -> Unit = {},
    body: suspend (accessToken: String) -> SyncResult,
): SyncResult =
    syncMutex.withLock {
        if (!isEnabled) {
            return SyncResult(
                success = false,
                errors = listOf(SyncError(SyncErrorType.UNKNOWN_ERROR, "Sync is disabled")),
            )
        }

        if (!tokenRefresher.isAuthenticated()) {
            Napier.w("attempted without authentication")
            return SyncResult(
                success = false,
                errors = listOf(SyncError(SyncErrorType.AUTHENTICATION_ERROR, "Not authenticated. Please sign in to sync.")),
            )
        }

        if (!readinessGate.allowProbe()) return SyncResult(success = false)
        syncStateFlow.value = SyncState.Syncing

        try {
            beforeAccessToken()
            val accessToken = tokenRefresher.getAccessToken() ?: return authError()
            provisionIdentityKey(accessToken)
            if (identityRecoveryNeededStore.isNeeded() || identityKeyManager?.hasIdentityKey() == false) {
                readinessGate.pause(
                    app.logdate.client.device.crypto
                        .IdentityKeyNotFoundException("Encryption identity unavailable"),
                )
                return SyncResult(success = false)
            }
            body(accessToken).also { if (it.success) readinessGate.ready() }
        } catch (e: Exception) {
            statusPublisher.handleSyncException(e, exceptionLabel)
        } finally {
            syncStateFlow.value = SyncState.Idle
        }
    }

internal suspend fun DefaultSyncManager.runPendingUploads(): SyncResult =
    runFullSyncPhase(
        operationName = "Upload",
        exceptionLabel = "Upload failed",
        beforeAccessToken = {
            // Captured here because this is the only moment the denominator exists: once the
            // run starts draining the queue, the count that is left is all anyone can see.
            statusPublisher.beginRun(runCatching { syncMetadataService.getPendingCount() }.getOrNull()?.takeIf { it > 0 })
        },
    ) { accessToken ->
        // Deliberately sequential. Every one of these writes into the same AT Protocol
        // repo, and a repo write is read-whole-tree, rebuild, write-head. Two of them in
        // flight at once each build a tree missing the other's record, and the later head
        // write wins -- the earlier record survives as an orphaned block but is no longer
        // reachable, so it silently disappears. Running them in parallel bought nothing
        // (they contend on one repo) and cost entries.
        val journalResult = uploader.uploadJournals(accessToken)
        if (readinessGate.reason.value != null) return@runFullSyncPhase journalResult
        val contentResult = uploader.uploadContent(accessToken)
        if (readinessGate.reason.value != null) return@runFullSyncPhase contentResult
        val mergeResult =
            if (journalResult.success && contentResult.success) {
                mergeUploader?.uploadPending() ?: SyncResult(success = true)
            } else {
                SyncResult(success = false)
            }
        val associationResult = uploader.uploadAssociations(accessToken)
        if (readinessGate.reason.value != null) return@runFullSyncPhase associationResult
        val draftResult = uploader.uploadDrafts(accessToken)
        if (readinessGate.reason.value != null) return@runFullSyncPhase draftResult
        val historyResult = locationHistorySyncEngine?.upload(accessToken) ?: SyncResult(success = true)
        val totalUploaded =
            journalResult.uploadedItems +
                contentResult.uploadedItems +
                mergeResult.uploadedItems +
                associationResult.uploadedItems +
                draftResult.uploadedItems +
                historyResult.uploadedItems
        statusPublisher.setRunCompleted(totalUploaded)
        val errors =
            journalResult.errors +
                contentResult.errors +
                mergeResult.errors +
                associationResult.errors +
                draftResult.errors +
                historyResult.errors

        val success = errors.isEmpty()
        if (success) {
            lastErrorFlow.value = null
            statusPublisher.refreshObservedQuotaFromServer("pending upload")
        } else {
            lastErrorFlow.value = errors.mostSevere()
        }

        SyncResult(
            success = success,
            uploadedItems = totalUploaded,
            errors = errors,
            lastSyncTime = latestSyncTime(),
            hasMorePending = historyResult.hasMorePending,
        )
    }

internal suspend fun DefaultSyncManager.runRemoteDownload(): SyncResult =
    runFullSyncPhase(
        operationName = "Download",
        exceptionLabel = "Download failed",
    ) { accessToken ->
        sessionStorage.getOriginBoundSession()?.let {
            remoteRefreshSchedule.recordAttempt(
                app.logdate.client.sync.metadata
                    .UploadScope(it.session.accountId, it.origin),
            )
        }
        auditLegacyRecordsIfNeeded()
        val journalSince = cursorFor(EntityType.JOURNAL)
        val contentSince = cursorFor(EntityType.NOTE)
        val associationSince = cursorFor(EntityType.ASSOCIATION)

        // Sequential for the same reason as the upload side: these share one server
        // instance whose per-request cost is proportional to repo size, and fanning out
        // was enough on its own to starve every request thread it had.
        val journalResult = downloader.downloadJournals(accessToken, journalSince)
        if (readinessGate.reason.value != null) return@runFullSyncPhase journalResult
        val contentResult = downloader.downloadContent(accessToken, contentSince)
        if (readinessGate.reason.value != null) return@runFullSyncPhase contentResult
        if (journalResult.remoteInventoryComplete) legacyBackfill.enqueueRecordsIfNeeded(EntityType.JOURNAL)
        if (contentResult.remoteInventoryComplete) legacyBackfill.enqueueRecordsIfNeeded(EntityType.NOTE)
        val associationResult = downloader.downloadAssociations(accessToken, associationSince)
        if (readinessGate.reason.value != null) return@runFullSyncPhase associationResult
        if (associationResult.success) legacyBackfill.enqueueMembershipsIfNeeded()
        val historyResult = locationHistorySyncEngine?.download(accessToken) ?: SyncResult(success = true)
        recoverMedia(accessToken, EntityType.NOTE)
        val totalDownloaded =
            journalResult.downloadedItems +
                contentResult.downloadedItems +
                associationResult.downloadedItems +
                historyResult.downloadedItems
        val conflictsResolved =
            journalResult.conflictsResolved +
                contentResult.conflictsResolved +
                associationResult.conflictsResolved
        val errors =
            journalResult.errors +
                contentResult.errors +
                associationResult.errors +
                historyResult.errors

        val success = errors.isEmpty()
        if (success) {
            lastErrorFlow.value = null
            statusPublisher.refreshObservedQuotaFromServer("remote download")
        } else {
            lastErrorFlow.value = errors.mostSevere()
        }

        SyncResult(
            success = success,
            downloadedItems = totalDownloaded,
            conflictsResolved = conflictsResolved,
            errors = errors,
            lastSyncTime = latestSyncTime(),
            hasMorePending = historyResult.hasMorePending,
        )
    }

/**
 * Shared shape behind every opportunistic, single-entity-type sync entry point
 * ([syncContent], [syncJournals], [syncAssociations], [syncDrafts]): check the caller-supplied
 * guard, download first (so a push that 409s has a fresh conflict marker to compare against),
 * start a run scoped to just this entity type's own pending count, then upload, and merge the
 * two results the same way every time.
 *
 * @param disabledOrUnauthenticated Runs before anything else; a non-null result short-circuits
 *   with it. Kept as a caller-supplied check rather than a fixed one because the four entry
 *   points don't all report "can't sync right now" the same way (some distinguish disabled vs.
 *   unauthenticated with their own [SyncError], one collapses both into a bare failure).
 * @param updatesGlobalSyncState Whether a run through this template should update
 *   [lastErrorFlow]/trigger [refreshObservedQuotaFromServer] the way [uploadPendingChanges]-driven
 *   runs do. `false` for [syncDrafts], which never reported into that shared state even before
 *   this template existed -- an unrelated, already-surfaced sync error must not be silently
 *   cleared by a successful draft-only sync, and a draft sync has no reason to trigger a quota
 *   refresh.
 * @param onException When non-null, wraps the body (guard excluded) in a catch that reports
 *   through this instead of letting the exception propagate -- only [syncDrafts] used to do
 *   this; the other three entry points still let an unexpected exception propagate uncaught.
 */
internal suspend fun DefaultSyncManager.syncEntityType(
    entityType: EntityType,
    quotaContext: String,
    disabledOrUnauthenticated: suspend () -> SyncResult?,
    download: suspend (accessToken: String, since: Instant) -> SyncResult,
    upload: suspend (accessToken: String) -> SyncResult,
    updatesGlobalSyncState: Boolean = true,
    onException: (suspend (Exception) -> SyncResult)? = null,
): SyncResult =
    syncMutex.withLock {
        disabledOrUnauthenticated()?.let { return@withLock it }

        if (!readinessGate.allowProbe()) return@withLock SyncResult(success = false)
        syncStateFlow.value = SyncState.Syncing
        try {
            val accessToken = tokenRefresher.getAccessToken() ?: return@withLock authError()
            provisionIdentityKey(accessToken)
            if (identityRecoveryNeededStore.isNeeded() || identityKeyManager?.hasIdentityKey() == false) {
                readinessGate.pause(
                    app.logdate.client.device.crypto
                        .IdentityKeyNotFoundException("Encryption identity unavailable"),
                )
                return@withLock SyncResult(success = false)
            }

            if (entityType == EntityType.DRAFT) uploader.enqueueDraftsForRichSync()
            val since = cursorFor(entityType)
            val downloadResult = download(accessToken, since)
            recoverMedia(accessToken, entityType)
            if (readinessGate.reason.value != null) return@withLock downloadResult

            statusPublisher.beginRunForPending(entityType)
            val uploadResult = upload(accessToken)

            val success = uploadResult.success && downloadResult.success
            if (updatesGlobalSyncState) {
                if (success) {
                    lastErrorFlow.value = null
                    statusPublisher.refreshObservedQuotaFromServer(quotaContext)
                } else {
                    lastErrorFlow.value = (downloadResult.errors + uploadResult.errors).mostSevere()
                }
            }

            SyncResult(
                success = success,
                uploadedItems = uploadResult.uploadedItems,
                downloadedItems = downloadResult.downloadedItems,
                conflictsResolved = downloadResult.conflictsResolved,
                errors = downloadResult.errors + uploadResult.errors,
                lastSyncTime = latestSyncTime(),
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (deviceBlockReason(e) !=
                null
            ) {
                statusPublisher.handleSyncException(e, quotaContext)
            } else if (onException != null) {
                onException(e)
            } else {
                throw e
            }
        } finally {
            syncStateFlow.value = SyncState.Idle
        }
    }

internal suspend fun DefaultSyncManager.runCompleteSync(): SyncResult {
    val run = SyncRunDiagnostics(diagnostics, diagnosticSource())
    return run.phase(DiagnosticPhase.RECOVERY) {
        uploader.enqueueEverythingOnFirstSync()
        val downloadResult = run.phase(DiagnosticPhase.FETCH) { downloadRemoteChanges() }
        if (readinessGate.reason.value != null) return@phase downloadResult
        val uploadResult = run.phase(DiagnosticPhase.UPLOAD) { uploadPendingChanges() }
        if (readinessGate.reason.value != null) return@phase uploadResult
        val draftResult = run.phase(DiagnosticPhase.RECOVERY) { syncDrafts() }

        val errors = downloadResult.errors + uploadResult.errors + draftResult.errors
        // Each phase updates the shared last error. A successful upload must not erase a failed
        // download from the same full run, or the worker retries with no visible reason.
        lastErrorFlow.value = errors.mostSevere()

        SyncResult(
            success =
                uploadResult.success &&
                    downloadResult.success &&
                    draftResult.success,
            uploadedItems = uploadResult.uploadedItems + draftResult.uploadedItems,
            downloadedItems = downloadResult.downloadedItems + draftResult.downloadedItems,
            conflictsResolved = downloadResult.conflictsResolved,
            errors = errors,
            lastSyncTime = latestSyncTime(),
            hasMorePending =
                downloadResult.hasMorePending ||
                    uploadResult.hasMorePending ||
                    draftResult.hasMorePending ||
                    (downloadInbox?.dueCount() ?: 0) > 0,
        )
    }
}
