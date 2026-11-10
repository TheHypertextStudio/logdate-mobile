package app.logdate.client.sync

import app.logdate.client.database.entities.sync.DownloadInboxEntity
import app.logdate.client.sync.diagnostics.DiagnosticSource
import app.logdate.client.sync.metadata.AssociationPendingKey
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.PendingOperation
import app.logdate.client.sync.metadata.PendingUpload
import app.logdate.client.sync.metadata.SyncMetadataService
import app.logdate.client.sync.recovery.DownloadInbox
import app.logdate.client.sync.recovery.DownloadScope
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** What remote deletion should do with a local item. */
internal enum class DeletionDecision { PRESERVE, DELETE }

/**
 * Local state that determines whether a remote tombstone may remove an item.
 *
 * @property localExists Whether the item is still present in local storage.
 * @property pendingOperation The item's queued upload, or null when nothing is queued.
 * @property hasRelatedEdits Whether a queued membership change still references the item.
 * @property localSyncVersion The sync version of the local copy; unused when [localExists] is false.
 * @property remoteVersion The tombstone's version from the download inbox, or null when unknown.
 */
internal data class DeletionFacts(
    val localExists: Boolean,
    val pendingOperation: PendingOperation?,
    val hasRelatedEdits: Boolean,
    val localSyncVersion: Long,
    val remoteVersion: Long?,
)

/**
 * Decides whether a remote deletion must keep a local copy of the item as a conflict.
 *
 * A local item survives as a conflict when it carries unsent work (a create or update, or a
 * membership edit) or is newer than the tombstone. A queued local delete agrees with the remote one.
 */
internal fun decideRemoteDeletion(facts: DeletionFacts): DeletionDecision {
    if (!facts.localExists) return DeletionDecision.DELETE
    val hasPendingEdit = facts.pendingOperation?.let { it != PendingOperation.DELETE } == true
    val newerThanTombstone = facts.remoteVersion != null && facts.localSyncVersion > facts.remoteVersion
    return if (hasPendingEdit || facts.hasRelatedEdits || newerThanTombstone) {
        DeletionDecision.PRESERVE
    } else {
        DeletionDecision.DELETE
    }
}

/**
 * Read-only inputs shared by every deletion in one downloaded page.
 *
 * @property rows Download inbox rows for the page, keyed by entity id.
 * @property scope The inbox scope the page belongs to; deletion aborts if the active scope changes.
 * @property source Diagnostic source attributed to the emitted events.
 * @property initialSkippedIds Ids whose media work was already skipped before applying deletions.
 */
internal data class DownloadDeletionContext(
    val rows: Map<String, DownloadInboxEntity>,
    val scope: DownloadScope?,
    val source: DiagnosticSource?,
    val initialSkippedIds: Set<String> = emptySet(),
)

/**
 * Outcome of applying a page of remote deletions.
 *
 * @property batch Counts and errors to fold into the page totals.
 * @property appliedIds Ids whose deletion committed.
 * @property skippedIds Ids whose conflicting local edits were preserved and skipped.
 * @property failedIds Ids whose deletion failed and should be reported back to the inbox.
 */
internal data class DeletionPageResult(
    val batch: BatchResult,
    val appliedIds: Set<String>,
    val skippedIds: Set<String>,
    val failedIds: Set<String>,
)

/** Applies remote tombstones for a page only after conflicting edits and membership work have been retained. */
internal suspend fun <T : Any> SyncDownloadEngine.applyRemoteDeletions(
    strategy: DownloadStrategy<T>,
    ids: List<Uuid>,
    context: DownloadDeletionContext,
): DeletionPageResult {
    val appliedIds = mutableSetOf<String>()
    val skippedIds = context.initialSkippedIds.toMutableSet()
    val failedIds = mutableSetOf<String>()
    var downloaded = 0
    var conflicts = 0
    val errors = mutableListOf<SyncError>()
    for (id in ids) {
        val row = context.rows[id.toString()]
        val attemptId = if (row != null) Uuid.random().toString() else null
        recordDiagnostics.started(row, attemptId, context.source)
        val downloadedBefore = downloaded
        val conflictsBefore = conflicts
        try {
            val preserved = applySingleDeletion(strategy, id, context)
            if (preserved) {
                conflicts++
                skippedIds += id.toString()
            }
            downloaded++
            appliedIds += id.toString()
            recordDiagnostics.completed(row, attemptId, context.source, id.toString() in skippedIds)
        } catch (error: Exception) {
            throwIfDeviceBlocked(error)
            downloaded = downloadedBefore
            conflicts = conflictsBefore
            if (error is CancellationException) {
                recordDiagnostics.interrupted(row, attemptId, context.source)
                throw error
            }
            recordDiagnostics.failed(row, attemptId, context.source)
            failedIds += id.toString()
            if (strategy.settle != null) strategy.settleFailed(id)
            errors += SyncError(SyncErrorType.UNKNOWN_ERROR, "Failed to apply remote deletion", cause = error)
            Napier.e("Failed to apply remote deletion: ${error::class.simpleName}")
        }
    }
    return DeletionPageResult(
        batch = BatchResult(downloaded, conflicts, errors),
        appliedIds = appliedIds,
        skippedIds = skippedIds,
        failedIds = failedIds,
    )
}

