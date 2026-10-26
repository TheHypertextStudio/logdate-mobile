package app.logdate.client.sync

import app.logdate.client.database.entities.sync.DownloadInboxEntity
import app.logdate.client.repository.journals.JournalContentRepository
import app.logdate.client.repository.journals.SyncableJournalContentRepository
import app.logdate.client.sync.cloud.AssociationSyncResult
import app.logdate.client.sync.cloud.CloudApiException
import app.logdate.client.sync.cloud.CloudAssociationDataSource
import app.logdate.client.sync.cloud.JournalContentAssociation
import app.logdate.client.sync.cloud.RemoteRecordFailure
import app.logdate.client.sync.diagnostics.DiagnosticSource
import app.logdate.client.sync.metadata.AssociationPendingKey
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.SyncMetadataService
import app.logdate.client.sync.recovery.DownloadInbox
import app.logdate.client.sync.recovery.DownloadScope
import app.logdate.shared.model.diagnostics.DiagnosticAction
import app.logdate.shared.model.diagnostics.DiagnosticOutcome
import app.logdate.shared.model.diagnostics.DiagnosticPhase
import app.logdate.shared.model.diagnostics.DiagnosticReason
import app.logdate.shared.model.diagnostics.SyncDiagnosticEvent
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The association half of [SyncDownloader]: pages through remote journal-entry link additions and
 * removals and applies each one in its own transaction, recording a conflict instead whenever the
 * same link has a local change still waiting to upload.
 */
