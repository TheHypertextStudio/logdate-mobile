package app.logdate.client.sync

import app.logdate.client.sync.conflict.SyncConflictRecord
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.UploadScope
import app.logdate.client.sync.recovery.DownloadScope
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Keep the existing seven-argument callable contract for retry, draft and association consumers. */
internal suspend fun SyncDownloadEngine.recordConflict(
    entityType: EntityType,
    entityId: String,
    reason: String,
    localVersion: Long?,
    remoteVersion: Long?,
    localUpdatedAt: Instant?,
    remoteUpdatedAt: Instant?,
) = recordConflict(entityType, entityId, reason, localVersion, remoteVersion, localUpdatedAt, remoteUpdatedAt, null, null)

internal suspend fun SyncDownloadEngine.recordConflict(
    entityType: EntityType,
    entityId: String,
    reason: String,
    localVersion: Long?,
    remoteVersion: Long?,
    localUpdatedAt: Instant?,
    remoteUpdatedAt: Instant?,
    textVersions: TextConflictVersions?,
    selectedScope: DownloadScope?,
    audioVersions: AudioConflictVersions? = null,
) {
    retainConflict(
        SyncConflictRecord(
            id = if (textVersions == null && audioVersions == null) "${entityType.name}:$entityId" else Uuid.random().toString(),
            entityType = entityType.name,
            entityId = entityId,
            localVersion = localVersion,
            remoteVersion = remoteVersion,
            localUpdatedAt = localUpdatedAt?.toEpochMilliseconds(),
            remoteUpdatedAt = remoteUpdatedAt?.toEpochMilliseconds(),
            reason = reason,
            detectedAt = Clock.System.now().toEpochMilliseconds(),
            scope = selectedScope?.let { UploadScope(it.owner, it.origin) },
            textVersions = textVersions,
            audioVersions = audioVersions,
        ),
        selectedScope,
    )
}
