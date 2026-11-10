package app.logdate.client.sync

import app.logdate.client.sync.cloud.RemoteRecordFailure
import app.logdate.client.sync.conflict.ConflictResolver
import app.logdate.client.sync.metadata.EntityType
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Data class to hold batch operation results within transactions.
 * Allows transactional batch applies to return result data that is used
 * after the transaction commits.
 */
internal data class BatchResult(
    val downloadedCount: Int,
    val conflictsResolved: Int,
    val errors: List<SyncError>,
)

/** The shape [app.logdate.client.sync.cloud.CloudJournalDataSource.getJournalChanges]/[app.logdate.client.sync.cloud.CloudContentDataSource.getContentChanges] share. */
internal data class ChangesPage<T>(
    val changes: List<T>,
    val deletions: List<Uuid>,
    val lastSyncTimestamp: Instant,
    val hasMore: Boolean,
    val unreadable: List<Uuid> = emptyList(),
    val failures: List<RemoteRecordFailure> = emptyList(),
    val unreadableVersions: Map<Uuid, Long> = emptyMap(),
)

/**
 * Everything [SyncDownloadEngine] needs to know about one [EntityType] to run the shared
 * download-paginate-resolve-conflicts sequence: how to fetch a page, read an item's identity
 * and versioning, resolve a conflict, and apply the outcome locally.
 *
 * @property entityType The metadata entity type tracked for pending uploads and sync cursors.
 * @property logLabel Human-readable singular entity name used in logs and error messages.
 * @property fetchChanges Fetches one page of changes and tombstones after the given cursor.
 * @property localItem Reads the current local copy of an item by its id, or null if absent.
 * @property idOf Extracts the unique identifier from a domain item.
 * @property syncVersionOf Extracts the sync version recorded on a domain item.
 * @property lastUpdatedOf Extracts the last-modified timestamp from a domain item.
 * @property conflictResolver Resolves a concurrent edit when both local and remote copies changed.
 * @property applyCreate Persists a newly downloaded item locally.
 * @property applyReplace Replaces an existing local item with the resolved remote or merged copy.
 * @property applyDelete Deletes the local item identified by a remote tombstone.
 * @property hydrate Fetches any additional remote payload needed before persisting an item.
 * @property afterDelete Runs post-delete cleanup inside the deletion transaction.
 * @property sameUploadedFields Returns true when local and remote copies already carry the same synced fields.
 * @property sameCreateBaseFields Returns true when a pending local create matches the remote record's base fields.
 * @property acknowledgeUpload Settles a pending upload when a remote record already reflects it.
 */
internal class DownloadStrategy<T : Any>(
    val entityType: EntityType,
    val logLabel: String,
    val fetchChanges: suspend (accessToken: String, cursor: Instant) -> Result<ChangesPage<T>>,
    val localItem: suspend (Uuid) -> T?,
    val idOf: (T) -> Uuid,
    val syncVersionOf: (T) -> Long,
    val lastUpdatedOf: (T) -> Instant,
    val conflictResolver: ConflictResolver<T>,
    val applyCreate: suspend (T) -> Unit,
    val applyReplace: suspend (existing: T, replacement: T) -> Unit,
    val applyDelete: suspend (Uuid) -> Unit,
    val hydrate: suspend (accessToken: String, item: T) -> T = { _, item -> item },
    val afterDelete: suspend (Uuid) -> Unit = {},
    val sameUploadedFields: suspend (local: T, remote: T) -> Boolean = { _, _ -> false },
    val sameCreateBaseFields: suspend (local: T, remote: T) -> Boolean = { _, _ -> false },
    val acknowledgeUpload: suspend (local: T, remote: T) -> Boolean = { _, _ -> false },
    /** Whether a record only the server has is stored here; a record already here is always updated. */
    val appliesRemoteCreate: (T) -> Boolean = { true },
    /** Whether an applied record's media is queued for download; off where media is fetched on demand. */
    val enqueuesMediaRecovery: Boolean = true,
    /** The download checkpoint whose fetch failure this download reports. */
    val checkpoint: String = entityType.name,
    /**
     * Settles the records behind each remote item inside the transaction that applied it, in place of
     * the inbox row under the item's id. A personal-space read records the versions it applied here.
     */
    val settle: (suspend (id: Uuid, outcome: RemoteOutcome) -> Unit)? = null,
    /** Reports an item that failed to apply, for a [settle] strategy. */
    val settleFailed: suspend (id: Uuid) -> Unit = {},
    /**
     * Whether a remote change to an item with no local change waiting is applied as it is. The
     * personal space only offers changes made since this device last applied the item, so with
     * nothing changed here there is nothing to resolve; a local change still waiting is resolved
     * when it uploads, against the version it then finds.
     */
    val appliesRemoteOverUnchangedLocal: Boolean = false,
)

/** What became of one remote item or deletion. */
internal enum class RemoteOutcome {
    /** The remote version is now the local one. */
    STORED,

    /** The resolver merged it into the local copy, which is queued to upload. */
    MERGED,

    /** The resolver kept the local copy, which is queued to upload over it. */
    KEPT_LOCAL,

    /** Not applied: a local change waits, a newer copy is here, the conflict awaits review, or this device doesn't keep it. */
    DECLINED,

    /** The deletion was applied. */
    DELETED,
}

internal fun conflictTimestamps(
    localSyncVersion: Long,
    localUpdatedAt: Instant,
    remoteSyncVersion: Long,
    remoteUpdatedAt: Instant,
): Pair<Instant, Instant> =
    if (localSyncVersion > 0L && remoteSyncVersion > 0L) {
        Instant.fromEpochMilliseconds(localSyncVersion) to Instant.fromEpochMilliseconds(remoteSyncVersion)
    } else {
        localUpdatedAt to remoteUpdatedAt
    }
