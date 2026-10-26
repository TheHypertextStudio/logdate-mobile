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

/** Shared page shape for journal and content change feeds. */
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
 */
internal class DownloadStrategy<T : Any>(
    val entityType: EntityType,
    val logLabel: String,
    val fetchChanges: suspend (accessToken: String, cursor: Instant) -> Result<ChangesPage<T>>,
    val localItems: suspend () -> Map<Uuid, T>,
    val idOf: (T) -> Uuid,
    val syncVersionOf: (T) -> Long,
    val lastUpdatedOf: (T) -> Instant,
    val conflictResolver: ConflictResolver<T>,
    val applyCreate: suspend (T) -> Unit,
    val applyReplace: suspend (existing: T, replacement: T) -> Unit,
    val applyDelete: suspend (Uuid) -> Unit,
    val hydrate: suspend (accessToken: String, item: T) -> T = { _, item -> item },
    val afterDelete: suspend (Uuid) -> Unit = {},
    val localItem: suspend (Uuid) -> T? = { id -> localItems()[id] },
    val sameUploadedFields: suspend (local: T, remote: T) -> Boolean = { _, _ -> false },
    val sameCreateBaseFields: suspend (local: T, remote: T) -> Boolean = { _, _ -> false },
    val acknowledgeUpload: suspend (local: T, remote: T) -> Boolean = { _, _ -> false },
)

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
