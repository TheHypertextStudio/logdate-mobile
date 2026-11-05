package app.logdate.server.logdate

import io.github.aakira.napier.Napier
import kotlinx.serialization.json.JsonObject
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.repo.DefaultRepoEngine
import studio.hypertext.atproto.repo.InvalidSwapException
import studio.hypertext.atproto.repo.RepoRecordId
import java.util.UUID

/**
 * Create-only writes on the repo engine.
 *
 * The metadata row is read first, so a duplicate or a deleted ID is answered without touching the repo.
 * The write itself is [DefaultRepoEngine.putRecordIfAbsent], which fails with [InvalidSwapException] when
 * the key appeared in the meantime; the repo head's compare-and-swap makes that check atomic across
 * concurrent writers and server instances, so of two racing creates exactly one writes.
 */
internal class RepoConditionalWriter(
    private val repoEngine: DefaultRepoEngine,
    private val metadataStore: LogDateCollectionsMetadataStore,
    private val transaction: RepoWriteTransaction = RepoWriteTransaction.Direct,
) {
    suspend fun state(
        userId: UUID,
        collection: LogDateCollectionKind,
        recordKey: String,
    ): LogDateRecordState {
        metadataStore.metadata(userId, collection, recordKey)?.let { return LogDateRecordState.Live(it.version) }
        val tombstone = metadataStore.tombstone(userId, collection, recordKey) ?: return LogDateRecordState.Absent
        return LogDateRecordState.Deleted(requireNotNull(tombstone.deletedAt), tombstone.version)
    }

    /** Writes [value] at [recordId] only if no live record holds it; [ConditionalCreate.Created] carries the new version. */
    suspend fun createIfAbsent(
        userId: UUID,
        repoDid: AtprotoDid,
        collection: LogDateCollectionKind,
        recordId: RepoRecordId,
        value: JsonObject,
        respectTombstones: Boolean,
    ): ConditionalCreate<Long> =
        transaction.write(userId) {
            create(userId, repoDid, collection, recordId, value, respectTombstones)
        }

    private suspend fun create(
        userId: UUID,
        repoDid: AtprotoDid,
        collection: LogDateCollectionKind,
        recordId: RepoRecordId,
        value: JsonObject,
        respectTombstones: Boolean,
    ): ConditionalCreate<Long> {
        val recordKey = recordId.recordKey.toString()
        when (val state = state(userId, collection, recordKey)) {
            is LogDateRecordState.Live -> return ConditionalCreate.Exists(state.serverVersion)
            is LogDateRecordState.Deleted -> if (respectTombstones) return ConditionalCreate.Deleted(state.deletedAt, state.serverVersion)
            LogDateRecordState.Absent -> Unit
        }
        val written =
            repoEngine.putRecordIfAbsent(recordId, value).getOrElse { failure ->
                if (failure !is InvalidSwapException) throw failure
                return ConditionalCreate.Exists(indexedVersion(userId, repoDid, collection, recordId))
            }
        val metadata =
            try {
                metadataStore.upsert(userId, repoDid, collection, recordKey)
            } catch (failure: Throwable) {
                runCatching { repoEngine.deleteRecord(recordId, swapRecord = written.cid).getOrThrow() }
                    .onFailure { Napier.e("Conditional create could not remove its record after the metadata write failed", it) }
                throw failure
            }
        return ConditionalCreate.Created(metadata.version)
    }

    /**
     * The version of the record that blocked a create. A process may stop after its repo write but before
     * indexing it for sync, so a record with no metadata row is indexed here; a retry never replaces its body.
     */
    private suspend fun indexedVersion(
        userId: UUID,
        repoDid: AtprotoDid,
        collection: LogDateCollectionKind,
        recordId: RepoRecordId,
    ): Long? {
        val recordKey = recordId.recordKey.toString()
        metadataStore.metadata(userId, collection, recordKey)?.let { return it.version }
        if (repoEngine.getRecord(recordId).getOrThrow() == null) return null
        return metadataStore.upsert(userId, repoDid, collection, recordKey).version
    }
}
