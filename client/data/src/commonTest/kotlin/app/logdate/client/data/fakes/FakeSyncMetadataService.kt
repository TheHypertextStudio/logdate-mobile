package app.logdate.client.data.fakes

import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.PendingOperation
import app.logdate.client.sync.metadata.PendingUpload
import app.logdate.client.sync.metadata.QueuedUpload
import app.logdate.client.sync.metadata.SyncMetadataService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlin.time.Instant

open class FakeSyncMetadataService : SyncMetadataService {
    private val pendingUploads = mutableMapOf<EntityType, MutableMap<String, PendingUpload>>()
    private val syncTimes = mutableMapOf<EntityType, Instant>()
    private val _pendingCount = MutableStateFlow(0)

    override suspend fun getPendingUploads(entityType: EntityType): List<PendingUpload> =
        pendingUploads[entityType]?.values?.toList() ?: emptyList()

    override suspend fun markAsSynced(
        entityId: String,
        entityType: EntityType,
        syncedAt: Instant,
        version: Long,
    ) {
        pendingUploads[entityType]?.remove(entityId)
        updatePendingCount()
    }

    override suspend fun getLastSyncTime(entityType: EntityType): Instant? = syncTimes[entityType]

    override suspend fun updateLastSyncTime(
        entityType: EntityType,
        syncedAt: Instant,
    ) {
        updateSyncTime(entityType, syncedAt)
    }

    override suspend fun enqueuePending(
        entityId: String,
        entityType: EntityType,
        operation: PendingOperation,
    ) {
        val existing = pendingUploads[entityType]?.get(entityId)
        val resolved = resolveOperation(existing?.operation, operation)
        if (resolved == null) {
            pendingUploads[entityType]?.remove(entityId)
        } else {
            val retryCount = existing?.retryCount ?: 0
            pendingUploads.getOrPut(entityType) { mutableMapOf() }[entityId] =
                PendingUpload(entityId, resolved, retryCount)
        }
        updatePendingCount()
    }

    override suspend fun resetSyncStatus(
        entityId: String,
        entityType: EntityType,
    ) {
        pendingUploads.getOrPut(entityType) { mutableMapOf() }[entityId] =
            PendingUpload(entityId, PendingOperation.UPDATE, retryCount = 0)
        updatePendingCount()
    }

    override suspend fun getPendingCount(): Int = pendingUploads.values.sumOf { it.size }

    override fun observePendingCount(): Flow<Int> = _pendingCount

    override fun observePendingUploads(): Flow<List<QueuedUpload>> = flowOf(emptyList())

    override suspend fun incrementRetryCount(
        entityId: String,
        entityType: EntityType,
    ) {
        val existing = pendingUploads[entityType]?.get(entityId) ?: return
        pendingUploads.getOrPut(entityType) { mutableMapOf() }[entityId] =
            existing.copy(retryCount = existing.retryCount + 1)
    }

    override suspend fun clearPending() {
        pendingUploads.clear()
        _pendingCount.value = 0
    }

    override suspend fun resetAllCursors() {
        syncTimes.clear()
    }

    fun snapshot(): Map<EntityType, List<PendingUpload>> = pendingUploads.mapValues { (_, uploads) -> uploads.values.toList() }

    fun restore(snapshot: Map<EntityType, List<PendingUpload>>) {
        pendingUploads.clear()
        snapshot.forEach { (entityType, uploads) ->
            pendingUploads[entityType] = uploads.associateByTo(mutableMapOf()) { it.entityId }
        }
        updatePendingCount()
    }

    private fun updatePendingCount() {
        _pendingCount.value = pendingUploads.values.sumOf { it.size }
    }

    private fun updateSyncTime(
        entityType: EntityType,
        syncedAt: Instant,
    ) {
        val current = syncTimes[entityType]
        if (current == null || syncedAt >= current) {
            syncTimes[entityType] = syncedAt
        }
    }

    private fun resolveOperation(
        existing: PendingOperation?,
        incoming: PendingOperation,
    ): PendingOperation? {
        if (existing == null) return incoming
        return when (existing) {
            PendingOperation.CREATE -> {
                when (incoming) {
                    PendingOperation.UPDATE -> PendingOperation.CREATE
                    PendingOperation.DELETE -> null
                    PendingOperation.CREATE -> PendingOperation.CREATE
                }
            }

            PendingOperation.UPDATE -> {
                when (incoming) {
                    PendingOperation.DELETE -> PendingOperation.DELETE
                    PendingOperation.CREATE -> PendingOperation.CREATE
                    PendingOperation.UPDATE -> PendingOperation.UPDATE
                }
            }

            PendingOperation.DELETE -> {
                when (incoming) {
                    PendingOperation.CREATE -> PendingOperation.CREATE
                    PendingOperation.UPDATE -> PendingOperation.CREATE
                    PendingOperation.DELETE -> PendingOperation.DELETE
                }
            }
        }
    }
}
