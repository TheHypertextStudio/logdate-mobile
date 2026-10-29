package app.logdate.client.sync

import app.logdate.client.sync.cloud.CloudApiException
import app.logdate.client.sync.diagnostics.SyncRunDiagnostics
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.UploadScope
import app.logdate.client.sync.space.SpaceRoute
import app.logdate.shared.model.diagnostics.DiagnosticPhase
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Instant

/**
 * Shared shape behind [uploadPendingChanges] and [downloadRemoteChanges]: the same
 * disabled/unauthenticated guard, a [syncStateFlow] transition around the body, unexpected
 * exceptions mapped through [SyncStatusPublisher.handleSyncException], and [SyncState.Idle]
 * always restored.
 *
 */
internal suspend fun DefaultSyncManager.runFullSyncPhase(
    operationName: String,
    exceptionLabel: String,
    body: suspend (accessToken: String) -> SyncResult,
): SyncResult =
    syncMutex.withLock {
        if (!tokenRefresher.isFullSyncScopeCurrent()) return SyncResult(success = false, hasMorePending = true)
        statusPublisher.beginRun(null)
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

        accountStorageBlocked()?.let { return it }

        if (!readinessGate.allowProbe()) return SyncResult(success = false)
        syncStateFlow.value = SyncState.Syncing

        try {
            val accessToken = tokenRefresher.getAccessToken() ?: return authError()
            identityProvisioner.provision(accessToken, requiresBoundAccountKey = space?.route() == SpaceRoute.Space)
            if (identityRecoveryNeededStore.isNeeded() || identityKeyManager?.hasIdentityKey() == false) {
                readinessGate.pause(
                    app.logdate.client.device.crypto
                        .IdentityKeyNotFoundException("Encryption identity unavailable"),
                )
                return SyncResult(success = false)
            }
            body(accessToken).also { if (it.success) readinessGate.ready() }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            statusPublisher.handleSyncException(e, exceptionLabel)
        } finally {
            syncStateFlow.value = SyncState.Idle
        }
    }

/**
 * Refuses to sync anything when the account's storage mode rules out both the personal space and
 * the per-kind endpoints: syncing either way would split the account's data between two stores.
 */
private suspend fun DefaultSyncManager.accountStorageBlocked(): SyncResult? {
    val blocked = space?.blocked() ?: return null
    val error = SyncError(SyncErrorType.ACCOUNT_STORAGE_ERROR, blocked.reason, retryable = false)
    lastErrorFlow.value = error
    return SyncResult(success = false, errors = listOf(error))
}

internal suspend fun DefaultSyncManager.runPendingUploads(): SyncResult =
    runFullSyncPhase(
        operationName = "Upload",
        exceptionLabel = "Upload failed",
    ) { accessToken ->
        statusPublisher.beginRun(pendingUploadsForRun())
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
                mergeUploader.uploadPending()
            } else {
                SyncResult(success = false)
            }
        if (readinessGate.reason.value != null) return@runFullSyncPhase mergeResult
        val associationResult = uploader.uploadAssociations(accessToken)
        if (readinessGate.reason.value != null) return@runFullSyncPhase associationResult
        val draftResult = if (profile.syncsDrafts) uploader.uploadDrafts(accessToken) else SyncResult(success = true)
        if (readinessGate.reason.value != null) return@runFullSyncPhase draftResult
        val historyResult = uploadLocationHistory(accessToken)
        if (readinessGate.reason.value != null) return@runFullSyncPhase historyResult
        val momentResult = momentUploader?.upload() ?: SyncResult(success = true)
        val totalUploaded =
            journalResult.uploadedItems +
                contentResult.uploadedItems +
                mergeResult.uploadedItems +
                associationResult.uploadedItems +
                draftResult.uploadedItems +
                historyResult.uploadedItems + momentResult.uploadedItems
        statusPublisher.setRunCompleted(totalUploaded)
        val errors =
            journalResult.errors +
                contentResult.errors +
                mergeResult.errors +
                associationResult.errors +
                draftResult.errors +
                historyResult.errors + momentResult.errors

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