internal class AssociationDownloader(
    private val journalContentRepository: JournalContentRepository,
    private val cloudAssociationDataSource: CloudAssociationDataSource,
    private val syncMetadataService: SyncMetadataService,
    private val transactionManager: SyncTransactionManager,
    private val downloadEngine: SyncDownloadEngine,
    private val tokenRefresher: SyncTokenRefresher,
    private val mapCloudApiError: (CloudApiException) -> SyncResult,
    private val mapException: (Exception, String) -> SyncResult,
    private val downloadInbox: DownloadInbox?,
) {
    private val syncable = journalContentRepository as? SyncableJournalContentRepository

    private class Tally {
        var downloaded = 0
        var conflicts = 0
        val errors = mutableListOf<SyncError>()
    }

    private class PageContext(
        val selected: DownloadScope?,
        val capturedSource: DiagnosticSource?,
        val tally: Tally,
    )

    suspend fun download(since: Instant): SyncResult =
        try {
            val selected = downloadInbox?.currentScope()
            syncMetadataService.getPendingUploads(EntityType.ASSOCIATION)
            var cursor = downloadInbox?.cursor("ASSOCIATION")?.let(Instant::fromEpochMilliseconds) ?: since
            val tally = Tally()
            while (true) {
                val page =
                    tokenRefresher
                        .withFreshToken(
                            { token ->
                                cloudAssociationDataSource.getAssociationChanges(token, cursor, SyncDownloader.SYNC_PAGE_SIZE)
                            },
                            "getAssociationChanges",
                        ).getOrThrow()
                applyPage(page, selected, tally)
                if (tally.errors.isNotEmpty() && downloadInbox == null) break
                syncMetadataService.updateLastSyncTime(EntityType.ASSOCIATION, page.lastSyncTimestamp)
                if (!page.hasMore || page.lastSyncTimestamp <= cursor) break
                cursor = page.lastSyncTimestamp
            }
            SyncResult(
                success = tally.errors.isEmpty(),
                downloadedItems = tally.downloaded,
                conflictsResolved = tally.conflicts,
                errors = tally.errors,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: CloudApiException) {
            mapCloudApiError(error)
        } catch (error: Exception) {
            mapException(error, "Download associations")
        }

    private suspend fun applyPage(
        page: AssociationSyncResult,
        selected: DownloadScope?,
        tally: Tally,
    ) {
        val fetchFailure = downloadInbox?.fetchFailure("ASSOCIATION")
        if (fetchFailure != null && fetchFailure != DiagnosticReason.NONE) {
            tally.errors += SyncError(SyncErrorType.SERVER_ERROR, "Remote fetch failed; saved relationships are still being recovered")
        }
        val rows = downloadInbox?.pending("ASSOCIATION").orEmpty().associateBy { it.entityId }
        val context = PageContext(selected, selected?.let { downloadInbox?.captureDiagnosticSource(it) }, tally)
        for ((association, deletion) in page.additions.map { it to false } + page.deletions.map { it to true }) {
            val key = AssociationPendingKey(association.journalId, association.contentId).toPendingId()
            applyAssociation(association, deletion, key, rows[key], context)
        }
        recordRemoteFailures(page.failures, selected, tally.errors)
    }

    private suspend fun applyAssociation(
        association: JournalContentAssociation,
        deletion: Boolean,
        key: String,
        row: DownloadInboxEntity?,
        context: PageContext,
    ) {
        val attemptId = row?.let { Uuid.random().toString() }
        recordApply(row, attemptId, context, DiagnosticOutcome.STARTED)
        try {
            val hasPending = applyInTransaction(association, deletion, key, row, context.selected)
            if (hasPending) {
                recordApply(
                    row,
                    attemptId,
                    context,
                    DiagnosticOutcome.CONFLICT,
                    DiagnosticReason.CONFLICT,
                    DiagnosticAction.REVIEW_CONFLICT,
                )
            } else {
                recordApply(row, attemptId, context, DiagnosticOutcome.SUCCEEDED)
            }
            if (hasPending) context.tally.conflicts++ else context.tally.downloaded++
        } catch (cancelled: CancellationException) {
            recordApply(row, attemptId, context, DiagnosticOutcome.INTERRUPTED)
            throw cancelled
        } catch (error: Exception) {
            throwIfDeviceBlocked(error)
            if (row != null && context.selected != null) {
                downloadInbox?.failed(
                    "ASSOCIATION",
                    key,
                    DiagnosticReason.LOCAL_STORAGE.name,
                    row.version,
                    context.selected,
                    attemptId = attemptId,
                    source = context.capturedSource,
                )
            }
            context.tally.errors += SyncError(SyncErrorType.UNKNOWN_ERROR, "Failed to apply remote relationship")
            Napier.e("Failed to apply remote relationship")
        }
    }

    /** Returns true when a local pending change wins and the remote one is recorded as a conflict. */
    private suspend fun applyInTransaction(
        association: JournalContentAssociation,
        deletion: Boolean,
        key: String,
        row: DownloadInboxEntity?,
        selected: DownloadScope?,
    ): Boolean =
        transactionManager.withTransaction {
            ensureScope(selected)
            val pending = syncMetadataService.getPendingUploads(EntityType.ASSOCIATION).firstOrNull { it.entityId == key }
            val hasPending = pending != null
            val matchingDelete = deletion && pending?.operation == app.logdate.client.sync.metadata.PendingOperation.DELETE
            if (matchingDelete) {
                check(
                    syncMetadataService.settleIfCurrent(
                        EntityType.ASSOCIATION,
                        requireNotNull(pending),
                        kotlin.time.Clock.System
                            .now(),
                        0L,
                    ),
                )
            } else if (hasPending && deletion) {
                downloadEngine.preserveDeletionConflict(EntityType.ASSOCIATION, key)
            } else if (hasPending) {
                downloadEngine.recordConflict(
                    EntityType.ASSOCIATION,
                    key,
                    "Local pending changes vs remote relationship",
                    null,
                    association.syncVersion,
                    null,
                    null,
                )
            }
            if (deletion) {
                if (syncable != null) {
                    syncable.removeContentFromJournalFromSync(association.contentId, association.journalId)
                } else {
                    journalContentRepository.removeContentFromJournal(association.contentId, association.journalId)
                }
            } else if (!hasPending) {
                if (syncable != null) {
                    syncable.addContentToJournalFromSync(association.contentId, association.journalId)
                } else {
                    journalContentRepository.addContentToJournal(association.contentId, association.journalId)
                }
            }
            ensureScope(selected)
            if (row != null && selected != null) downloadInbox?.applied("ASSOCIATION", key, row.version, selected)
            hasPending && !matchingDelete
        }

    private suspend fun recordRemoteFailures(
        failures: List<RemoteRecordFailure>,
        selected: DownloadScope?,
        errors: MutableList<SyncError>,
    ) {
        for (failure in failures) {
            ensureScope(selected)
            if (selected != null) {
                downloadInbox?.failed("ASSOCIATION", failure.entityId, failure.reason.name, failure.serverVersion, selected)
            }
            errors += SyncError(SyncErrorType.UNKNOWN_ERROR, "Remote relationship remains queued for recovery")
        }
    }

    private fun ensureScope(selected: DownloadScope?) {
        if (selected != null && selected != downloadInbox?.currentScope()) throw CancellationException("Download scope changed")
    }

    private fun recordApply(
        row: DownloadInboxEntity?,
        attemptId: String?,
        context: PageContext,
        outcome: DiagnosticOutcome,
        reason: DiagnosticReason = DiagnosticReason.NONE,
        action: DiagnosticAction = DiagnosticAction.NONE,
    ) {
        if (row == null || attemptId == null) return
        downloadInbox?.recordDiagnostic(
            SyncDiagnosticEvent(
                DiagnosticPhase.APPLY,
                outcome,
                reason = reason,
                action = action,
                operationId = row.operationId,
                attemptId = attemptId,
            ),
            context.capturedSource,
        )
    }
}
