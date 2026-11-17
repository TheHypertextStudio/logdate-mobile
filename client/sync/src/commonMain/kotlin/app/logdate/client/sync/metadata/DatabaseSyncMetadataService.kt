package app.logdate.client.sync.metadata

import app.logdate.client.database.dao.sync.SyncMetadataDao
import app.logdate.client.database.entities.sync.PendingUploadEntity
import app.logdate.client.database.entities.sync.SyncCursorEntity
import app.logdate.client.device.identity.CanonicalOwnerProvider
import app.logdate.shared.config.LogDateConfigRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Room-backed implementation of [SyncMetadataService].
 *
 * Persists sync cursors and pending uploads to the local database. The outbox is local-first:
 * a missing Cloud session pauses transport but never suppresses or deletes a local mutation.
 */
class DatabaseSyncMetadataService(
    private val dao: SyncMetadataDao,
    private val configRepository: LogDateConfigRepository,
    private val canonicalOwnerProvider: CanonicalOwnerProvider,
) : SyncMetadataService {
    private val metadataMutex = Mutex()

    override suspend fun getPendingUploads(entityType: EntityType): List<PendingUpload> =
        metadataMutex.withLock {
            val serverOrigin = currentOrigin()
            val ownerId = currentOwnerId()
            promoteLegacyPendingIfNeeded(ownerId, serverOrigin, entityType)
            dao.getPendingByType(ownerId, serverOrigin, entityType.name).map { entity ->
                PendingUpload(
                    entityId = entity.entityId,
                    operation = PendingOperation.fromStorage(entity.operation),
                    retryCount = entity.retryCount,
                    expectedServerVersion = entity.expectedServerVersion,
                    scope = UploadScope(entity.ownerId, entity.serverOrigin),
                    operationId = entity.operationId,
                )
            }
        }

    override suspend fun getPendingUploadForScope(
        entityType: EntityType,
        entityId: String,
        scope: UploadScope,
    ): PendingUpload? =
        dao.getPending(scope.ownerId, scope.serverOrigin, entityType.name, entityId)?.let { entity ->
            PendingUpload(
                entity.entityId,
                PendingOperation.fromStorage(entity.operation),
                entity.retryCount,
                entity.expectedServerVersion,
                scope,
                entity.operationId,
            )
        }

    override suspend fun hasPending(
        entityType: EntityType,
        entityId: String,
    ): Boolean {
        val origin = currentOrigin()
        val owner = currentOwnerId()
        // A read inside a Room apply transaction must not invert the metadataMutex/Room lock order.
        return dao.getPending(owner, origin, entityType.name, entityId) != null ||
            dao.getPending(LEGACY_OWNER_ID, origin, entityType.name, entityId) != null
    }

    override suspend fun markAsSynced(
        entityId: String,
        entityType: EntityType,
        syncedAt: Instant,
        version: Long,
    ) {
        metadataMutex.withLock {
            val serverOrigin = currentOrigin()
            dao.deletePending(currentOwnerId(), serverOrigin, entityType.name, entityId)
            // Promotion copies a legacy row forward instead of moving it, so the owner-less original
            // outlives the copy. Left behind, it is promoted again the next time this entity type is
            // read -- refilling the outbox after every successful sync, forever. Settling the entity
            // has to retire both.
            dao.deletePending(LEGACY_OWNER_ID, serverOrigin, entityType.name, entityId)
        }
    }

    override suspend fun settleIfCurrent(
        entityType: EntityType,
        pending: PendingUpload,
        syncedAt: Instant,
        version: Long,
    ): Boolean {
        val scope = pending.scope ?: return false
        val operationId = pending.operationId ?: return false
        return dao.deletePendingIfCurrent(
            scope.ownerId,
            scope.serverOrigin,
            entityType.name,
            pending.entityId,
            operationId,
        )
    }

    override suspend fun isCurrentOperation(
        entityType: EntityType,
        pending: PendingUpload,
    ): Boolean {
        val scope = pending.scope ?: return false
        val operationId = pending.operationId ?: return false
        return dao.getPending(scope.ownerId, scope.serverOrigin, entityType.name, pending.entityId)?.operationId == operationId
    }

    override suspend fun incrementRetryIfCurrent(
        entityType: EntityType,
        pending: PendingUpload,
    ): Boolean {
        val scope = pending.scope ?: return false
        val operationId = pending.operationId ?: return false
        return dao.incrementRetryIfCurrent(scope.ownerId, scope.serverOrigin, entityType.name, pending.entityId, operationId)
    }

    override suspend fun getLastSyncTime(entityType: EntityType): Instant? {
        val serverOrigin = currentOrigin()
        val ownerId = currentOwnerId()
        promoteLegacyCursorIfNeeded(ownerId, serverOrigin, entityType)
        // A cursor row can exist before anything has actually synced, carrying a zero
        // timestamp. Mapping that straight through reports the Unix epoch as a sync time -
        // "Last synced: December 31, 1969" in any timezone west of UTC - instead of letting
        // the caller fall back to "never synced".
        return dao
            .getCursor(ownerId, serverOrigin, entityType.name)
            ?.lastSyncTimestamp
            ?.takeIf { it > 0L }
            ?.let { Instant.fromEpochMilliseconds(it) }
    }

    override suspend fun updateLastSyncTime(
        entityType: EntityType,
        syncedAt: Instant,
    ) {
        updateCursorIfNewer(entityType, syncedAt)
    }

    override suspend fun enqueuePending(
        entityId: String,
        entityType: EntityType,
        operation: PendingOperation,
    ) {
        val serverOrigin = currentOrigin()
        val ownerId = currentOwnerId()
        dao.enqueueMutation(
            PendingUploadEntity(
                ownerId = ownerId,
                serverOrigin = serverOrigin,
                entityType = entityType.name,
                entityId = entityId,
                operation = operation.name,
                createdAt = Clock.System.now().toEpochMilliseconds(),
                retryCount = 0,
            ),
        )
    }

    override suspend fun enqueuePendingForScope(
        entityId: String,
        entityType: EntityType,
        operation: PendingOperation,
        scope: UploadScope,
    ) {
        enqueueScopedPending(entityId, entityType, operation, scope)
    }

    override suspend fun enqueueRepairForScopeIfAbsent(
        entityId: String,
        entityType: EntityType,
        scope: UploadScope,
    ) {
        require(scope.ownerId.isNotBlank() && scope.serverOrigin.isNotBlank())
        dao.insertRepairIfAbsent(
            scope.ownerId,
            scope.serverOrigin,
            entityType.name,
            entityId,
            Clock.System.now().toEpochMilliseconds(),
            null,
        )
    }

    private suspend fun enqueueScopedPending(
        entityId: String,
        entityType: EntityType,
        operation: PendingOperation,
        scope: UploadScope,
    ) {
        require(scope.ownerId.isNotBlank() && scope.serverOrigin.isNotBlank())
        val ownerId = scope.ownerId
        val serverOrigin = scope.serverOrigin
        dao.enqueueMutation(
            PendingUploadEntity(
                ownerId = ownerId,
                serverOrigin = serverOrigin,
                entityType = entityType.name,
                entityId = entityId,
                operation = operation.name,
                createdAt = Clock.System.now().toEpochMilliseconds(),
                retryCount = 0,
            ),
        )
    }

    override suspend fun enqueueCreateIfAbsent(
        entityId: String,
        entityType: EntityType,
    ) {
        val origin = currentOrigin()
        val owner = currentOwnerId()
        dao.insertRepairIfAbsent(
            owner,
            origin,
            entityType.name,
            entityId,
            Clock.System.now().toEpochMilliseconds(),
            operation = PendingOperation.CREATE.name,
        )
    }

    override suspend fun hasPendingDeletion(
        entityId: String,
        entityType: EntityType,
        scope: UploadScope?,
    ): Boolean {
        val selected = scope ?: UploadScope(currentOwnerId(), currentOrigin())
        return dao.getPending(selected.ownerId, selected.serverOrigin, entityType.name, entityId)?.operation == "DELETE" ||
            dao.getPending(LEGACY_OWNER_ID, selected.serverOrigin, entityType.name, entityId)?.operation == "DELETE"
    }

    override suspend fun enqueueRestoreMutation(
        entityId: String,
        entityType: EntityType,
        operation: PendingOperation,
        scope: UploadScope?,
    ): Boolean {
        val selected = scope ?: UploadScope(currentOwnerId(), currentOrigin())
        require(selected.ownerId.isNotBlank() && selected.serverOrigin.isNotBlank())
        return dao.enqueueRestoreMutation(
            selected.ownerId,
            selected.serverOrigin,
            entityId,
            entityType.name,
            operation.name,
            Clock.System.now().toEpochMilliseconds(),
        )
    }

    override suspend fun enqueueTranscriptMutation(noteId: String): Boolean =
        dao.enqueueTranscriptMutation(currentOwnerId(), currentOrigin(), noteId, Clock.System.now().toEpochMilliseconds())

    override suspend fun bindCreateToServerVersion(
        entityType: EntityType,
        pending: PendingUpload,
        serverVersion: Long,
    ): Boolean {
        val selected = pending.scope ?: return false
        val operationId = pending.operationId ?: return false
        if (pending.operation != PendingOperation.CREATE ||
            serverVersion <= 0 ||
            selected.ownerId != currentOwnerId() ||
            selected.serverOrigin != currentOrigin()
        ) {
            return false
        }
        return dao.bindCreateToServerVersion(
            selected.ownerId,
            selected.serverOrigin,
            entityType.name,
            pending.entityId,
            operationId,
            serverVersion,
        )
    }

    override suspend fun advancePendingVersionAfterUpload(
        entityType: EntityType,
        uploaded: PendingUpload,
        serverVersion: Long,
    ): Boolean {
        val scope = uploaded.scope ?: return false
        val base = uploaded.expectedServerVersion ?: return false
        return dao.advancePendingVersionAfterUpload(
            scope.ownerId,
            scope.serverOrigin,
            entityType.name,
            uploaded.entityId,
            base,
            serverVersion,
        )
    }

    override suspend fun enqueueRepairIfAbsent(
        entityId: String,
        entityType: EntityType,
        expectedServerVersion: Long?,
        serverOrigin: String?,
    ) {
        val origin = serverOrigin ?: currentOrigin()
        val owner = currentOwnerId()
        check(origin == currentOrigin()) { "Sync scope changed" }
        // One SQL statement protects both scoped and legacy mutations without a Room/mutex inversion.
        dao.insertRepairIfAbsent(owner, origin, entityType.name, entityId, Clock.System.now().toEpochMilliseconds(), expectedServerVersion)
    }

    override suspend fun resetSyncStatus(
        entityId: String,
        entityType: EntityType,
    ) {
        enqueuePending(entityId, entityType, PendingOperation.UPDATE)
    }

    override suspend fun getPendingCount(): Int =
        metadataMutex.withLock {
            val ownerId = currentOwnerId()
            val serverOrigin = currentOrigin()
            EntityType.entries.forEach { promoteLegacyPendingIfNeeded(ownerId, serverOrigin, it) }
            dao.getPendingCount(ownerId, serverOrigin)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observePendingCount(): Flow<Int> =
        configRepository.backendUrl.flatMapLatest {
            flow {
                getPendingCount()
                emitAll(dao.observePendingCount(currentOwnerId(), currentOrigin()))
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observePendingUploads(): Flow<List<QueuedUpload>> =
        configRepository.backendUrl.flatMapLatest {
            flow {
                // Promotes any legacy rows first, the same way observePendingCount does, so the list
                // and the count agree.
                getPendingCount()
                emitAll(
                    dao.observePending(currentOwnerId(), currentOrigin()).map { rows ->
                        rows.map { row ->
                            QueuedUpload(
                                entityType = EntityType.entries.firstOrNull { it.name == row.entityType },
                                entityId = row.entityId,
                                operation = PendingOperation.fromStorage(row.operation),
                                retryCount = row.retryCount,
                                scope = UploadScope(row.ownerId, row.serverOrigin),
                                operationId = row.operationId,
                                expectedServerVersion = row.expectedServerVersion,
                            )
                        }
                    },
                )
            }
        }

    override suspend fun clearPending() {
        // Origin-scoped: only clears the queue tied to the current backend, not other backends
        // the user may have used. Cursors are intentionally preserved.
        dao.deletePendingForOrigin(currentOwnerId(), currentOrigin())
    }

    override suspend fun incrementRetryCount(
        entityId: String,
        entityType: EntityType,
    ) {
        dao.incrementRetryCount(currentOwnerId(), currentOrigin(), entityType.name, entityId)
    }

    /**
     * Adds an entity to the pending upload queue.
     */
    suspend fun addPendingUpload(
        entityId: String,
        entityType: EntityType,
        operation: String,
    ) {
        enqueuePending(entityId, entityType, PendingOperation.fromStorage(operation))
    }

    /**
     * Updates the sync cursor for a specific entity type.
     */
    suspend fun updateCursor(
        entityType: EntityType,
        timestamp: Instant,
    ) {
        updateCursorIfNewer(entityType, timestamp)
    }

    /**
     * Clears all sync metadata (for logout/reset).
     */
    suspend fun clearAll() {
        dao.deletePendingForOrigin(currentOwnerId(), currentOrigin())
        resetAllCursors()
    }

    override suspend fun resetAllCursors() {
        dao.deleteCursorsForOrigin(currentOwnerId(), currentOrigin())
    }

    private suspend fun updateCursorIfNewer(
        entityType: EntityType,
        syncedAt: Instant,
    ) {
        val serverOrigin = currentOrigin()
        val ownerId = currentOwnerId()
        promoteLegacyCursorIfNeeded(ownerId, serverOrigin, entityType)
        val current = dao.getCursor(ownerId, serverOrigin, entityType.name)?.lastSyncTimestamp ?: 0L
        val next = syncedAt.toEpochMilliseconds()
        if (next >= current) {
            dao.upsertCursor(
                SyncCursorEntity(
                    ownerId = ownerId,
                    serverOrigin = serverOrigin,
                    entityType = entityType.name,
                    lastSyncTimestamp = next,
                ),
            )
        }
    }

    private fun currentOrigin(): String = configRepository.getCurrentBackendUrl().trimEnd('/')

    private suspend fun currentOwnerId(): String = canonicalOwnerProvider.getCanonicalOwnerId()

    private suspend fun promoteLegacyCursorIfNeeded(
        ownerId: String,
        serverOrigin: String,
        entityType: EntityType,
    ) {
        if (dao.getCursor(ownerId, serverOrigin, entityType.name) != null) {
            return
        }

        val legacyCursor = dao.getLegacyCursor(serverOrigin, entityType.name) ?: return
        dao.upsertCursor(legacyCursor.copy(ownerId = ownerId))
    }

    private suspend fun promoteLegacyPendingIfNeeded(
        ownerId: String,
        serverOrigin: String,
        entityType: EntityType,
    ) {
        if (dao.getPendingByType(ownerId, serverOrigin, entityType.name).isNotEmpty()) {
            return
        }

        val legacyPending = dao.getLegacyPendingByType(serverOrigin, entityType.name)
        if (legacyPending.isEmpty()) {
            return
        }

        legacyPending.forEach { pending ->
            dao.promotePendingIfAbsent(pending.copy(ownerId = ownerId))
        }
    }

    private companion object {
        /** Rows written before sync metadata was scoped by owner. */
        const val LEGACY_OWNER_ID = ""
    }
}
