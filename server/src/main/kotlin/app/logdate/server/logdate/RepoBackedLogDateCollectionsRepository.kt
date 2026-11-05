@file:OptIn(ExperimentalUuidApi::class)

package app.logdate.server.logdate

import app.logdate.server.atproto.HostedRepoCommitSigner
import app.logdate.server.auth.AccountRepository
import app.logdate.server.database.toKotlinUuid
import app.logdate.server.identity.AtprotoIdentityService
import app.logdate.server.identity.SigningKeyService
import app.logdate.server.sync.StaleWriteException
import io.github.aakira.napier.Napier
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.repo.DefaultRepoEngine
import studio.hypertext.atproto.repo.RepoBlockStore
import studio.hypertext.atproto.repo.RepoRecordId
import studio.hypertext.atproto.repo.RepoWriteResult
import studio.hypertext.atproto.syntax.RecordKey
import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi

/**
 * Canonical LogDate collections repository backed by the shared AT Protocol repo engine.
 *
 * Live record bodies are stored in the repo block store. Sync-only concerns such as versions,
 * tombstones, and the stable repo DID binding stay in [metadataStore] so the server's internal
 * language remains LogDate collections rather than raw AT Protocol records.
 */
internal class RepoBackedLogDateCollectionsRepository(
    private val accountRepository: AccountRepository,
    private val identityService: AtprotoIdentityService,
    signingKeyService: SigningKeyService,
    blockStore: RepoBlockStore,
    internal val metadataStore: LogDateCollectionsMetadataStore,
) : LogDateCollectionsRepository {
    internal val repoEngine = DefaultRepoEngine(blockStore, signer = HostedRepoCommitSigner(accountRepository, signingKeyService))
    private val transaction = blockStore as? RepoWriteTransaction ?: RepoWriteTransaction.Direct
    private val conditionalWriter =
        RepoConditionalWriter(
            repoEngine,
            metadataStore,
            transaction,
        )

    override suspend fun status(userId: UUID): LogDateCollectionsStatus = metadataStore.status(userId)

    override suspend fun listEntries(userId: UUID): List<LogDateEntry> =
        listLiveRecords(userId = userId, collection = LogDateCollectionKind.ENTRY) { repoDid, metadata ->
            repoEngine
                .getRecord(entryRecordId(repoDid, metadata.recordKey))
                .getOrThrow()
                ?.value
                ?.toLogDateEntry(recordKey = RecordKey.require(metadata.recordKey), version = metadata.version)
        }

    override suspend fun upsertEntry(
        userId: UUID,
        entry: LogDateEntry,
    ): LogDateEntry =
        transaction.write(userId) {
            metadataStore.requireWritable(userId, LogDateCollectionKind.ENTRY, entry.id)
            val repoDid = canonicalRepoDid(userId)
            val preserved =
                entry.copy(
                    transcript =
                        entry.transcript ?: getEntry(userId, entry.id)
                            ?.takeIf {
                                it.type == entry.type && it.mediaUri == entry.mediaUri && it.durationMs == entry.durationMs
                            }?.transcript,
                )
            val recordId = entryRecordId(repoDid, entry.id)
            val written = repoEngine.putRecord(recordId, preserved.toRepoJson()).getOrThrow()
            val metadata = indexWrittenRecord(userId, repoDid, LogDateCollectionKind.ENTRY, recordId, written)
            return@write preserved.copy(version = metadata.version, lastUpdated = System.currentTimeMillis())
        }

    override suspend fun entryExists(
        userId: UUID,
        id: String,
    ): Boolean = metadataStore.metadata(userId, LogDateCollectionKind.ENTRY, id) != null

    internal suspend fun indexWrittenRecord(
        userId: UUID,
        repoDid: AtprotoDid,
        collection: LogDateCollectionKind,
        recordId: RepoRecordId,
        written: RepoWriteResult,
    ): LogDateCollectionMetadata =
        try {
            metadataStore.upsert(userId, repoDid, collection, recordId.recordKey.toString())
        } catch (deleted: StaleWriteException) {
            // Remove only this rejected write; a later canonical write must never be overwritten.
            runCatching { repoEngine.deleteRecord(recordId, swapRecord = written.cid).getOrThrow() }
                .onFailure { Napier.e("Could not remove a canonical write rejected by a deletion fence", it) }
            throw deleted
        }

    internal suspend fun deleteCanonicalRecord(
        userId: UUID,
        repoDid: AtprotoDid,
        collection: LogDateCollectionKind,
        recordId: RepoRecordId,
        deletedAt: Long,
    ) {
        // Publish the fence first so every server rejects writes while canonical cleanup runs.
        metadataStore.delete(
            userId = userId,
            repoDid = repoDid,
            collection = collection,
            recordKey = recordId.recordKey.toString(),
            deletedAt = deletedAt,
            allowMissing = true,
        )
        transaction.write(userId) { repoEngine.deleteRecord(recordId).getOrThrow() }
    }

    override suspend fun journalExists(
        userId: UUID,
        id: String,
    ): Boolean = metadataStore.metadata(userId, LogDateCollectionKind.JOURNAL, id) != null

    override suspend fun entryState(
        userId: UUID,
        id: String,
    ): LogDateRecordState = conditionalWriter.state(userId, LogDateCollectionKind.ENTRY, id)

    override suspend fun journalState(
        userId: UUID,
        id: String,
    ): LogDateRecordState = conditionalWriter.state(userId, LogDateCollectionKind.JOURNAL, id)

    override suspend fun createEntryIfAbsent(
        userId: UUID,
        entry: LogDateEntry,
        respectTombstones: Boolean,
    ): ConditionalCreate<LogDateEntry> {
        val repoDid = canonicalRepoDid(userId)
        return conditionalWriter
            .createIfAbsent(
                userId = userId,
                repoDid = repoDid,
                collection = LogDateCollectionKind.ENTRY,
                recordId = entryRecordId(repoDid, entry.id),
                value = entry.toRepoJson(),
                respectTombstones = respectTombstones,
            ).mapCreated { version -> entry.copy(version = version, lastUpdated = System.currentTimeMillis()) }
    }

    override suspend fun createJournalIfAbsent(
        userId: UUID,
        journal: LogDateJournal,
        respectTombstones: Boolean,
    ): ConditionalCreate<LogDateJournal> {
        val repoDid = canonicalRepoDid(userId)
        return conditionalWriter
            .createIfAbsent(
                userId = userId,
                repoDid = repoDid,
                collection = LogDateCollectionKind.JOURNAL,
                recordId = journalRecordId(repoDid, journal.id),
                value = journal.toRepoJson(),
                respectTombstones = respectTombstones,
            ).mapCreated { version -> journal.copy(version = version, lastUpdated = System.currentTimeMillis()) }
    }

    override suspend fun createAssociationsIfAbsent(
        userId: UUID,
        associations: List<LogDateAssociation>,
    ): AssociationCreateResults {
        val repoDid = canonicalRepoDid(userId)
        val created = mutableListOf<LogDateAssociation>()
        val existing = mutableListOf<LogDateAssociationRef>()
        val deleted = mutableListOf<LogDateAssociationDeletion>()
        associations.forEach { association ->
            val ref = LogDateAssociationRef(association.journalId, association.entryId)
            val outcome =
                conditionalWriter.createIfAbsent(
                    userId = userId,
                    repoDid = repoDid,
                    collection = LogDateCollectionKind.ASSOCIATION,
                    recordId = associationRecordId(repoDid, association.journalId, association.entryId),
                    value = association.toRepoJson(),
                    respectTombstones = true,
                )
            when (outcome) {
                is ConditionalCreate.Created -> created += association.copy(version = outcome.record)
                is ConditionalCreate.Exists -> existing += ref
                is ConditionalCreate.Deleted -> deleted += LogDateAssociationDeletion(ref, outcome.deletedAt, outcome.serverVersion)
            }
        }
        return AssociationCreateResults(created, existing, deleted)
    }

    override suspend fun getEntry(
        userId: UUID,
        id: String,
    ): LogDateEntry? {
        val metadata = metadataStore.metadata(userId, LogDateCollectionKind.ENTRY, id) ?: return null
        val repoDid = canonicalRepoDid(userId)
        return repoEngine
            .getRecord(entryRecordId(repoDid, id))
            .getOrThrow()
            ?.value
            ?.toLogDateEntry(recordKey = RecordKey.require(id), version = metadata.version)
            ?: missingRecord(
                userId = userId,
                collection = LogDateCollectionKind.ENTRY,
                recordKey = id,
            )
    }

    override suspend fun deleteEntry(
        userId: UUID,
        id: String,
        deletedAt: Long,
    ) {
        val repoDid = canonicalRepoDid(userId)
        deleteCanonicalRecord(userId, repoDid, LogDateCollectionKind.ENTRY, entryRecordId(repoDid, id), deletedAt)
    }

    override suspend fun entryChanges(
        userId: UUID,
        since: Long,
        limit: Int,
    ): LogDateChangeSet<LogDateEntry, LogDateEntryDeletion> {
        val repoDid = canonicalRepoDid(userId)
        val metadata = metadataStore.changes(userId, LogDateCollectionKind.ENTRY, since, limit)
        return LogDateChangeSet(
            changes =
                repoEngine
                    .getRecords(metadata.changes.map { entryRecordId(repoDid, it.recordKey) })
                    .getOrThrow()
                    .zip(metadata.changes)
                    .mapNotNull { (record, change) ->
                        record
                            ?.value
                            ?.toLogDateEntry(recordKey = RecordKey.require(change.recordKey), version = change.version)
                            ?: missingRecord(userId, LogDateCollectionKind.ENTRY, change.recordKey)
                    },
            deletions =
                metadata.deletions.map { deletion ->
                    LogDateEntryDeletion(
                        id = deletion.recordKey,
                        deletedAt = requireNotNull(deletion.deletedAt),
                        serverVersion = deletion.version,
                    )
                },
            lastTimestamp = metadata.lastTimestamp,
            hasMore = metadata.hasMore,
        )
    }

    override suspend fun listJournals(userId: UUID): List<LogDateJournal> =
        listLiveRecords(userId = userId, collection = LogDateCollectionKind.JOURNAL) { repoDid, metadata ->
            repoEngine
                .getRecord(journalRecordId(repoDid, metadata.recordKey))
                .getOrThrow()
                ?.value
                ?.toLogDateJournal(recordKey = RecordKey.require(metadata.recordKey), version = metadata.version)
        }

    override suspend fun upsertJournal(
        userId: UUID,
        journal: LogDateJournal,
    ): LogDateJournal =
        transaction.write(userId) {
            metadataStore.requireWritable(userId, LogDateCollectionKind.JOURNAL, journal.id)
            val repoDid = canonicalRepoDid(userId)
            val recordId = journalRecordId(repoDid, journal.id)
            val written = repoEngine.putRecord(recordId, journal.toRepoJson()).getOrThrow()
            val metadata = indexWrittenRecord(userId, repoDid, LogDateCollectionKind.JOURNAL, recordId, written)
            return@write journal.copy(version = metadata.version, lastUpdated = System.currentTimeMillis())
        }

    override suspend fun getJournal(
        userId: UUID,
        id: String,
    ): LogDateJournal? {
        val metadata = metadataStore.metadata(userId, LogDateCollectionKind.JOURNAL, id) ?: return null
        val repoDid = canonicalRepoDid(userId)
        return repoEngine
            .getRecord(journalRecordId(repoDid, id))
            .getOrThrow()
            ?.value
            ?.toLogDateJournal(recordKey = RecordKey.require(id), version = metadata.version)
            ?: missingRecord(
                userId = userId,
                collection = LogDateCollectionKind.JOURNAL,
                recordKey = id,
            )
    }

    override suspend fun deleteJournal(
        userId: UUID,
        id: String,
        deletedAt: Long,
    ) {
        val repoDid = canonicalRepoDid(userId)
        deleteCanonicalRecord(userId, repoDid, LogDateCollectionKind.JOURNAL, journalRecordId(repoDid, id), deletedAt)
    }

    override suspend fun recordJournalMergeDeletion(
        userId: UUID,
        id: String,
        deletedAt: Long,
    ): Long {
        val repoDid = canonicalRepoDid(userId)
        return checkNotNull(
            metadataStore.delete(
                userId = userId,
                repoDid = repoDid,
                collection = LogDateCollectionKind.JOURNAL,
                recordKey = id,
                deletedAt = deletedAt,
                allowMissing = true,
            ),
        ).version
    }

    override suspend fun journalChanges(
        userId: UUID,
        since: Long,
        limit: Int,
    ): LogDateChangeSet<LogDateJournal, LogDateJournalDeletion> {
        val repoDid = canonicalRepoDid(userId)
        val metadata = metadataStore.changes(userId, LogDateCollectionKind.JOURNAL, since, limit)
        return LogDateChangeSet(
            changes =
                repoEngine
                    .getRecords(metadata.changes.map { journalRecordId(repoDid, it.recordKey) })
                    .getOrThrow()
                    .zip(metadata.changes)
                    .mapNotNull { (record, change) ->
                        record
                            ?.value
                            ?.toLogDateJournal(recordKey = RecordKey.require(change.recordKey), version = change.version)
                            ?: missingRecord(userId, LogDateCollectionKind.JOURNAL, change.recordKey)
                    },
            deletions =
                metadata.deletions.map { deletion ->
                    LogDateJournalDeletion(
                        id = deletion.recordKey,
                        deletedAt = requireNotNull(deletion.deletedAt),
                        serverVersion = deletion.version,
                    )
                },
            lastTimestamp = metadata.lastTimestamp,
            hasMore = metadata.hasMore,
        )
    }

    override suspend fun listAssociations(userId: UUID): List<LogDateAssociation> = repoListAssociations(userId)

    override suspend fun upsertAssociations(
        userId: UUID,
        associations: List<LogDateAssociation>,
    ): List<LogDateAssociation> = transaction.write(userId) { repoUpsertAssociations(userId, associations) }

    override suspend fun deleteAssociations(
        userId: UUID,
        associations: List<LogDateAssociationRef>,
        deletedAt: Long,
    ) = repoDeleteAssociations(userId, associations, deletedAt)

    override suspend fun associationChanges(
        userId: UUID,
        since: Long,
        limit: Int,
    ): LogDateChangeSet<LogDateAssociation, LogDateAssociationDeletion> = repoAssociationChanges(userId, since, limit)

    override suspend fun upsertDraft(
        userId: UUID,
        draft: LogDateDraft,
    ): LogDateDraft = transaction.write(userId) { repoUpsertDraft(userId, draft) }

    override suspend fun getDraft(
        userId: UUID,
        id: String,
    ): LogDateDraft? = repoGetDraft(userId, id)

    override suspend fun deleteDraft(
        userId: UUID,
        id: String,
        deletedAt: Long,
    ) = repoDeleteDraft(userId, id, deletedAt)

    override suspend fun draftChanges(
        userId: UUID,
        since: Long,
        limit: Int,
    ): LogDateChangeSet<LogDateDraft, LogDateDraftDeletion> = repoDraftChanges(userId, since, limit)

    override suspend fun purgeTombstones(olderThan: Long): LogDateCollectionsPurgeResult = metadataStore.purgeAllTombstones(olderThan)

    override suspend fun purgeTombstones(
        userId: UUID,
        olderThan: Long,
    ): LogDateCollectionsPurgeResult = metadataStore.purgeTombstones(userId = userId, olderThan = olderThan)

    internal suspend fun canonicalRepoDid(userId: UUID): AtprotoDid = metadataStore.state(userId)?.repoDid ?: preferredRepoDid(userId)

    private suspend fun preferredRepoDid(userId: UUID): AtprotoDid {
        val account = accountRepository.findById(userId.toKotlinUuid())
        val ensuredDid = account?.let { identityService.ensureIdentity(it).did }
        return AtprotoDid.require(ensuredDid ?: syntheticRepoDid(userId))
    }

    internal suspend fun <T> listLiveRecords(
        userId: UUID,
        collection: LogDateCollectionKind,
        decode: suspend (AtprotoDid, LogDateCollectionMetadata) -> T?,
    ): List<T> {
        val repoDid = canonicalRepoDid(userId)
        return metadataStore.listLive(userId, collection).mapNotNull { metadata ->
            decode(repoDid, metadata) ?: missingRecord(userId, collection, metadata.recordKey)
        }
    }

    internal fun <T> missingRecord(
        userId: UUID,
        collection: LogDateCollectionKind,
        recordKey: String,
    ): T? {
        Napier.w("Missing canonical repo record for ${collection.storageName}:$recordKey on user $userId")
        return null
    }

    internal fun repoRecordIdForAssociation(
        repoDid: AtprotoDid,
        recordKey: String,
    ) = RepoRecordId(
        repo = repoDid,
        collection = LogDateCollectionKind.ASSOCIATION.nsid,
        recordKey = RecordKey.require(recordKey),
    )
}

/** The repository DID sync uses for an account that has no DID of its own. */
internal fun syntheticRepoDid(userId: UUID): String =
    "$SYNTHETIC_REPO_DID_PREFIX${userId.toString().replace("-", "")}$SYNTHETIC_REPO_DID_SUFFIX"

/**
 * True for a [syntheticRepoDid]. Those repositories are private sync storage: the `.internal`
 * domain never resolves, so no DID document points at them and relays can never read them.
 */
internal fun isSyntheticRepoDid(did: AtprotoDid): Boolean =
    did.toString().let { it.startsWith(SYNTHETIC_REPO_DID_PREFIX) && it.endsWith(SYNTHETIC_REPO_DID_SUFFIX) }

private const val SYNTHETIC_REPO_DID_PREFIX = "did:web:sync-"
private const val SYNTHETIC_REPO_DID_SUFFIX = ".logdate.internal"
