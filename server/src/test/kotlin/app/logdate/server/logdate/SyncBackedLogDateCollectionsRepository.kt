package app.logdate.server.logdate

import app.logdate.server.sync.AssociationDeletionMarker
import app.logdate.server.sync.AssociationKey
import app.logdate.server.sync.AssociationRecord
import app.logdate.server.sync.ChangeSet
import app.logdate.server.sync.ContentDeletionMarker
import app.logdate.server.sync.ContentRecord
import app.logdate.server.sync.JournalDeletionMarker
import app.logdate.server.sync.JournalRecord
import app.logdate.server.sync.SyncRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Sync-backed implementation of [LogDateCollectionsRepository].
 *
 * This keeps sync transport details behind a LogDate-owned boundary so the rest of the server can
 * speak in LogDate collection terms instead of sync DTOs.
 */
class SyncBackedLogDateCollectionsRepository(
    private val syncRepository: SyncRepository,
) : LogDateCollectionsRepository {
    private val creationMutex = Mutex()

    override suspend fun status(userId: UUID): LogDateCollectionsStatus =
        syncRepository.status(userId).let { status ->
            LogDateCollectionsStatus(
                entryCount = status.contentCount,
                journalCount = status.journalCount,
                associationCount = status.associationCount,
                lastTimestamp = status.lastTimestamp,
            )
        }

    override suspend fun listEntries(userId: UUID): List<LogDateEntry> =
        loadAllEntries(
            userId = userId,
            changes = syncRepository::contentChanges,
        )

    override suspend fun upsertEntry(
        userId: UUID,
        entry: LogDateEntry,
    ): LogDateEntry = creationMutex.withLock { writeEntry(userId, entry) }

    override suspend fun createEntryIfAbsent(
        userId: UUID,
        entry: LogDateEntry,
        respectTombstones: Boolean,
    ): ConditionalCreate<LogDateEntry> =
        creationMutex.withLock {
            entryState(userId, entry.id).createUnlessTaken(respectTombstones) { writeEntry(userId, entry) }
        }

    private fun writeEntry(
        userId: UUID,
        entry: LogDateEntry,
    ): LogDateEntry =
        syncRepository
            .upsertContent(
                userId = userId,
                record =
                    ContentRecord(
                        id = entry.id,
                        type = entry.type,
                        content = entry.content,
                        mediaUri = entry.mediaUri,
                        durationMs = entry.durationMs,
                        createdAt = entry.createdAt,
                        lastUpdated = entry.lastUpdated,
                        serverVersion = entry.version,
                        deviceId = entry.deviceId,
                        transcript = entry.transcript,
                    ),
            ).toEntry()

    override suspend fun getEntry(
        userId: UUID,
        id: String,
    ): LogDateEntry? = syncRepository.getContent(userId = userId, id = id)?.toEntry()

    override suspend fun deleteEntry(
        userId: UUID,
        id: String,
        deletedAt: Long,
    ) {
        syncRepository.deleteContent(userId = userId, id = id, deletedAt = deletedAt)
    }

    override suspend fun entryChanges(
        userId: UUID,
        since: Long,
        limit: Int,
    ): LogDateChangeSet<LogDateEntry, LogDateEntryDeletion> =
        syncRepository
            .contentChanges(userId = userId, since = since, limit = limit)
            .toEntryChanges()

    override suspend fun listJournals(userId: UUID): List<LogDateJournal> =
        loadAllJournals(userId = userId, changes = syncRepository::journalChanges)

    override suspend fun upsertJournal(
        userId: UUID,
        journal: LogDateJournal,
    ): LogDateJournal = creationMutex.withLock { writeJournal(userId, journal) }

    override suspend fun createJournalIfAbsent(
        userId: UUID,
        journal: LogDateJournal,
        respectTombstones: Boolean,
    ): ConditionalCreate<LogDateJournal> =
        creationMutex.withLock {
            journalState(userId, journal.id).createUnlessTaken(respectTombstones) { writeJournal(userId, journal) }
        }

    private fun writeJournal(
        userId: UUID,
        journal: LogDateJournal,
    ): LogDateJournal =
        syncRepository
            .upsertJournal(
                userId = userId,
                record =
                    JournalRecord(
                        id = journal.id,
                        title = journal.title,
                        description = journal.description,
                        createdAt = journal.createdAt,
                        lastUpdated = journal.lastUpdated,
                        serverVersion = journal.version,
                        deviceId = journal.deviceId,
                    ),
            ).toJournal()

    override suspend fun getJournal(
        userId: UUID,
        id: String,
    ): LogDateJournal? = syncRepository.getJournal(userId = userId, id = id)?.toJournal()

    override suspend fun deleteJournal(
        userId: UUID,
        id: String,
        deletedAt: Long,
    ) {
        syncRepository.deleteJournal(userId = userId, id = id, deletedAt = deletedAt)
    }

    override suspend fun journalChanges(
        userId: UUID,
        since: Long,
        limit: Int,
    ): LogDateChangeSet<LogDateJournal, LogDateJournalDeletion> =
        syncRepository
            .journalChanges(userId = userId, since = since, limit = limit)
            .toJournalChanges()

    override suspend fun listAssociations(userId: UUID): List<LogDateAssociation> =
        loadAllAssociations(userId = userId, changes = syncRepository::associationChanges)

    override suspend fun upsertAssociations(
        userId: UUID,
        associations: List<LogDateAssociation>,
    ): List<LogDateAssociation> {
        syncRepository.upsertAssociations(
            userId = userId,
            records =
                associations.map { association ->
                    AssociationRecord(
                        journalId = association.journalId,
                        contentId = association.entryId,
                        createdAt = association.createdAt,
                        serverVersion = association.version,
                        deviceId = association.deviceId,
                    )
                },
        )
        return associations.mapNotNull { association ->
            listAssociations(userId).firstOrNull {
                it.journalId == association.journalId && it.entryId == association.entryId
            }
        }
    }

    override suspend fun deleteAssociations(
        userId: UUID,
        associations: List<LogDateAssociationRef>,
        deletedAt: Long,
    ) {
        syncRepository.deleteAssociations(
            userId = userId,
            keys =
                associations.map { association ->
                    AssociationKey(journalId = association.journalId, contentId = association.entryId)
                },
            deletedAt = deletedAt,
        )
    }

    override suspend fun associationChanges(
        userId: UUID,
        since: Long,
        limit: Int,
    ): LogDateChangeSet<LogDateAssociation, LogDateAssociationDeletion> =
        syncRepository
            .associationChanges(userId = userId, since = since, limit = limit)
            .toAssociationChanges()

    override suspend fun upsertDraft(
        userId: UUID,
        draft: LogDateDraft,
    ): LogDateDraft = throw UnsupportedOperationException("Draft sync not yet supported in sync-backed repository")

    override suspend fun getDraft(
        userId: UUID,
        id: String,
    ): LogDateDraft? = null

    override suspend fun deleteDraft(
        userId: UUID,
        id: String,
        deletedAt: Long,
    ) {}

    override suspend fun draftChanges(
        userId: UUID,
        since: Long,
        limit: Int,
    ): LogDateChangeSet<LogDateDraft, LogDateDraftDeletion> =
        LogDateChangeSet(changes = emptyList(), deletions = emptyList(), lastTimestamp = 0L)

    override suspend fun purgeTombstones(olderThan: Long): LogDateCollectionsPurgeResult =
        syncRepository.purgeTombstonesOlderThan(olderThan).let { result ->
            LogDateCollectionsPurgeResult(
                entryPurged = result.contentPurged,
                journalPurged = result.journalPurged,
                associationPurged = result.associationPurged,
                cutoff = result.cutoff,
            )
        }

    override suspend fun purgeTombstones(
        userId: UUID,
        olderThan: Long,
    ): LogDateCollectionsPurgeResult =
        syncRepository.purgeTombstones(userId = userId, olderThan = olderThan).let { result ->
            LogDateCollectionsPurgeResult(
                entryPurged = result.contentPurged,
                journalPurged = result.journalPurged,
                associationPurged = result.associationPurged,
                cutoff = result.cutoff,
            )
        }
}

