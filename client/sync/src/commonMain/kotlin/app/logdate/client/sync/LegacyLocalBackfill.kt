package app.logdate.client.sync

import app.logdate.client.repository.journals.JournalContentRepository
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.journals.JournalRepository
import app.logdate.client.sync.metadata.AssociationPendingKey
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.FirstSyncEnqueueStore
import app.logdate.client.sync.metadata.SyncMetadataService
import app.logdate.client.sync.recovery.DownloadInbox
import kotlinx.coroutines.flow.first

/** Account-bound inventory of readable local legacy records, preserving pending user operations. */
internal class LegacyLocalBackfill(
    private val downloadInbox: DownloadInbox?,
    private val firstSyncEnqueueStore: FirstSyncEnqueueStore,
    private val journalRepository: JournalRepository,
    private val journalNotesRepository: JournalNotesRepository,
    private val journalContentRepository: JournalContentRepository,
    private val syncMetadataService: SyncMetadataService,
) {
    suspend fun enqueueMembershipsIfNeeded() {
        val inbox = downloadInbox ?: return
        val selected = inbox.currentScope()
        if (firstSyncEnqueueStore.hasEnqueuedAssociationScope(selected.owner, selected.origin)) return
        val ids = journalNotesRepository.allNotesObserved.first().map { it.uid }
        for (chunk in ids.chunked(500)) {
            val memberships = journalContentRepository.observeJournalsForContents(chunk.toSet()).first()
            for ((contentId, journals) in memberships) {
                for (journal in journals) {
                    check(selected == inbox.currentScope()) { "Download scope changed" }
                    val id = AssociationPendingKey(journal.id, contentId).toPendingId()
                    syncMetadataService.enqueueRepairIfAbsent(id, EntityType.ASSOCIATION, serverOrigin = selected.origin)
                }
            }
        }
        check(selected == inbox.currentScope()) { "Download scope changed" }
        firstSyncEnqueueStore.markEnqueuedAssociationScope(selected.owner, selected.origin)
    }

    /** Recover unqueued local records absent from the completed cloud inventory. */
    suspend fun enqueueRecordsIfNeeded(entityType: EntityType) {
        val inbox = downloadInbox ?: return
        val selected = inbox.currentScope()
        if (!firstSyncEnqueueStore.hasAuditedLegacyScope(selected.owner, selected.origin)) return
        if (firstSyncEnqueueStore.hasEnqueuedLocalScope(selected.owner, selected.origin, entityType)) return
        val ids =
            when (entityType) {
                EntityType.JOURNAL -> {
                    journalRepository.allJournalsObserved
                        .first()
                        .map { it.id }
                }

                EntityType.NOTE -> {
                    journalNotesRepository.allNotesObserved
                        .first()
                        .map { it.uid }
                }

                else -> {
                    return
                }
            }
        for (id in ids) {
            check(selected == inbox.currentScope()) { "Download scope changed" }
            // Any observed cloud version, including a tombstone or unreadable record, already
            // has its own apply/repair path. Backfill must never resurrect or blindly replace it.
            if (!inbox.hasRecord(entityType.name, id.toString(), selected)) {
                syncMetadataService.enqueueCreateIfAbsent(id.toString(), entityType)
            }
        }
        check(selected == inbox.currentScope()) { "Download scope changed" }
        firstSyncEnqueueStore.markEnqueuedLocalScope(selected.owner, selected.origin, entityType)
    }
}
