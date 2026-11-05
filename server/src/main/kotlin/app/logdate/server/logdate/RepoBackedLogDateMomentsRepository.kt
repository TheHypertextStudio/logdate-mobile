package app.logdate.server.logdate

import app.logdate.server.atproto.HostedRepoCommitSigner
import app.logdate.server.auth.AccountRepository
import app.logdate.server.identity.SigningKeyService
import io.github.aakira.napier.Napier
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.repo.DefaultRepoEngine
import studio.hypertext.atproto.repo.RepoBlockStore
import studio.hypertext.atproto.syntax.RecordKey
import java.util.UUID

/**
 * Marked moments in the account's repo, versioned and tombstoned through the same metadata store as entries:
 * they share the account's version sequence and the scheduled tombstone purge. [repoDidFor] is
 * [RepoBackedLogDateCollectionsRepository.canonicalRepoDid], so both stores write to one repo.
 */
internal class RepoBackedLogDateMomentsRepository(
    accountRepository: AccountRepository,
    signingKeyService: SigningKeyService,
    blockStore: RepoBlockStore,
    private val metadataStore: LogDateCollectionsMetadataStore,
    private val repoDidFor: suspend (UUID) -> AtprotoDid,
    private val mutations: JournalMergeStore,
) : LogDateMomentsRepository {
    private val repoEngine = DefaultRepoEngine(blockStore, signer = HostedRepoCommitSigner(accountRepository, signingKeyService))
    private val transaction = blockStore as? RepoWriteTransaction ?: RepoWriteTransaction.Direct
    private val conditionalWriter = RepoConditionalWriter(repoEngine, metadataStore, transaction)

    override suspend fun updateMomentIfVersion(
        userId: UUID,
        id: String,
        expectedVersion: Long?,
        transform: suspend (LogDateMoment) -> LogDateMoment,
    ): MomentUpdateResult =
        mutations.withAccountLock(userId) {
            val existing = readMoment(userId, id) ?: return@withAccountLock MomentUpdateResult.Missing
            if (expectedVersion != null && existing.version != expectedVersion) return@withAccountLock MomentUpdateResult.Conflict
            val changed = transform(existing)
            require(changed.id == id) { "A moment patch cannot change its identity" }
            MomentUpdateResult.Updated(transaction.write(userId) { writeMoment(userId, changed) })
        }

    override suspend fun momentState(
        userId: UUID,
        id: String,
    ): LogDateRecordState = mutations.withAccountLock(userId) { conditionalWriter.state(userId, LogDateCollectionKind.MOMENT, id) }

    override suspend fun upsertMoment(
        userId: UUID,
        moment: LogDateMoment,
    ): LogDateMoment = mutations.withAccountLock(userId) { transaction.write(userId) { writeMoment(userId, moment) } }

    override suspend fun createMomentIfAbsent(
        userId: UUID,
        moment: LogDateMoment,
        respectTombstones: Boolean,
    ): ConditionalCreate<LogDateMoment> = mutations.withAccountLock(userId) { createMoment(userId, moment, respectTombstones) }

    override suspend fun getMoment(
        userId: UUID,
        id: String,
    ): LogDateMoment? = mutations.withAccountLock(userId) { readMoment(userId, id) }

    override suspend fun deleteMoment(
        userId: UUID,
        id: String,
        deletedAt: Long,
    ) {
        mutations.withAccountLock(userId) { transaction.write(userId) { removeMoment(userId, id, deletedAt) } }
    }

    override suspend fun momentChanges(
        userId: UUID,
        since: Long,
        limit: Int,
    ): LogDateChangeSet<LogDateMoment, LogDateMomentDeletion> = mutations.withAccountLock(userId) { readChanges(userId, since, limit) }

    private suspend fun writeMoment(
        userId: UUID,
        moment: LogDateMoment,
    ): LogDateMoment {
        val repoDid = repoDidFor(userId)
        repoEngine.putRecord(momentRecordId(repoDid, moment.id), moment.toRepoJson()).getOrThrow()
        val metadata = metadataStore.upsert(userId, repoDid, LogDateCollectionKind.MOMENT, moment.id)
        return moment.copy(version = metadata.version, lastUpdated = System.currentTimeMillis())
    }

    private suspend fun createMoment(
        userId: UUID,
        moment: LogDateMoment,
        respectTombstones: Boolean,
    ): ConditionalCreate<LogDateMoment> {
        val repoDid = repoDidFor(userId)
        return conditionalWriter
            .createIfAbsent(
                userId = userId,
                repoDid = repoDid,
                collection = LogDateCollectionKind.MOMENT,
                recordId = momentRecordId(repoDid, moment.id),
                value = moment.toRepoJson(),
                respectTombstones = respectTombstones,
            ).mapCreated { version -> moment.copy(version = version, lastUpdated = System.currentTimeMillis()) }
    }

    private suspend fun readMoment(
        userId: UUID,
        id: String,
    ): LogDateMoment? {
        val metadata = metadataStore.metadata(userId, LogDateCollectionKind.MOMENT, id) ?: return null
        return repoEngine
            .getRecord(momentRecordId(repoDidFor(userId), id))
            .getOrThrow()
            ?.value
            ?.toLogDateMoment(RecordKey.require(id), metadata.version)
    }

    private suspend fun removeMoment(
        userId: UUID,
        id: String,
        deletedAt: Long,
    ) {
        val existing = metadataStore.metadata(userId, LogDateCollectionKind.MOMENT, id) ?: return
        val repoDid = repoDidFor(userId)
        if (!repoEngine.deleteRecord(momentRecordId(repoDid, id)).getOrThrow()) {
            Napier.w("A moment's repo record was already missing when it was deleted")
        }
        metadataStore.delete(userId, repoDid, LogDateCollectionKind.MOMENT, existing.recordKey, deletedAt)
    }

    private suspend fun readChanges(
        userId: UUID,
        since: Long,
        limit: Int,
    ): LogDateChangeSet<LogDateMoment, LogDateMomentDeletion> {
        val repoDid = repoDidFor(userId)
        val metadata = metadataStore.changes(userId, LogDateCollectionKind.MOMENT, since, limit)
        val records = repoEngine.getRecords(metadata.changes.map { momentRecordId(repoDid, it.recordKey) }).getOrThrow()
        return LogDateChangeSet(
            changes =
                records.zip(metadata.changes).mapNotNull { (record, change) ->
                    record?.value?.toLogDateMoment(RecordKey.require(change.recordKey), change.version)
                },
            deletions = metadata.deletions.map { LogDateMomentDeletion(it.recordKey, requireNotNull(it.deletedAt), it.version) },
            lastTimestamp = metadata.lastTimestamp,
            hasMore = metadata.hasMore,
        )
    }
}