private fun ContentRecord.toEntry(): LogDateEntry =
    LogDateEntry(
        id = id,
        type = type,
        content = content,
        mediaUri = mediaUri,
        durationMs = durationMs,
        createdAt = createdAt,
        lastUpdated = lastUpdated,
        version = serverVersion,
        deviceId = deviceId,
        transcript = transcript,
    )

private fun JournalRecord.toJournal(): LogDateJournal =
    LogDateJournal(
        id = id,
        title = title,
        description = description,
        createdAt = createdAt,
        lastUpdated = lastUpdated,
        version = serverVersion,
        deviceId = deviceId,
    )

private fun AssociationRecord.toAssociation(): LogDateAssociation =
    LogDateAssociation(
        journalId = journalId,
        entryId = contentId,
        createdAt = createdAt,
        version = serverVersion,
        deviceId = deviceId,
    )

private fun ChangeSet<ContentRecord, ContentDeletionMarker>.toEntryChanges(): LogDateChangeSet<LogDateEntry, LogDateEntryDeletion> =
    LogDateChangeSet(
        changes = changes.map(ContentRecord::toEntry),
        deletions =
            deletions.map { deletion ->
                LogDateEntryDeletion(id = deletion.id, deletedAt = deletion.deletedAt, serverVersion = deletion.serverVersion)
            },
        lastTimestamp = lastTimestamp,
        hasMore = hasMore,
    )

