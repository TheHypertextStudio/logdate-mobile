package app.logdate.client.sync

import app.logdate.client.repository.journals.JournalRepository
import app.logdate.client.repository.journals.SyncableDraftRepository
import app.logdate.client.sync.cloud.CloudApiException
import app.logdate.client.sync.cloud.CloudDraftDataSource
import app.logdate.client.sync.cloud.DraftSyncResult
import app.logdate.client.sync.cloud.RemoteRecordFailure
import app.logdate.client.sync.cloud.SyncedDraft
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.PendingOperation
import app.logdate.client.sync.metadata.SyncMetadataService
import app.logdate.client.sync.recovery.DownloadInbox
import app.logdate.client.sync.recovery.DownloadScope
import app.logdate.client.sync.space.SpaceKind
import app.logdate.client.sync.space.SpaceSync
import app.logdate.client.sync.space.mappers.SpaceDraft
import app.logdate.shared.model.EditorDraft
import app.logdate.shared.model.SerializableTextBlock
import app.logdate.shared.model.diagnostics.DiagnosticOutcome
import app.logdate.shared.model.diagnostics.DiagnosticReason
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Applies draft changes and deletions in transactions, then settles their staged inbox rows.
 */
internal class DraftDownloader(
    private val journalRepository: JournalRepository,
    private val cloudDraftDataSource: CloudDraftDataSource,
    private val syncMetadataService: SyncMetadataService,
    private val transactionManager: SyncTransactionManager,
    private val downloadEngine: SyncDownloadEngine,
    private val tokenRefresher: SyncTokenRefresher,
    private val mapCloudApiError: (CloudApiException) -> SyncResult,
    private val mapException: (Exception, String) -> SyncResult,
    private val downloadInbox: DownloadInbox?,
    private val space: SpaceSync? = null,
) {
    private val syncableDraftRepository = journalRepository as? SyncableDraftRepository

    private val spaceDownloader =
        SpaceDraftDownloader(
            journalRepository,
            syncMetadataService,
            transactionManager,
            downloadEngine,
            downloadInbox,
            ::saveDraft,
            ::recordPendingConflict,
            ::hasPendingLocal,
            ::checkScope,
            mapCloudApiError,
            mapException,
        )

    private class BatchTally {
        var downloadedCount = 0
        var conflictsResolved = 0
        val errors = mutableListOf<SyncError>()
    }

    suspend fun download(since: Instant): SyncResult {
        val sync = space
        val kind = sync?.activeKind(EntityType.DRAFT)
        if (sync != null && kind != null) {
            @Suppress("UNCHECKED_CAST")
            return spaceDownloader.download(sync, kind as SpaceKind<SpaceDraft>)
        }
        return downloadFromServer(since)
    }

    private suspend fun downloadFromServer(since: Instant): SyncResult =
        try {
            val downloadScope = downloadInbox?.currentScope()

            var cursor = since
            var totalDownloaded = 0
            var totalConflicts = 0
            val errors = mutableListOf<SyncError>()

            while (true) {
                val (result, batchResult) = downloadPage(cursor, downloadScope, errors)
                totalDownloaded += batchResult.downloadedCount
                totalConflicts += batchResult.conflictsResolved
                cursor = nextCursor(result, batchResult, cursor) ?: break
            }

            SyncResult(
                success = errors.isEmpty(),
                downloadedItems = totalDownloaded,
                conflictsResolved = totalConflicts,
                errors = errors,
            )
        } catch (e: CloudApiException) {
            mapCloudApiError(e)
        } catch (e: Exception) {
            throwIfDeviceBlocked(e)
            if (e is CancellationException) throw e
            mapException(e, "Download drafts")
        }

    private suspend fun saveDraft(draft: EditorDraft) {
        if (syncableDraftRepository != null) syncableDraftRepository.saveDraftFromSync(draft) else journalRepository.saveDraft(draft)
    }

    private suspend fun recordPendingConflict(
        draftId: Uuid,
        existing: EditorDraft?,
        remoteVersion: Long?,
        remoteUpdatedAt: Instant?,
    ) = downloadEngine.recordConflict(
        entityType = EntityType.DRAFT,
        entityId = draftId.toString(),
        reason = "Local pending draft changes vs remote update",
        localVersion = null,
        remoteVersion = remoteVersion,
        localUpdatedAt = existing?.lastModifiedAt,
        remoteUpdatedAt = remoteUpdatedAt,
    )

    private suspend fun downloadPage(
        cursor: Instant,
        downloadScope: DownloadScope?,
        errors: MutableList<SyncError>,
    ): Pair<DraftSyncResult, BatchResult> {
        checkScope(downloadScope)
        // Journals, notes and associations all refresh the token per page; drafts reused
        // the one captured when the run started, so a session that expired mid-sync failed
        // with a 401 that never triggered a refresh.
        val result =
            tokenRefresher
                .withFreshToken(
                    { token -> cloudDraftDataSource.getDraftChanges(token, cursor, SyncDownloader.SYNC_PAGE_SIZE) },
                    "getDraftChanges",
                ).getOrThrow()
        checkScope(downloadScope)
        reportFetchFailure(errors)

        val page = startPage(result, downloadScope)
        val batchResult = applyPage(page, downloadScope)
        errors.addAll(batchResult.errors)
        downloadEngine.repairUnreadable(
            EntityType.DRAFT,
            "draft",
            result.unreadable,
            if (result.unreadable.isEmpty()) emptySet() else journalRepository.getAllDraftsForSync().map { it.id }.toSet(),
            downloadScope,
            result.unreadableVersions,
        )

        if (result.incompatible.isNotEmpty()) {
            errors += SyncError(SyncErrorType.SERVER_ERROR, "One or more drafts use an unsupported or damaged encrypted format")
        }

        page.settleApplied(downloadScope)
        page.settleSkipped(downloadScope)
        page.settleFailed(downloadScope)
        recordRemoteFailures(result.failures, downloadScope, errors)
        return result to batchResult
    }

    /** Returns the cursor for the next page, or null once pagination should stop. */
    private suspend fun nextCursor(
        result: DraftSyncResult,
        batchResult: BatchResult,
        cursor: Instant,
    ): Instant? {
        if ((
                batchResult.errors.isNotEmpty() ||
                    result.unreadable.isNotEmpty() ||
                    result.incompatible.isNotEmpty() ||
                    result.failures.isNotEmpty()
            ) &&
            downloadInbox == null
        ) {
            return null
        }

        if (result.changes.isEmpty() &&
            result.deletions.isEmpty() &&
            result.unreadable.isEmpty() &&
            result.incompatible.isEmpty() &&
            result.failures.isEmpty() &&
            !result.hasMore
        ) {
            return null
        }

        syncMetadataService.updateLastSyncTime(EntityType.DRAFT, result.lastSyncTimestamp)

        if (!result.hasMore) {
            return null
        }

        if (result.lastSyncTimestamp <= cursor) {
            Napier.w("Draft sync pagination cursor did not advance")
            return null
        }

        return result.lastSyncTimestamp
    }

    private suspend fun reportFetchFailure(errors: MutableList<SyncError>) {
        val fetchFailure = downloadInbox?.fetchFailure("DRAFT")
        if (fetchFailure == null ||
            fetchFailure == DiagnosticReason.NONE ||
            errors.any { it.message == FETCH_FAILED_MESSAGE }
        ) {
            return
        }
        errors +=
            SyncError(
                if (fetchFailure == DiagnosticReason.SIGN_IN_REQUIRED) {
                    SyncErrorType.AUTHENTICATION_ERROR
                } else {
                    SyncErrorType.SERVER_ERROR
                },
                FETCH_FAILED_MESSAGE,
            )
    }

    /** Reads the inbox rows staged for [result] and records an apply attempt starting for each. */
    private suspend fun startPage(
        result: DraftSyncResult,
        downloadScope: DownloadScope?,
    ): DraftDownloadPage {
        val stagedRows = downloadScope?.let { downloadInbox?.pending("DRAFT", it)?.associateBy { row -> row.entityId } }.orEmpty()
        val capturedSource = downloadScope?.let { downloadInbox?.captureDiagnosticSource(it) }
        val applyAttempts =
            (result.changes.map { it.id.toString() } + result.deletions.map { it.toString() })
                .distinct()
                .mapNotNull { id -> stagedRows[id]?.let { id to Uuid.random().toString() } }
                .toMap()
        val page = DraftDownloadPage(result, stagedRows, capturedSource, applyAttempts, downloadInbox, downloadEngine)
        for ((id, attemptId) in applyAttempts) {
            page.recordApply(stagedRows.getValue(id), attemptId, DiagnosticOutcome.STARTED)
        }
        return page
    }

    private suspend fun applyPage(
        page: DraftDownloadPage,
        downloadScope: DownloadScope?,
    ): BatchResult =
        try {
            transactionManager.withTransaction {
                val tally = BatchTally()
                for (remoteDraft in page.result.changes) {
                    applyChange(remoteDraft, page, downloadScope, tally)
                }
                for (draftId in page.result.deletions) {
                    applyDeletion(draftId, page, downloadScope, tally)
                }
                BatchResult(tally.downloadedCount, tally.conflictsResolved, tally.errors)
            }
        } catch (cancelled: CancellationException) {
            for ((id, attemptId) in page.applyAttempts) {
                page.recordApply(page.stagedRows.getValue(id), attemptId, DiagnosticOutcome.INTERRUPTED)
            }
            throw cancelled
        }

    private suspend fun applyChange(
        remoteDraft: SyncedDraft,
        page: DraftDownloadPage,
        downloadScope: DownloadScope?,
        tally: BatchTally,
    ) {
        try {
            checkScope(downloadScope)
            val existingDraft = journalRepository.getDraft(remoteDraft.id)
            val pending = syncMetadataService.getPendingUploads(EntityType.DRAFT).firstOrNull { it.entityId == remoteDraft.id.toString() }
            if (pending != null) {
                // A lost response is settled only by the complete authenticated plaintext, never by ID or time alone.
                if (pending.operation != PendingOperation.DELETE &&
                    remoteDraft.richDraft != null &&
                    existingDraft == remoteDraft.richDraft &&
                    syncMetadataService.settleIfCurrent(EntityType.DRAFT, pending, remoteDraft.lastUpdated, remoteDraft.serverVersion)
                ) {
                    page.appliedIds += remoteDraft.id.toString() to remoteDraft.serverVersion
                    return
                }
                skipForPendingLocalChange(remoteDraft, existingDraft, page, tally)
                return
            }

            if (existingDraft != null && isAppliedVersion(remoteDraft, downloadScope)) {
                // The locally hydrated media links belong to this same server version.
                // Re-ack to repair a missing media queue row after an interrupted run.
                page.appliedIds += remoteDraft.id.toString() to remoteDraft.serverVersion
                return
            }

            // The outer timestamp is milliseconds; the encrypted draft preserves the edit's full precision.
            val remoteModifiedAt = remoteDraft.richDraft?.lastModifiedAt ?: remoteDraft.lastUpdated
            if (existingDraft != null && existingDraft.lastModifiedAt > remoteModifiedAt) {
                syncMetadataService.enqueuePending(
                    entityId = existingDraft.id.toString(),
                    entityType = EntityType.DRAFT,
                    operation = PendingOperation.UPDATE,
                )
                tally.conflictsResolved++
                Napier.w("Preserving newer local draft over older remote draft")
                page.skippedIds += remoteDraft.id.toString() to remoteDraft.serverVersion
                return
            }

            saveRemoteDraft(remoteDraft, existingDraft, page, downloadScope, tally)
        } catch (e: Exception) {
            throwIfDeviceBlocked(e)
            if (e is CancellationException || isScopeChanged(downloadScope)) throw e
            page.failedIds += remoteDraft.id.toString() to remoteDraft.serverVersion
            tally.errors.add(SyncError(SyncErrorType.UNKNOWN_ERROR, "Failed to apply remote draft", cause = e))
            Napier.e("Failed to apply a remote draft: ${e::class.simpleName}")
        }
    }

    private suspend fun skipForPendingLocalChange(
        remoteDraft: SyncedDraft,
        existingDraft: EditorDraft?,
        page: DraftDownloadPage,
        tally: BatchTally,
    ) {
        tally.conflictsResolved++
        Napier.w("Skipping draft update for due to local pending changes")
        downloadEngine.recordConflict(
            entityType = EntityType.DRAFT,
            entityId = remoteDraft.id.toString(),
            reason = "Local pending draft changes vs remote update",
            localVersion = null,
            remoteVersion = remoteDraft.serverVersion,
            localUpdatedAt = existingDraft?.lastModifiedAt,
            remoteUpdatedAt = remoteDraft.lastUpdated,
        )
        page.skippedIds += remoteDraft.id.toString() to remoteDraft.serverVersion
    }

    private suspend fun saveRemoteDraft(
        remoteDraft: SyncedDraft,
        existingDraft: EditorDraft?,
        page: DraftDownloadPage,
        downloadScope: DownloadScope?,
        tally: BatchTally,
    ) {
        val draft = remoteDraft.toEditorDraft()
        if (existingDraft != draft) {
            if (syncableDraftRepository != null) {
                syncableDraftRepository.saveDraftFromSync(draft)
            } else {
                journalRepository.saveDraft(draft)
            }
        }
        checkScope(downloadScope)
        tally.downloadedCount++
        page.appliedIds += draft.id.toString() to remoteDraft.serverVersion
    }

    private suspend fun applyDeletion(
        draftId: Uuid,
        page: DraftDownloadPage,
        downloadScope: DownloadScope?,
        tally: BatchTally,
    ) {
        try {
            checkScope(downloadScope)
            val version = page.result.deletionVersions[draftId] ?: page.stagedRows[draftId.toString()]?.version ?: 0L
            val pending = syncMetadataService.getPendingUploads(EntityType.DRAFT).firstOrNull { it.entityId == draftId.toString() }
            if (pending != null && pending.operation != PendingOperation.DELETE) {
                downloadEngine.preserveDeletionConflict(EntityType.DRAFT, draftId.toString())
                tally.conflictsResolved++
            } else if (pending != null) {
                check(
                    syncMetadataService.settleIfCurrent(
                        EntityType.DRAFT,
                        pending,
                        kotlin.time.Clock.System
                            .now(),
                        version,
                    ),
                )
            }

            if (syncableDraftRepository != null) {
                syncableDraftRepository.deleteDraftFromSync(draftId)
            } else {
                journalRepository.deleteDraft(draftId)
            }
            checkScope(downloadScope)
            tally.downloadedCount++
            page.appliedIds += draftId.toString() to version
        } catch (e: Exception) {
            throwIfDeviceBlocked(e)
            if (e is CancellationException || isScopeChanged(downloadScope)) throw e
            page.failedIds += draftId.toString() to (page.result.deletionVersions[draftId] ?: 0L)
            tally.errors.add(SyncError(SyncErrorType.UNKNOWN_ERROR, "Failed to delete remote draft", cause = e))
            Napier.e("Failed to delete a remote draft: ${e::class.simpleName}")
        }
    }

    private suspend fun recordRemoteFailures(
        failures: List<RemoteRecordFailure>,
        downloadScope: DownloadScope?,
        errors: MutableList<SyncError>,
    ) {
        for (failure in failures) {
            if (isScopeChanged(downloadScope)) throw CancellationException("Download scope changed")
            if (downloadScope != null) {
                downloadInbox?.failed("DRAFT", failure.entityId, failure.reason.name, failure.serverVersion, downloadScope)
            }
            errors += SyncError(SyncErrorType.UNKNOWN_ERROR, "Remote draft remains queued for recovery")
        }
    }

    private suspend fun hasPendingLocal(draftId: Uuid): Boolean =
        syncMetadataService
            .getPendingUploads(EntityType.DRAFT)
            .any { it.entityId == draftId.toString() }

    private suspend fun isAppliedVersion(
        remoteDraft: SyncedDraft,
        downloadScope: DownloadScope?,
    ): Boolean =
        downloadScope != null &&
            downloadInbox?.isAppliedVersion("DRAFT", remoteDraft.id.toString(), remoteDraft.serverVersion, downloadScope) == true

    private fun isScopeChanged(downloadScope: DownloadScope?): Boolean =
        downloadScope != null && downloadScope != downloadInbox?.currentScope()

    private fun checkScope(downloadScope: DownloadScope?) {
        if (isScopeChanged(downloadScope)) error("Download scope changed")
    }

    private fun SyncedDraft.toEditorDraft(): EditorDraft =
        richDraft ?: EditorDraft(
            id = id,
            blocks =
                content
                    .takeIf { it.isNotBlank() }
                    ?.let {
                        listOf(
                            SerializableTextBlock(
                                id = id,
                                timestamp = createdAt,
                                content = it,
                            ),
                        )
                    }.orEmpty(),
            selectedJournalIds = journalIds,
            createdAt = createdAt,
            lastModifiedAt = lastUpdated,
        )

    private companion object {
        const val FETCH_FAILED_MESSAGE = "Remote draft fetch failed; saved drafts are still being recovered"
    }
}
