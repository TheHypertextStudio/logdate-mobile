package app.logdate.client.sync.space.mappers

import app.logdate.client.repository.journals.JournalMergeOperation
import app.logdate.client.repository.journals.SyncableJournalRepository
import app.logdate.client.sync.conflict.ConflictResolution
import app.logdate.client.sync.conflict.ConflictResolver
import app.logdate.client.sync.conflict.DeletionResolution
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.space.SpaceKind
import app.logdate.client.sync.space.SpaceLocalRecords
import app.logdate.client.sync.space.SpaceMediaResolver
import app.logdate.shared.model.Journal
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Journals as a kind of the personal space: one `app.logdate.journal.journal` record each.
 *
 * A record that says its journal was merged into another one is applied as a merge on this device
 * (see [SpaceJournal]); it never becomes a journal the person can see.
 *
 * @param journals the stored journals, written without queueing another upload.
 * @param resolver decides between this device's journal and another device's when both changed.
 * @param media connects each journal's cover with the blob in the personal space.
 */
fun journalSpaceKind(
    journals: SyncableJournalRepository,
    resolver: ConflictResolver<Journal>,
    media: SpaceMediaResolver = SpaceMediaResolver.Default,
): SpaceKind<SpaceJournal> =
    SpaceKind.single(
        entityType = EntityType.JOURNAL,
        mapper = JournalRecordMapper(media),
        local = StoredJournals(journals, JournalOrMerge(resolver)),
        withSyncVersion = { item, version -> item.copy(journal = item.journal.copy(syncVersion = version)) },
    )

/** Settles two journals with the journal resolver, and prefers a merge made elsewhere to an edit made here. */
private class JournalOrMerge(
    private val journals: ConflictResolver<Journal>,
) : ConflictResolver<SpaceJournal> {
    override fun resolve(
        local: SpaceJournal,
        remote: SpaceJournal,
        localTimestamp: Instant,
        remoteTimestamp: Instant,
    ): ConflictResolution<SpaceJournal> {
        if (remote.mergedInto != null) return ConflictResolution.KeepRemote(remote)
        if (local.mergedInto != null) return ConflictResolution.KeepLocal(local)
        return when (val resolution = journals.resolve(local.journal, remote.journal, localTimestamp, remoteTimestamp)) {
            is ConflictResolution.KeepLocal -> {
                ConflictResolution.KeepLocal(SpaceJournal(resolution.value))
            }

            is ConflictResolution.KeepRemote -> {
                ConflictResolution.KeepRemote(SpaceJournal(resolution.value))
            }

            is ConflictResolution.Merge -> {
                ConflictResolution.Merge(SpaceJournal(resolution.merged))
            }

            is ConflictResolution.RequiresManualResolution -> {
                ConflictResolution.RequiresManualResolution(
                    SpaceJournal(resolution.local),
                    SpaceJournal(resolution.remote),
                    resolution.reason,
                )
            }
        }
    }

    override fun resolveDeletion(
        remote: SpaceJournal,
        remoteTimestamp: Instant,
    ): DeletionResolution =
        if (remote.mergedInto != null) DeletionResolution.DELETE else journals.resolveDeletion(remote.journal, remoteTimestamp)
}

private class StoredJournals(
    private val journals: SyncableJournalRepository,
    override val conflictResolver: ConflictResolver<SpaceJournal>,
) : SpaceLocalRecords<SpaceJournal> {
    /**
     * The journal with this id, or the merge that retired it. The repository answers for a retired
     * journal with the journal it was merged into, so a merge this device hasn't sent yet is
     * looked for first, and nothing else is read as another journal's record.
     */
    override suspend fun load(entityId: String): SpaceJournal? {
        val id = Uuid.parseOrNull(entityId) ?: return null
        journals.pendingJournalMerges().firstOrNull { it.sourceId == id }?.let { return mergedSource(it) }
        return journals.getJournalById(id)?.takeIf { it.id == id }?.let { SpaceJournal(it) }
    }

    private suspend fun mergedSource(merge: JournalMergeOperation): SpaceJournal {
        val source = merge.source ?: Journal(id = merge.sourceId, title = merge.sourceTitle)
        val survivor = journals.resolveJournalId(merge.sourceId).takeIf { it != merge.sourceId } ?: merge.destinationId
        return SpaceJournal(source, mergedInto = survivor)
    }

    override fun lastUpdatedOf(item: SpaceJournal): Instant = item.journal.lastUpdated

    override suspend fun acknowledgeUpload(
        item: SpaceJournal,
        seq: Long,
        syncedAt: Instant,
    ) {
        if (item.mergedInto != null) return
        journals.updateSyncMetadata(item.journal.id, seq, syncedAt)
    }

    override suspend fun replaceLocal(item: SpaceJournal) {
        val journal = item.journal
        val survivor = item.mergedInto
        if (survivor != null) {
            journals.applyJournalRedirect(journal.id, survivor)
        } else if (journals.getJournalById(journal.id) == null) {
            journals.createFromSync(journal)
        } else {
            journals.updateFromSync(journal)
        }
    }
}