private fun ChangeSet<JournalRecord, JournalDeletionMarker>.toJournalChanges(): LogDateChangeSet<LogDateJournal, LogDateJournalDeletion> =
    LogDateChangeSet(
        changes = changes.map(JournalRecord::toJournal),
        deletions =
            deletions.map { deletion ->
                LogDateJournalDeletion(id = deletion.id, deletedAt = deletion.deletedAt, serverVersion = deletion.serverVersion)
            },
        lastTimestamp = lastTimestamp,
        hasMore = hasMore,
    )

private fun ChangeSet<AssociationRecord, AssociationDeletionMarker>.toAssociationChanges():
    LogDateChangeSet<LogDateAssociation, LogDateAssociationDeletion> =
    LogDateChangeSet(
        changes = changes.map(AssociationRecord::toAssociation),
        deletions =
            deletions.map { deletion ->
                LogDateAssociationDeletion(
                    association =
                        LogDateAssociationRef(
                            journalId = deletion.key.journalId,
                            entryId = deletion.key.contentId,
                        ),
                    deletedAt = deletion.deletedAt,
                    serverVersion = deletion.serverVersion,
                )
            },
        lastTimestamp = lastTimestamp,
        hasMore = hasMore,
    )

private fun <T, D> loadAll(
    userId: UUID,
    changes: (UUID, Long, Int) -> ChangeSet<T, D>,
    recordId: (T) -> Any,
    deletedId: (D) -> Any,
): List<T> {
    val records = linkedMapOf<Any, T>()
    var since = 0L
    do {
        val changeSet = changes(userId, since, CHANGE_PAGE_SIZE)
        changeSet.changes.forEach { change -> records[recordId(change)] = change }
        changeSet.deletions.forEach { deletion -> records.remove(deletedId(deletion)) }
        if (changeSet.lastTimestamp <= since) {
            break
        }
        since = changeSet.lastTimestamp
    } while (changeSet.hasMore)
    return records.values.toList()
}

private fun loadAllEntries(
    userId: UUID,
    changes: (UUID, Long, Int) -> ChangeSet<ContentRecord, ContentDeletionMarker>,
): List<LogDateEntry> = loadAll(userId, changes, ContentRecord::id, ContentDeletionMarker::id).map(ContentRecord::toEntry)

private fun loadAllJournals(
    userId: UUID,
    changes: (UUID, Long, Int) -> ChangeSet<JournalRecord, JournalDeletionMarker>,
): List<LogDateJournal> = loadAll(userId, changes, JournalRecord::id, JournalDeletionMarker::id).map(JournalRecord::toJournal)

private fun loadAllAssociations(
    userId: UUID,
    changes: (UUID, Long, Int) -> ChangeSet<AssociationRecord, AssociationDeletionMarker>,
): List<LogDateAssociation> =
    loadAll(
        userId,
        changes,
        recordId = { AssociationKey(journalId = it.journalId, contentId = it.contentId) },
        deletedId = AssociationDeletionMarker::key,
    ).map(AssociationRecord::toAssociation)

private const val CHANGE_PAGE_SIZE = 100