private suspend fun <T : Any> SyncDownloadEngine.applySingleDeletion(
    strategy: DownloadStrategy<T>,
    id: Uuid,
    context: DownloadDeletionContext,
): Boolean =
    transactionManager.withTransaction {
        ensureScope(context.scope)
        val existing = strategy.localItem(id)
        val version = context.rows[id.toString()]?.version
        val pending = syncMetadataService.getPendingUploads(strategy.entityType).firstOrNull { it.entityId == id.toString() }
        val memberships = relatedMemberships(syncMetadataService, strategy.entityType, id.toString())
        val preserve =
            decideRemoteDeletion(
                DeletionFacts(
                    localExists = existing != null,
                    pendingOperation = pending?.operation,
                    hasRelatedEdits = memberships.any { it.operation != PendingOperation.DELETE },
                    localSyncVersion = existing?.let(strategy.syncVersionOf) ?: 0L,
                    remoteVersion = version,
                ),
            ) == DeletionDecision.PRESERVE
        if (preserve) {
            preserveDeletionConflict(strategy.entityType, id.toString())
        } else if (pending != null) {
            check(syncMetadataService.settleIfCurrent(strategy.entityType, pending, Clock.System.now(), version ?: 0L))
        }
        // Re-read: preserveDeletionConflict already settled the memberships it retained.
        for (membership in relatedMemberships(syncMetadataService, strategy.entityType, id.toString())) {
            check(syncMetadataService.settleIfCurrent(EntityType.ASSOCIATION, membership, Clock.System.now(), 0L))
        }
        strategy.applyDelete(id)
        strategy.afterDelete(id)
        Napier.d("Remote deletion applied")
        ensureScope(context.scope)
        strategy.settle?.invoke(id, RemoteOutcome.DELETED)
        context.rows[id.toString()]?.let { row ->
            downloadInbox?.applied(strategy.entityType.name, row.entityId, row.version, requireNotNull(context.scope), enqueueMedia = false)
        }
        ensureScope(context.scope)
        preserve
    }

/** Retains a local edit whose remote counterpart was deleted, and settles its pending uploads. */
internal suspend fun preserveRemoteDeletionConflict(
    transactions: SyncTransactionManager,
    metadata: SyncMetadataService,
    retainDeletedEdit: suspend (EntityType, String) -> Unit,
    entityType: EntityType,
    entityId: String,
) = transactions.withTransaction {
    val pending = metadata.getPendingUploads(entityType).firstOrNull { it.entityId == entityId }
    val memberships = relatedMemberships(metadata, entityType, entityId)
    retainDeletedEdit(entityType, entityId)
    for (membership in memberships) {
        check(metadata.settleIfCurrent(EntityType.ASSOCIATION, membership, Clock.System.now(), 0L))
    }
    if (pending != null) {
        check(metadata.settleIfCurrent(entityType, pending, Clock.System.now(), 0L)) { "The edit changed while preserving it" }
    }
}

private suspend fun relatedMemberships(
    metadata: SyncMetadataService,
    type: EntityType,
    id: String,
): List<PendingUpload> {
    if (type != EntityType.NOTE && type != EntityType.JOURNAL) return emptyList()
    return metadata.getPendingUploads(EntityType.ASSOCIATION).filter { pending ->
        AssociationPendingKey.fromPendingId(pending.entityId)?.let {
            if (type == EntityType.NOTE) it.contentId.toString() == id else it.journalId.toString() == id
        } == true
    }
}
