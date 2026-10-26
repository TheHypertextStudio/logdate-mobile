package app.logdate.server.logdate

import app.logdate.shared.model.sync.DeviceId
import app.logdate.shared.model.sync.JournalMergeRequest
import app.logdate.shared.model.sync.JournalMergeResponse
import java.util.UUID

/**
 * LogDate-owned collection models used internally by the server.
 *
 * These stay protocol-agnostic so AT Protocol remains an integration boundary rather than the
 * language of the core application.
 */
data class LogDateEntry(
    val id: String,
    val type: String,
    val content: String?,
    val mediaUri: String?,
    val durationMs: Long?,
    val createdAt: Long,
    val lastUpdated: Long,
    val version: Long,
    val deviceId: DeviceId,
    /** User-authored caption for image and video entries. */
    val caption: String? = null,
    val photoPresentation: String? = null,
    /** Encrypted note location; the server stores and returns it without interpreting it. */
    val location: String? = null,
    /** Opaque encrypted structured audio transcript. */
    val transcript: String? = null,
)

data class LogDateJournal(
    val id: String,
    val title: String,
    val description: String,
    val createdAt: Long,
    val lastUpdated: Long,
    val version: Long,
    val deviceId: DeviceId,
)

data class LogDateAssociation(
    val journalId: String,
    val entryId: String,
    val createdAt: Long,
    val version: Long,
    val deviceId: DeviceId,
)

data class LogDateCollectionsStatus(
    val entryCount: Int,
    val journalCount: Int,
    val associationCount: Int,
    val lastTimestamp: Long,
)

/** Counts payload records scrubbed; lightweight deletion markers are retained for offline clients. */
data class LogDateCollectionsPurgeResult(
    val entryPurged: Int,
    val journalPurged: Int,
    val associationPurged: Int,
    val cutoff: Long,
)

data class LogDateChangeSet<T, D>(
    val changes: List<T>,
    val deletions: List<D>,
    val lastTimestamp: Long,
    val hasMore: Boolean = false,
)

data class LogDateEntryDeletion(
    val id: String,
    val deletedAt: Long,
    val serverVersion: Long = deletedAt,
)

data class LogDateJournalDeletion(
    val id: String,
    val deletedAt: Long,
    val serverVersion: Long = deletedAt,
    val mergedIntoJournalId: String? = null,
)

data class LogDateAssociationRef(
    val journalId: String,
    val entryId: String,
)

data class LogDateAssociationDeletion(
    val association: LogDateAssociationRef,
    val deletedAt: Long,
    val serverVersion: Long = deletedAt,
)

data class LogDateDraft(
    val id: String,
    val content: String,
    val blockTypes: List<String>,
    val journalIds: List<String>,
    val createdAt: Long,
    val lastUpdated: Long,
    val version: Long,
    val deviceId: DeviceId,
    val encryptedBlocksVersion: Int? = null,
    val encryptedBlocks: String? = null,
)

data class LogDateDraftDeletion(
    val id: String,
    val deletedAt: Long,
    val serverVersion: Long = 0L,
)

class DraftFormatUpgradeRequiredException : IllegalStateException("Draft was upgraded by a newer client")

/**
 * Internal collection repository boundary for the LogDate data model.
 *
 * Sync and AT Protocol adapters both consume this interface so neither of them owns the server's
 * internal persistence language.
 */
interface LogDateCollectionsRepository {
    suspend fun merge(
        userId: UUID,
        sourceId: String,
        request: JournalMergeRequest,
    ): JournalMergeResponse = throw UnsupportedOperationException("Journal merge is unavailable")

    /** Allocates a change-feed deletion even when the source existed only offline. */
    suspend fun recordJournalMergeDeletion(
        userId: UUID,
        id: String,
        deletedAt: Long,
    ): Long {
        deleteJournal(userId, id, deletedAt)
        return journalChanges(userId, 0L, Int.MAX_VALUE).deletions.first { it.id == id }.serverVersion
    }

    suspend fun journalMergeDestination(
        userId: UUID,
        id: String,
    ): String? = null

    suspend fun status(userId: UUID): LogDateCollectionsStatus

    suspend fun listEntries(userId: UUID): List<LogDateEntry>

    suspend fun upsertEntry(
        userId: UUID,
        entry: LogDateEntry,
    ): LogDateEntry

    /** Creates while the canonical record is absent; null preserves an existing record. */
    suspend fun createEntryIfAbsent(
        userId: UUID,
        entry: LogDateEntry,
    ): LogDateEntry?

    suspend fun getEntry(
        userId: UUID,
        id: String,
    ): LogDateEntry?

    /**
     * Whether an entry exists, without reading it.
     *
     * Upsert needs this only to answer 200 versus 201. Fetching the record to find out opens the
     * repo, which costs a pass over every block in it, and the value is then thrown away. The
     * default is the honest fallback for implementations that have no cheaper answer.
     */
    suspend fun entryExists(
        userId: UUID,
        id: String,
    ): Boolean = getEntry(userId, id) != null

    suspend fun deleteEntry(
        userId: UUID,
        id: String,
        deletedAt: Long,
    )

    suspend fun entryChanges(
        userId: UUID,
        since: Long,
        limit: Int,
    ): LogDateChangeSet<LogDateEntry, LogDateEntryDeletion>

    suspend fun listJournals(userId: UUID): List<LogDateJournal>

    suspend fun upsertJournal(
        userId: UUID,
        journal: LogDateJournal,
    ): LogDateJournal

    suspend fun createJournalIfAbsent(
        userId: UUID,
        journal: LogDateJournal,
    ): LogDateJournal?

    suspend fun getJournal(
        userId: UUID,
        id: String,
    ): LogDateJournal?

    /** Whether a journal exists, without reading it. See [entryExists]. */
    suspend fun journalExists(
        userId: UUID,
        id: String,
    ): Boolean = getJournal(userId, id) != null

    suspend fun deleteJournal(
        userId: UUID,
        id: String,
        deletedAt: Long,
    )

    suspend fun journalChanges(
        userId: UUID,
        since: Long,
        limit: Int,
    ): LogDateChangeSet<LogDateJournal, LogDateJournalDeletion>

    suspend fun listAssociations(userId: UUID): List<LogDateAssociation>

    suspend fun upsertAssociations(
        userId: UUID,
        associations: List<LogDateAssociation>,
    ): List<LogDateAssociation>

    suspend fun deleteAssociations(
        userId: UUID,
        associations: List<LogDateAssociationRef>,
        deletedAt: Long,
    )

    suspend fun associationChanges(
        userId: UUID,
        since: Long,
        limit: Int,
    ): LogDateChangeSet<LogDateAssociation, LogDateAssociationDeletion>

    suspend fun upsertDraft(
        userId: UUID,
        draft: LogDateDraft,
    ): LogDateDraft

    suspend fun getDraft(
        userId: UUID,
        id: String,
    ): LogDateDraft?

    suspend fun deleteDraft(
        userId: UUID,
        id: String,
        deletedAt: Long,
    )

    suspend fun draftChanges(
        userId: UUID,
        since: Long,
        limit: Int,
    ): LogDateChangeSet<LogDateDraft, LogDateDraftDeletion>

    suspend fun purgeTombstones(
        userId: UUID,
        olderThan: Long,
    ): LogDateCollectionsPurgeResult

    /**
     * Purges expired tombstones across every user, for the scheduled retention job.
     *
     * The job runs without a request context and therefore without a user, which is why it
     * cannot reuse the per-user purge behind the maintenance endpoint.
     */
    suspend fun purgeTombstones(olderThan: Long): LogDateCollectionsPurgeResult
}
