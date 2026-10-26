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

internal class DownloadDeletionContext(
    val rows: Map<String, DownloadInboxEntity>,
    val scope: DownloadScope?,
    val source: DiagnosticSource?,
    val appliedIds: MutableSet<String>,
    val skippedIds: Set<String>,
    val failedIds: MutableSet<String>,
)

/** Applies tombstones only after conflicting edits and membership work have been retained. */
internal class SyncDownloadDeletionApplier(
    private val transactions: SyncTransactionManager,
    private val metadata: SyncMetadataService,
    private val inbox: DownloadInbox?,
    private val ensureScope: (DownloadScope?) -> Unit,
    private val retainDeletedEdit: suspend (EntityType, String) -> Unit,
    private val diagnostics: SyncDownloadRecordDiagnostics,
) {
    suspend fun <T : Any> apply(
        strategy: DownloadStrategy<T>,
        ids: List<Uuid>,
        localById: MutableMap<Uuid, T>,
        context: DownloadDeletionContext,
    ): BatchResult {
        var downloaded = 0
        var conflicts = 0
        val errors = mutableListOf<SyncError>()
        for (id in ids) {
            val row = context.rows[id.toString()]
            val attemptId = if (row != null) Uuid.random().toString() else null
            diagnostics.started(row, attemptId, context.source)
            val previous = localById[id]
            val downloadedBefore = downloaded
            val conflictsBefore = conflicts
            try {
                if (applyDeletion(strategy, id, localById, context)) conflicts++
                downloaded++
                context.appliedIds += id.toString()
                diagnostics.completed(row, attemptId, context.source, id.toString() in context.skippedIds)
            } catch (error: Exception) {
                throwIfDeviceBlocked(error)
                if (previous == null) localById.remove(id) else localById[id] = previous
                downloaded = downloadedBefore
                conflicts = conflictsBefore
                if (error is CancellationException) {
                    diagnostics.interrupted(row, attemptId, context.source)
                    throw error
                }
                diagnostics.failed(row, attemptId, context.source)
                context.failedIds += id.toString()
                errors += SyncError(SyncErrorType.UNKNOWN_ERROR, "Failed to apply remote deletion")
                Napier.e("Failed to apply remote deletion")
            }
        }
        return BatchResult(downloaded, conflicts, errors)
    }

    private suspend fun <T : Any> applyDeletion(
        strategy: DownloadStrategy<T>,
        id: Uuid,
        localById: MutableMap<Uuid, T>,
        context: DownloadDeletionContext,
    ): Boolean =
        transactions.withTransaction {
            ensureScope(context.scope)
            val existing = strategy.localItem(id)
            val version = context.rows[id.toString()]?.version
            val pending = metadata.getPendingUploads(strategy.entityType).firstOrNull { it.entityId == id.toString() }
            val relatedEdits = relatedMemberships(strategy.entityType, id.toString()).any { it.operation != PendingOperation.DELETE }
            val preserve =
                existing != null &&
                    (
                        pending?.operation?.let { it != PendingOperation.DELETE } == true ||
                            relatedEdits ||
                            (version != null && strategy.syncVersionOf(existing) > version)
                    )
            if (preserve) {
                preserveConflict(strategy.entityType, id.toString())
            } else if (pending != null) {
                check(metadata.settleIfCurrent(strategy.entityType, pending, Clock.System.now(), version ?: 0L))
            }
            for (membership in relatedMemberships(strategy.entityType, id.toString())) {
                check(metadata.settleIfCurrent(EntityType.ASSOCIATION, membership, Clock.System.now(), 0L))
            }
            strategy.applyDelete(id)
            strategy.afterDelete(id)
            localById.remove(id)
            Napier.d("Remote deletion applied")
            ensureScope(context.scope)
            context.rows[id.toString()]?.let { row ->
                inbox?.applied(strategy.entityType.name, row.entityId, row.version, requireNotNull(context.scope), enqueueMedia = false)
            }
            ensureScope(context.scope)
            preserve
        }

    suspend fun preserveConflict(
        entityType: EntityType,
        entityId: String,
    ) = transactions.withTransaction {
        val pending = metadata.getPendingUploads(entityType).firstOrNull { it.entityId == entityId }
        val memberships = relatedMemberships(entityType, entityId)
        retainDeletedEdit(entityType, entityId)
        for (membership in memberships) {
            check(metadata.settleIfCurrent(EntityType.ASSOCIATION, membership, Clock.System.now(), 0L))
        }
        if (pending != null) {
            check(metadata.settleIfCurrent(entityType, pending, Clock.System.now(), 0L)) { "The edit changed while preserving it" }
        }
    }

    private suspend fun relatedMemberships(
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
}