/** Queue kinds with a handler in this device's active transport. Held rows are never discarded. */
internal suspend fun DefaultSyncManager.handledUploadTypes(): List<EntityType> =
    buildList {
        addAll(listOf(EntityType.JOURNAL, EntityType.NOTE, EntityType.JOURNAL_MERGE, EntityType.ASSOCIATION))
        if (profile.syncsDrafts) add(EntityType.DRAFT)
        if (momentUploader != null) add(EntityType.MOMENT)
        if (historyEngine != null) {
            for (type in app.logdate.client.sync.location.LOCATION_ENTITY_TYPES) {
                if (space?.activeKind(type) != null) add(type)
            }
        }
    }

/** Count only due operations this upload pass can handle; held legacy rows stay queued. */
private suspend fun DefaultSyncManager.pendingUploadsForRun(entityType: EntityType? = null): Int? {
    val types = handledUploadTypes().filter { entityType == null || it == entityType }
    var count = 0
    for (type in types) {
        for (pending in syncMetadataService.getPendingUploads(type)) {
            if (pending.operation == app.logdate.client.sync.metadata.PendingOperation.UPDATE && !profile.uploadsUpdates) continue
            if (retryCoordinator.shouldAttempt(type, pending)) count++
        }
    }
    return count.takeIf { it > 0 }
}

/** What one download pass brought, kind by kind. */
private data class RemoteDownloads(
    val journals: SyncResult,
    val content: SyncResult,
    val associations: SyncResult,
    val locationHistory: SyncResult,
    val moments: SyncResult,
)

/** Location history goes through the personal space when the account's data lives there, and through its own endpoints otherwise. */
private suspend fun DefaultSyncManager.uploadLocationHistory(accessToken: String): SyncResult {
    val engine = historyEngine ?: return SyncResult(success = true)
    val viaSpace = locationHistorySpace?.takeIf { it.isActive() } ?: return engine.upload(accessToken)
    return engine.uploadToSpace(accessToken, viaSpace)
}

private suspend fun DefaultSyncManager.downloadLocationHistory(accessToken: String): SyncResult {
    val engine = historyEngine ?: return SyncResult(success = true)
    val viaSpace = locationHistorySpace?.takeIf { it.isActive() } ?: return engine.download(accessToken)
    return engine.downloadFromSpace(accessToken, viaSpace)
}

private fun RemoteDownloads.blockedResult(): SyncResult =
    listOf(journals, content, associations, locationHistory, moments).firstOrNull { it.errors.isNotEmpty() }
        ?: SyncResult(success = false)

