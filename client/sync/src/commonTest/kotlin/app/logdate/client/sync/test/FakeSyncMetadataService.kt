package app.logdate.client.sync.test

import app.logdate.client.datastore.SessionStorage
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.PendingOperation
import app.logdate.client.sync.metadata.PendingUpload
import app.logdate.client.sync.metadata.QueuedUpload
import app.logdate.client.sync.metadata.SyncMetadataService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Fake SyncMetadataService for testing.
 */
class FakeSyncMetadataService(
    private val sessionStorage: SessionStorage = AlwaysAuthenticatedSessionStorage,
    private val trackOperationIdentity: Boolean = false,
    private val currentScope: () -> app.logdate.client.sync.metadata.UploadScope = {
        app.logdate.client.sync.metadata
            .UploadScope("fixture-owner", "fixture-server")
    },
) : SyncMetadataService {
    private val operationIds = mutableMapOf<Pair<EntityType, String>, String>()
    private val queuedFlow = MutableStateFlow<List<QueuedUpload>>(emptyList())
    private val pendingUploads = mutableMapOf<EntityType, MutableMap<String, PendingOperation>>()
    private val repairVersions = mutableMapOf<Pair<EntityType, String>, Long>()
    private val retryCounts = mutableMapOf<EntityType, MutableMap<String, Int>>()
    private val syncTimes = mutableMapOf<EntityType, Instant>()
    private val pendingCountFlow = MutableStateFlow(0)
    var clearPendingCalls: Int = 0

    /** How many times [getPendingCount] has actually been invoked -- see its call site for why. */
    var getPendingCountCalls: Int = 0
        private set

    /** Every [enqueuePending] invocation, in order -- lets a test prove a re-scan did or didn't happen. */
    val enqueuePendingCalls = mutableListOf<Pair<EntityType, String>>()

    override suspend fun getPendingUploads(entityType: EntityType): List<PendingUpload> =
        pendingUploads[entityType]
            ?.map { (entityId, operation) ->
                val retryCount = retryCounts[entityType]?.get(entityId) ?: 0
                PendingUpload(
                    entityId,
                    operation,
                    retryCount,
                    repairVersions[entityType to entityId],
                    scope =
                        if (trackOperationIdentity) {
                            currentScope()
                        } else {
                            null
                        },
                    operationId = operationIds[entityType to entityId],
                )
            }
            ?: emptyList()

    override suspend fun markAsSynced(
        entityId: String,
        entityType: EntityType,
        syncedAt: Instant,
        version: Long,
    ) {
        pendingUploads[entityType]?.remove(entityId)
        operationIds.remove(entityType to entityId)
        repairVersions.remove(entityType to entityId)
        retryCounts[entityType]?.remove(entityId)
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
        if (trackOperationIdentity) operationIds[entityType to entityId] = Uuid.random().toString()
        repairVersions.remove(entityType to entityId)
        enqueuePendingCalls += entityType to entityId
        val existing = pendingUploads[entityType]?.get(entityId)
        val resolved = PendingOperation.coalesce(existing, operation)
        if (resolved == null) {
            pendingUploads[entityType]?.remove(entityId)
            retryCounts[entityType]?.remove(entityId)
        } else {
            pendingUploads.getOrPut(entityType) { mutableMapOf() }[entityId] = resolved
            val counts = retryCounts.getOrPut(entityType) { mutableMapOf() }
            if (counts[entityId] == null || trackOperationIdentity) {
                counts[entityId] = 0
            }
        }
        updatePendingCount()
    }

    override suspend fun bindCreateToServerVersion(
        entityType: EntityType,
        pending: PendingUpload,
        serverVersion: Long,
    ): Boolean {
        if (pending.operation != PendingOperation.CREATE || !isCurrentOperation(entityType, pending)) return false
        pendingUploads.getValue(entityType)[pending.entityId] = PendingOperation.UPDATE
        if (trackOperationIdentity) operationIds[entityType to pending.entityId] = Uuid.random().toString()
        repairVersions[entityType to pending.entityId] = serverVersion
        retryCounts[entityType]?.set(pending.entityId, 0)
        updatePendingCount()
        return true
    }

    override suspend fun enqueueTranscriptMutation(noteId: String): Boolean {
        if (pendingUploads[EntityType.NOTE]?.get(noteId) == PendingOperation.DELETE) return false
        val expectedVersion = repairVersions[EntityType.NOTE to noteId]
        enqueuePending(noteId, EntityType.NOTE, PendingOperation.UPDATE)
        if (expectedVersion != null) repairVersions[EntityType.NOTE to noteId] = expectedVersion
        return true
    }

    override suspend fun advancePendingVersionAfterUpload(
        entityType: EntityType,
        uploaded: PendingUpload,
        serverVersion: Long,
    ): Boolean {
        val current = getPendingUploads(entityType).firstOrNull { it.entityId == uploaded.entityId } ?: return false
        val base = uploaded.expectedServerVersion ?: return false
        if (!trackOperationIdentity ||
            current.scope != uploaded.scope ||
            current.operation != PendingOperation.UPDATE ||
            current.expectedServerVersion != base ||
            serverVersion <= base
        ) {
            return false
        }
        repairVersions[entityType to uploaded.entityId] = serverVersion
        return true
    }

    override suspend fun isCurrentOperation(
        entityType: EntityType,
        pending: PendingUpload,
    ): Boolean =
        getPendingUploads(entityType).any {
            it.entityId == pending.entityId &&
                it.scope == pending.scope &&
                it.operationId == pending.operationId &&
                (trackOperationIdentity || (it.operation == pending.operation && it.expectedServerVersion == pending.expectedServerVersion))
        }

    override suspend fun enqueueRepairIfAbsent(
        entityId: String,
        entityType: EntityType,
        expectedServerVersion: Long?,
        serverOrigin: String?,
    ) {
        if (hasPending(entityType, entityId)) return
        enqueuePending(entityId, entityType, PendingOperation.UPDATE)
        expectedServerVersion?.let { repairVersions[entityType to entityId] = it }
    }

    override suspend fun resetSyncStatus(
        entityId: String,
        entityType: EntityType,
    ) {
        enqueuePending(entityId, entityType, PendingOperation.UPDATE)
        updatePendingCount()
    }

    override suspend fun getPendingCount(): Int {
        getPendingCountCalls += 1
        return pendingUploads.values.sumOf { it.size }
    }

    override fun observePendingCount(): Flow<Int> = pendingCountFlow

    override fun observePendingUploads(): Flow<List<QueuedUpload>> = queuedFlow

    override suspend fun clearPending() {
        clearPendingCalls += 1
        pendingUploads.clear()
        operationIds.clear()
        queuedFlow.value = emptyList()
        repairVersions.clear()
        retryCounts.clear()
        pendingCountFlow.value = 0
    }

    override suspend fun resetAllCursors() {
        syncTimes.clear()
    }

    override suspend fun incrementRetryCount(
        entityId: String,
        entityType: EntityType,
    ) {
        val counts = retryCounts.getOrPut(entityType) { mutableMapOf() }
        counts[entityId] = (counts[entityId] ?: 0) + 1
    }

    private fun updatePendingCount() {
        pendingCountFlow.value = pendingUploads.values.sumOf { it.size }
        queuedFlow.value =
            pendingUploads.flatMap { (type, values) ->
                values.map { (id, operation) ->
                    QueuedUpload(
                        type,
                        id,
                        operation,
                        retryCounts[type]?.get(id) ?: 0,
                        scope =
                            if (trackOperationIdentity) {
                                currentScope()
                            } else {
                                null
                            },
                        operationId = operationIds[type to id],
                        expectedServerVersion = repairVersions[type to id],
                    )
                }
            }
    }

    fun addPending(
        entityId: Uuid,
        entityType: EntityType,
        operation: PendingOperation = PendingOperation.UPDATE,
    ) {
        pendingUploads.getOrPut(entityType) { mutableMapOf() }[entityId.toString()] = operation
        updatePendingCount()
    }

    fun clear() {
        pendingUploads.clear()
        operationIds.clear()
        queuedFlow.value = emptyList()
        repairVersions.clear()
        retryCounts.clear()
        syncTimes.clear()
        pendingCountFlow.value = 0
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
}

/*
 * Fake JournalNotesRepository for testing.
 */
