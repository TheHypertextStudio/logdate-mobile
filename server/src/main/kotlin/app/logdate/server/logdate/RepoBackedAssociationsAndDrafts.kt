@file:OptIn(ExperimentalUuidApi::class)

package app.logdate.server.logdate

import io.github.aakira.napier.Napier
import studio.hypertext.atproto.repo.BatchRecordWrite
import studio.hypertext.atproto.syntax.RecordKey
import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi

internal suspend fun RepoBackedLogDateCollectionsRepository.repoListAssociations(userId: UUID): List<LogDateAssociation> =
    listLiveRecords(userId = userId, collection = LogDateCollectionKind.ASSOCIATION) { repoDid, metadata ->
        repoEngine
            .getRecord(
                repoRecordIdForAssociation(
                    repoDid = repoDid,
                    recordKey = metadata.recordKey,
                ),
            ).getOrThrow()
            ?.value
            ?.toLogDateAssociation(recordKey = RecordKey.require(metadata.recordKey), version = metadata.version)
    }

internal suspend fun RepoBackedLogDateCollectionsRepository.repoUpsertAssociations(
    userId: UUID,
    associations: List<LogDateAssociation>,
): List<LogDateAssociation> {
    val repoDid = canonicalRepoDid(userId)
    if (associations.isEmpty()) return emptyList()
    associations.forEach {
        metadataStore.requireWritable(userId, LogDateCollectionKind.ASSOCIATION, associationRecordKey(it.journalId, it.entryId).toString())
    }
    val recordIds = associations.map { associationRecordId(repoDid, it.journalId, it.entryId) }
    val previousRecords = repoEngine.getRecords(recordIds).getOrThrow()

    // One commit for the whole list. This used to put each record separately, so an endpoint
    // that accepts a list cost exactly as much as the client sending them one at a time -
    // a full read-rebuild-write of the repo per link.
    val writtenRecords =
        repoEngine
            .putRecords(
                associations.mapIndexed { index, association ->
                    BatchRecordWrite(
                        recordId = recordIds[index],
                        value = association.toRepoJson(),
                        swapRecord = previousRecords[index]?.cid,
                    )
                },
            ).getOrThrow()

    val metadataResults =
        try {
            metadataStore.upsertBatch(
                userId = userId,
                repoDid = repoDid,
                collection = LogDateCollectionKind.ASSOCIATION,
                recordKeys = associations.map { associationRecordKey(it.journalId, it.entryId).toString() },
            )
        } catch (failure: Throwable) {
            // Restore the repo state this batch replaced. A retry can contain existing links,
            // so deleting every record would erase a link that predates the failed request.
            associations.forEachIndexed { index, association ->
                runCatching {
                    val recordId = recordIds[index]
                    val writtenCid = writtenRecords[index].cid
                    val previous = previousRecords[index]
                    val key = recordId.recordKey.toString()
                    val fenced = metadataStore.deletionTimestamp(userId, LogDateCollectionKind.ASSOCIATION, key) != null
                    if (previous == null || fenced) {
                        repoEngine.deleteRecord(recordId, swapRecord = writtenCid).getOrThrow()
                    } else {
                        val restored = repoEngine.putRecord(recordId, previous.value, swapRecord = writtenCid).getOrThrow()
                        // A different server may have fenced this member during restoration.
                        if (metadataStore.deletionTimestamp(userId, LogDateCollectionKind.ASSOCIATION, key) != null) {
                            repoEngine.deleteRecord(recordId, swapRecord = restored.cid).getOrThrow()
                        }
                    }
                }.onFailure { compensationFailure ->
                    Napier.e(
                        "Failed to compensate association repo record " +
                            "${association.journalId}/${association.entryId} for user $userId after metadata upsert " +
                            "failed; repo and metadata store may now be inconsistent",
                        compensationFailure,
                    )
                }
            }
            throw failure
        }

    return associations.zip(metadataResults) { association, metadata -> association.copy(version = metadata.version) }
}

internal suspend fun RepoBackedLogDateCollectionsRepository.repoDeleteAssociations(
    userId: UUID,
    associations: List<LogDateAssociationRef>,
    deletedAt: Long,
) {
    val repoDid = canonicalRepoDid(userId)
    associations.forEach { association ->
        deleteCanonicalRecord(
            userId = userId,
            repoDid = repoDid,
            collection = LogDateCollectionKind.ASSOCIATION,
            recordId = associationRecordId(repoDid, association.journalId, association.entryId),
            deletedAt = deletedAt,
        )
    }
}