private suspend fun DefaultSyncManager.fetchRemoteDownloads(accessToken: String): RemoteDownloads {
    val journalSince = cursorFor(EntityType.JOURNAL)
    val contentSince = cursorFor(EntityType.NOTE)
    val associationSince = cursorFor(EntityType.ASSOCIATION)

    // Sequential for the same reason as the upload side: these share one server
    // instance whose per-request cost is proportional to repo size, and fanning out
    // was enough on its own to starve every request thread it had.
    val downloads =
        downloader.downloadPass {
            val journals = downloader.downloadJournals(accessToken, journalSince)
            val content =
                if (readinessGate.reason.value ==
                    null
                ) {
                    downloader.downloadContent(accessToken, contentSince)
                } else {
                    SyncResult(success = false)
                }
            val associations =
                if (readinessGate.reason.value ==
                    null
                ) {
                    downloader.downloadAssociations(accessToken, associationSince)
                } else {
                    SyncResult(success = false)
                }
            val history = if (readinessGate.reason.value == null) downloadLocationHistory(accessToken) else SyncResult(success = false)
            val moments =
                if (readinessGate.reason.value ==
                    null
                ) {
                    momentDownloader?.download() ?: SyncResult(success = true)
                } else {
                    SyncResult(success = false)
                }
            RemoteDownloads(journals, content, associations, history, moments)
        }
    return downloads
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
        val downloads = fetchRemoteDownloads(accessToken)
        val (journalResult, contentResult, associationResult, historyResult, momentResult) = downloads
        if (journalResult.remoteInventoryComplete) legacyBackfill?.enqueueRecordsIfNeeded(EntityType.JOURNAL)
        if (contentResult.remoteInventoryComplete) legacyBackfill?.enqueueRecordsIfNeeded(EntityType.NOTE)
        if (associationResult.success) legacyBackfill?.enqueueMembershipsIfNeeded()
        if (readinessGate.reason.value != null) return@runFullSyncPhase downloads.blockedResult()
        recoverMedia(accessToken, EntityType.NOTE)
        recoverMedia(accessToken, EntityType.JOURNAL)
        val totalDownloaded =
            journalResult.downloadedItems +
                contentResult.downloadedItems +
                associationResult.downloadedItems +
                historyResult.downloadedItems + momentResult.downloadedItems
        val conflictsResolved =
            journalResult.conflictsResolved +
                contentResult.conflictsResolved +
                associationResult.conflictsResolved
        val errors =
            journalResult.errors +
                contentResult.errors +
                associationResult.errors +
                historyResult.errors + momentResult.errors

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
            hasMorePending = historyResult.hasMorePending || momentResult.hasMorePending,
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
        if (!tokenRefresher.isFullSyncScopeCurrent()) return@withLock SyncResult(success = false, hasMorePending = true)
        disabledOrUnauthenticated()?.let { return@withLock it }
        accountStorageBlocked()?.let { return@withLock it }

        if (!readinessGate.allowProbe()) return@withLock SyncResult(success = false)
        syncStateFlow.value = SyncState.Syncing
        try {
            val accessToken = tokenRefresher.getAccessToken() ?: return@withLock authError()
            identityProvisioner.provision(accessToken, requiresBoundAccountKey = space?.route() == SpaceRoute.Space)
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

            statusPublisher.beginRun(pendingUploadsForRun(entityType))
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
    val selected = tokenRefresher.currentUploadScope()
    return withContext(FullSyncScope(selected)) {
        run.phase(DiagnosticPhase.RECOVERY) recovery@{
            if (!tokenRefresher.isFullSyncScopeCurrent()) return@recovery SyncResult(success = false, hasMorePending = true)
            val accountErrors = refreshAccountForSync()
            if (!tokenRefresher.isFullSyncScopeCurrent()) return@recovery SyncResult(success = false, hasMorePending = true)
            val routeErrors = prepareRouteForSync(selected, accountErrors.isEmpty())
            if (!tokenRefresher.isFullSyncScopeCurrent()) return@recovery SyncResult(success = false, hasMorePending = true)
            if (routeErrors.isNotEmpty()) return@recovery SyncResult(success = false, errors = routeErrors, hasMorePending = true)
            if (profile.sweepsOnFirstSync) uploader.enqueueEverythingOnFirstSync()
            val downloadResult = run.phase(DiagnosticPhase.FETCH) { downloadRemoteChanges() }
            if (!tokenRefresher.isFullSyncScopeCurrent()) return@recovery SyncResult(success = false, hasMorePending = true)
            if (readinessGate.reason.value != null) return@recovery downloadResult
            val uploadResult = run.phase(DiagnosticPhase.UPLOAD) { uploadPendingChanges() }
            if (!tokenRefresher.isFullSyncScopeCurrent()) return@recovery SyncResult(success = false, hasMorePending = true)
            if (readinessGate.reason.value != null) return@recovery uploadResult
            val draftResult = run.phase(DiagnosticPhase.RECOVERY) { syncDrafts() }
            if (readinessGate.reason.value != null) return@recovery draftResult
            if (!tokenRefresher.isFullSyncScopeCurrent()) return@recovery SyncResult(success = false, hasMorePending = true)

            enrollDeviceIfPossible()

            val errors = accountErrors + downloadResult.errors + uploadResult.errors + draftResult.errors
            // Each phase updates the shared last error. A successful upload must not erase a failed
            // download from the same full run, or the worker retries with no visible reason.
            lastErrorFlow.value = errors.mostSevere()
            val recovery = downloadInbox?.recoveryCounts(includePolicyDeferred = mediaRecoveryAllowed())

            SyncResult(
                success =
                    accountErrors.isEmpty() &&
                        uploadResult.success &&
                        downloadResult.success &&
                        draftResult.success &&
                        (recovery?.failed ?: 0) == 0,
                uploadedItems = uploadResult.uploadedItems + draftResult.uploadedItems,
                downloadedItems = downloadResult.downloadedItems + draftResult.downloadedItems,
                conflictsResolved = downloadResult.conflictsResolved,
                errors = errors,
                lastSyncTime = latestSyncTime(),
                hasMorePending =
                    downloadResult.hasMorePending ||
                        uploadResult.hasMorePending ||
                        draftResult.hasMorePending ||
                        (recovery?.retryable ?: 0) > 0,
            ).let { result ->
                if (result.success && !result.hasMorePending) journalMove?.runIfDue()
                result.copy(
                    recoveredMediaScope =
                        selected.takeIf {
                            result.success &&
                                !result.hasMorePending &&
                                (recovery?.retained ?: 0) == 0 &&
                                // A policy-deferred upload can outlive a successful full pass.
                                // It must not consume the readiness wake waiting behind this writer.
                                syncMetadataService.getPendingCount() == 0 &&
                                tokenRefresher.isFullSyncScopeCurrent()
                        },
                )
            }
        }
    }
}

/**
 * Reads the account from the server before anything else syncs, so its storage mode is the server's
 * current answer rather than a saved one. A failure is reported with the run's other errors; it never
 * stops the legacy phases, which the server refuses itself when the account isn't in legacy storage.
 */
internal suspend fun DefaultSyncManager.refreshAccountForSync(): List<SyncError> {
    if (!isEnabled || !tokenRefresher.isAuthenticated()) return emptyList()
    val failure = refreshServerAccount()?.exceptionOrNull() ?: return emptyList()
    Napier.e("The account could not be read from the server at the start of sync", failure)
    val unauthorized = (failure.cause as? CloudApiException)?.statusCode == 401
    return listOf(
        SyncError(
            type = if (unauthorized) SyncErrorType.AUTHENTICATION_ERROR else SyncErrorType.SERVER_ERROR,
            message = "The account could not be read from the server: ${failure.message}",
            cause = failure,
        ),
    )
}

/**
 * Before anything syncs: finishes switching a journal back to legacy storage if the app stopped part
 * way, so legacy downloads never replace changes still waiting to be queued, then notices when the
 * account changed stores since this device last synced it. Skipped when the account couldn't be read,
 * since its storage mode isn't known then. Nothing syncs when this fails, because syncing before the
 * device has adjusted to the account's store could replace newer changes.
 */
private suspend fun DefaultSyncManager.prepareRouteForSync(
    selected: UploadScope?,
    accountRead: Boolean,
): List<SyncError> {
    if (selected == null || !accountRead) return emptyList()
    return try {
        journalMove?.finishSwitchBackIfNeeded()
        rebaseForRoute(selected)
        emptyList()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Napier.e("Preparing this device for the account's storage failed", error)
        listOf(SyncError(SyncErrorType.UNKNOWN_ERROR, "Preparing this device for the account's storage failed: ${error.message}", error))
    }
}

/** Enrolls this device with the account when it isn't yet; a failure is logged and the next sync tries again. */
private suspend fun DefaultSyncManager.enrollDeviceIfPossible() {
    try {
        ensureDeviceEnrolled()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        Napier.w("Couldn't enroll this account device", failure)
    }
}
