package app.logdate.client.sync

import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.SyncDeadLetterRecord
import app.logdate.client.sync.metadata.SyncDeadLetterStore
import app.logdate.client.sync.metadata.SyncMetadataService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlin.time.Instant

/** Keep errors visible only while their exact queued operation is still pending. */
internal fun SyncDeadLetterStore.observeQueued(metadata: SyncMetadataService): Flow<List<SyncDeadLetterRecord>> =
    combine(observe(), metadata.observePendingUploads()) { issues, pending ->
        issues.filter { issue ->
            pending.any { queued ->
                issue.entityType == queued.entityType?.name &&
                    issue.entityId == queued.entityId &&
                    issue.scope == queued.scope &&
                    issue.operationId == queued.operationId &&
                    issue.operation == queued.operation.name &&
                    issue.expectedServerVersion == queued.expectedServerVersion
            }
        }
    }

internal suspend fun SyncMetadataService.latestEntitySyncTime(): Instant? =
    listOf(
        getLastSyncTime(EntityType.JOURNAL),
        getLastSyncTime(EntityType.NOTE),
        getLastSyncTime(EntityType.ASSOCIATION),
        getLastSyncTime(EntityType.MEDIA),
        getLastSyncTime(EntityType.DRAFT),
        getLastSyncTime(EntityType.MOMENT),
    ).filterNotNull().maxOrNull()