internal suspend fun RepoBackedLogDateCollectionsRepository.repoAssociationChanges(
    userId: UUID,
    since: Long,
    limit: Int,
): LogDateChangeSet<LogDateAssociation, LogDateAssociationDeletion> {
    val repoDid = canonicalRepoDid(userId)
    val metadata = metadataStore.changes(userId, LogDateCollectionKind.ASSOCIATION, since, limit)
    return LogDateChangeSet(
        changes =
            repoEngine
                .getRecords(
                    metadata.changes.map {
                        repoRecordIdForAssociation(repoDid = repoDid, recordKey = it.recordKey)
                    },
                ).getOrThrow()
                .zip(metadata.changes)
                .mapNotNull { (record, change) ->
                    record
                        ?.value
                        ?.toLogDateAssociation(recordKey = RecordKey.require(change.recordKey), version = change.version)
                        ?: missingRecord(userId, LogDateCollectionKind.ASSOCIATION, change.recordKey)
                },
        deletions =
            metadata.deletions.map { deletion ->
                LogDateAssociationDeletion(
                    association =
                        LogDateAssociationRef(
                            journalId = journalIdFromAssociationRecordKey(RecordKey.require(deletion.recordKey)),
                            entryId = entryIdFromAssociationRecordKey(RecordKey.require(deletion.recordKey)),
                        ),
                    deletedAt = requireNotNull(deletion.deletedAt),
                    serverVersion = deletion.version,
                )
            },
        lastTimestamp = metadata.lastTimestamp,
        hasMore = metadata.hasMore,
    )
}

internal suspend fun RepoBackedLogDateCollectionsRepository.repoUpsertDraft(
    userId: UUID,
    draft: LogDateDraft,
): LogDateDraft {
    metadataStore.requireWritable(userId, LogDateCollectionKind.DRAFT, draft.id)
    val repoDid = canonicalRepoDid(userId)
    val recordId = draftRecordId(repoDid, draft.id)
    val prior = repoEngine.getRecord(recordId).getOrThrow()
    if (draft.encryptedBlocks == null && prior?.value?.stringValue("encryptedBlocks") != null) {
        throw DraftFormatUpgradeRequiredException()
    }
    val result =
        if (draft.encryptedBlocks == null && prior == null) {
            repoEngine.putRecordIfAbsent(recordId, draft.toRepoJson())
        } else {
            repoEngine.putRecord(
                recordId,
                draft.toRepoJson(),
                swapRecord = if (draft.encryptedBlocks == null) prior?.cid else null,
            )
        }
    result.exceptionOrNull()?.let { failure ->
        if (draft.encryptedBlocks == null &&
            repoEngine
                .getRecord(recordId)
                .getOrThrow()
                ?.value
                ?.stringValue("encryptedBlocks") != null
        ) {
            throw DraftFormatUpgradeRequiredException()
        }
        throw failure
    }
    val metadata = indexWrittenRecord(userId, repoDid, LogDateCollectionKind.DRAFT, recordId, result.getOrThrow())
    return draft.copy(version = metadata.version, lastUpdated = System.currentTimeMillis())
}

internal suspend fun RepoBackedLogDateCollectionsRepository.repoGetDraft(
    userId: UUID,
    id: String,
): LogDateDraft? {
    val metadata = metadataStore.metadata(userId, LogDateCollectionKind.DRAFT, id) ?: return null
    val repoDid = canonicalRepoDid(userId)
    return repoEngine
        .getRecord(draftRecordId(repoDid, id))
        .getOrThrow()
        ?.value
        ?.toLogDateDraft(recordKey = RecordKey.require(id), version = metadata.version)
}

internal suspend fun RepoBackedLogDateCollectionsRepository.repoDeleteDraft(
    userId: UUID,
    id: String,
    deletedAt: Long,
) {
    val repoDid = canonicalRepoDid(userId)
    deleteCanonicalRecord(
        userId = userId,
        repoDid = repoDid,
        collection = LogDateCollectionKind.DRAFT,
        recordId = draftRecordId(repoDid, id),
        deletedAt = deletedAt,
    )
}

internal suspend fun RepoBackedLogDateCollectionsRepository.repoDraftChanges(
    userId: UUID,
    since: Long,
    limit: Int,
): LogDateChangeSet<LogDateDraft, LogDateDraftDeletion> {
    val repoDid = canonicalRepoDid(userId)
    val metadata = metadataStore.changes(userId, LogDateCollectionKind.DRAFT, since, limit)
    return LogDateChangeSet(
        changes =
            repoEngine
                .getRecords(metadata.changes.map { draftRecordId(repoDid, it.recordKey) })
                .getOrThrow()
                .zip(metadata.changes)
                .mapNotNull { (record, change) ->
                    record
                        ?.value
                        ?.toLogDateDraft(recordKey = RecordKey.require(change.recordKey), version = change.version)
                },
        deletions =
            metadata.deletions.map { deletion ->
                LogDateDraftDeletion(
                    id = deletion.recordKey,
                    deletedAt = requireNotNull(deletion.deletedAt),
                    serverVersion = deletion.version,
                )
            },
        lastTimestamp = metadata.lastTimestamp,
        hasMore = metadata.hasMore,
    )
}
