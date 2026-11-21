package app.logdate.client.database.dao.sync

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.logdate.client.database.entities.sync.PendingOperation
import app.logdate.client.database.entities.sync.PendingUploadEntity
import app.logdate.client.database.entities.sync.SyncCursorEntity
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

/**
 * Data Access Object for sync metadata operations.
 */
@Dao
interface SyncMetadataDao {
    // --- Sync Cursors ---

    @Query("SELECT * FROM sync_cursors WHERE ownerId = :ownerId AND serverOrigin = :serverOrigin AND entityType = :entityType")
    suspend fun getCursor(
        ownerId: String,
        serverOrigin: String,
        entityType: String,
    ): SyncCursorEntity?

    @Query("SELECT * FROM sync_cursors WHERE ownerId = '' AND serverOrigin = :serverOrigin AND entityType = :entityType")
    suspend fun getLegacyCursor(
        serverOrigin: String,
        entityType: String,
    ): SyncCursorEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCursor(cursor: SyncCursorEntity)

    @Query("DELETE FROM sync_cursors WHERE ownerId = :ownerId AND serverOrigin = :serverOrigin")
    suspend fun deleteCursorsForOrigin(
        ownerId: String,
        serverOrigin: String,
    )

    @Query("DELETE FROM sync_cursors WHERE ownerId = '' AND serverOrigin = :serverOrigin AND entityType = :entityType")
    suspend fun deleteLegacyCursor(
        serverOrigin: String,
        entityType: String,
    )

    // --- Pending Uploads ---

    @Query(
        "SELECT * FROM pending_uploads WHERE ownerId = :ownerId AND serverOrigin = :serverOrigin AND entityType = :entityType ORDER BY createdAt ASC",
    )
    suspend fun getPendingByType(
        ownerId: String,
        serverOrigin: String,
        entityType: String,
    ): List<PendingUploadEntity>

    @Query(
        "SELECT EXISTS(SELECT 1 FROM pending_uploads WHERE ownerId = :ownerId AND serverOrigin = :serverOrigin AND entityType = :entityType)",
    )
    suspend fun hasPendingOfType(
        ownerId: String,
        serverOrigin: String,
        entityType: String,
    ): Boolean

    @Query(
        "SELECT * FROM pending_uploads WHERE ownerId = '' AND serverOrigin = :serverOrigin AND entityType = :entityType ORDER BY createdAt ASC",
    )
    suspend fun getLegacyPendingByType(
        serverOrigin: String,
        entityType: String,
    ): List<PendingUploadEntity>

    @Query(
        "SELECT * FROM pending_uploads WHERE ownerId = :ownerId AND serverOrigin = :serverOrigin AND entityType = :entityType AND entityId = :entityId",
    )
    suspend fun getPending(
        ownerId: String,
        serverOrigin: String,
        entityType: String,
        entityId: String,
    ): PendingUploadEntity?

    @Query("SELECT * FROM pending_uploads ORDER BY createdAt ASC")
    suspend fun getAllPending(): List<PendingUploadEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPending(pending: PendingUploadEntity)

    /** Serialize queue mutations with their note transaction, without a separate service lock. */
    @Transaction
    suspend fun enqueueMutation(pending: PendingUploadEntity) {
        val owner = pending.ownerId
        val origin = pending.serverOrigin
        val type = pending.entityType
        if (!hasPendingOfType(owner, origin, type)) {
            getLegacyPendingByType(origin, type).forEach { promotePendingIfAbsent(it.copy(ownerId = owner)) }
        }
        val existing = getPending(owner, origin, type, pending.entityId)
        insertPending(
            pending.copy(
                operation = PendingOperation.coalesce(existing?.operation, pending.operation),
                createdAt = existing?.createdAt ?: pending.createdAt,
            ),
        )
    }

    /** A legacy snapshot cannot replace a mutation queued after it was read. */
    @Transaction
    suspend fun promotePendingIfAbsent(pending: PendingUploadEntity) {
        val legacy = getPending("", pending.serverOrigin, pending.entityType, pending.entityId)
        if (legacy?.copy(ownerId = pending.ownerId) != pending) return
        if (getPending(pending.ownerId, pending.serverOrigin, pending.entityType, pending.entityId) == null) {
            insertPending(pending)
        }
    }

    /**
     * Returns the owner's queue for a type, first copying any legacy owner-less rows forward when the
     * owner has none. One Room transaction makes the check and copy atomic, so callers inside another
     * transaction join it instead of waiting on a separate lock.
     */
    @Transaction
    suspend fun getPendingByTypePromotingLegacy(
        ownerId: String,
        serverOrigin: String,
        entityType: String,
    ): List<PendingUploadEntity> {
        promoteLegacyPendingOfType(ownerId, serverOrigin, entityType)
        return getPendingByType(ownerId, serverOrigin, entityType)
    }

    /** Counts the owner's queue after promoting legacy rows for each of [entityTypes], in one transaction. */
    @Transaction
    suspend fun getPendingCountPromotingLegacy(
        ownerId: String,
        serverOrigin: String,
        entityTypes: List<String>,
    ): Int {
        entityTypes.forEach { promoteLegacyPendingOfType(ownerId, serverOrigin, it) }
        return getPendingCount(ownerId, serverOrigin)
    }

    /** Settles an entity for its owner and retires its legacy owner-less row with it. */
    @Transaction
    suspend fun deletePendingWithLegacy(
        ownerId: String,
        serverOrigin: String,
        entityType: String,
        entityId: String,
    ) {
        deletePending(ownerId, serverOrigin, entityType, entityId)
        deletePending("", serverOrigin, entityType, entityId)
    }

    @Transaction
    suspend fun promoteLegacyPendingOfType(
        ownerId: String,
        serverOrigin: String,
        entityType: String,
    ) {
        if (getPendingByType(ownerId, serverOrigin, entityType).isNotEmpty()) return
        getLegacyPendingByType(serverOrigin, entityType).forEach { promotePendingIfAbsent(it.copy(ownerId = ownerId)) }
    }

    /** Joins transcript persistence without taking a service mutex while Room owns the writer. */
    @Transaction
    suspend fun enqueueTranscriptMutation(
        ownerId: String,
        serverOrigin: String,
        noteId: String,
        createdAt: Long,
    ): Boolean {
        val scoped = getPending(ownerId, serverOrigin, "NOTE", noteId)
        val legacy = getPending("", serverOrigin, "NOTE", noteId)
        if (scoped?.operation == "DELETE" || legacy?.operation == "DELETE") return false
        val existing = scoped ?: legacy
        insertPending(
            PendingUploadEntity(
                ownerId = ownerId,
                serverOrigin = serverOrigin,
                entityType = "NOTE",
                entityId = noteId,
                operation = if (existing?.operation == "CREATE") "CREATE" else "UPDATE",
                createdAt = existing?.createdAt ?: createdAt,
                expectedServerVersion = existing?.expectedServerVersion,
            ),
        )
        return true
    }

    @Transaction
    suspend fun enqueueRestoreMutation(
        ownerId: String,
        serverOrigin: String,
        entityId: String,
        entityType: String,
        operation: String,
        createdAt: Long,
    ): Boolean {
        val scoped = getPending(ownerId, serverOrigin, entityType, entityId)
        val legacy = getPending("", serverOrigin, entityType, entityId)
        if (scoped?.operation == "DELETE" || legacy?.operation == "DELETE") return false
        val existing = scoped ?: legacy
        insertPending(
            PendingUploadEntity(
                ownerId = ownerId,
                serverOrigin = serverOrigin,
                entityType = entityType,
                entityId = entityId,
                operation = if (existing?.operation == "CREATE") "CREATE" else operation,
                createdAt = existing?.createdAt ?: createdAt,
                expectedServerVersion = existing?.expectedServerVersion,
            ),
        )
        return true
    }

    @Transaction
    suspend fun advancePendingVersionAfterUpload(
        ownerId: String,
        serverOrigin: String,
        entityType: String,
        entityId: String,
        uploadedBaseVersion: Long,
        serverVersion: Long,
    ): Boolean {
        val pending = getPending(ownerId, serverOrigin, entityType, entityId) ?: return false
        if (pending.operation != "UPDATE" ||
            pending.expectedServerVersion != uploadedBaseVersion ||
            serverVersion <= uploadedBaseVersion
        ) {
            return false
        }
        insertPending(pending.copy(expectedServerVersion = serverVersion))
        return true
    }

    @Query(
        """INSERT OR IGNORE INTO pending_uploads
        (ownerId, serverOrigin, entityType, entityId, operation, createdAt, retryCount, expectedServerVersion, operationId)
        SELECT :ownerId, :serverOrigin, :entityType, :entityId, :operation, :createdAt, 0, :expectedServerVersion, :operationId
        WHERE NOT EXISTS (
            SELECT 1 FROM pending_uploads WHERE (ownerId = :ownerId OR ownerId = '')
            AND serverOrigin = :serverOrigin AND entityType = :entityType AND entityId = :entityId
        )""",
    )
    suspend fun insertRepairIfAbsent(
        ownerId: String,
        serverOrigin: String,
        entityType: String,
        entityId: String,
        createdAt: Long,
        expectedServerVersion: Long? = null,
        operationId: String = Uuid.random().toString(),
        operation: String = "UPDATE",
    )

    @Transaction
    suspend fun bindCreateToServerVersion(
        ownerId: String,
        serverOrigin: String,
        entityType: String,
        entityId: String,
        operationId: String,
        serverVersion: Long,
    ): Boolean {
        val pending = getPending(ownerId, serverOrigin, entityType, entityId) ?: return false
        if (pending.operation != "CREATE" || pending.operationId != operationId) return false
        insertPending(
            pending.copy(
                operation = "UPDATE",
                expectedServerVersion = serverVersion,
                operationId = Uuid.random().toString(),
                retryCount = 0,
            ),
        )
        return true
    }

    @Query(
        "DELETE FROM pending_uploads WHERE ownerId = :ownerId AND serverOrigin = :serverOrigin AND entityType = :entityType AND entityId = :entityId",
    )
    suspend fun deletePending(
        ownerId: String,
        serverOrigin: String,
        entityType: String,
        entityId: String,
    )

    @Transaction
    suspend fun incrementRetryIfCurrent(
        ownerId: String,
        serverOrigin: String,
        entityType: String,
        entityId: String,
        operationId: String,
    ): Boolean {
        if (getPending(ownerId, serverOrigin, entityType, entityId)?.operationId != operationId) return false
        incrementRetryCount(ownerId, serverOrigin, entityType, entityId)
        return true
    }

    /** The read and deletion share one Room transaction, including legacy retirement. */
    @Transaction
    suspend fun deletePendingIfCurrent(
        ownerId: String,
        serverOrigin: String,
        entityType: String,
        entityId: String,
        operationId: String,
    ): Boolean {
        val pending = getPending(ownerId, serverOrigin, entityType, entityId) ?: return false
        if (pending.operationId != operationId) return false
        deletePending(ownerId, serverOrigin, entityType, entityId)
        // Owner-less rows are immutable upgrade shadows; new mutations always carry an owner.
        deletePending("", serverOrigin, entityType, entityId)
        return true
    }

    @Query("DELETE FROM pending_uploads WHERE ownerId = :ownerId AND serverOrigin = :serverOrigin")
    suspend fun deletePendingForOrigin(
        ownerId: String,
        serverOrigin: String,
    )

    @Query("SELECT COUNT(*) FROM pending_uploads WHERE ownerId = :ownerId AND serverOrigin = :serverOrigin")
    suspend fun getPendingCount(
        ownerId: String,
        serverOrigin: String,
    ): Int

    @Query("SELECT * FROM pending_uploads WHERE ownerId = :ownerId AND serverOrigin = :serverOrigin ORDER BY createdAt ASC")
    fun observePending(
        ownerId: String,
        serverOrigin: String,
    ): Flow<List<PendingUploadEntity>>

    @Query("SELECT COUNT(*) FROM pending_uploads WHERE ownerId = :ownerId AND serverOrigin = :serverOrigin")
    fun observePendingCount(
        ownerId: String,
        serverOrigin: String,
    ): Flow<Int>

    @Query(
        "UPDATE pending_uploads SET retryCount = retryCount + 1 WHERE ownerId = :ownerId AND serverOrigin = :serverOrigin AND entityType = :entityType AND entityId = :entityId",
    )
    suspend fun incrementRetryCount(
        ownerId: String,
        serverOrigin: String,
        entityType: String,
        entityId: String,
    )

    @Query("DELETE FROM pending_uploads WHERE ownerId = '' AND serverOrigin = :serverOrigin AND entityType = :entityType")
    suspend fun deleteLegacyPendingByType(
        serverOrigin: String,
        entityType: String,
    )

    // --- Moving to a new owner ---

    @Query("SELECT * FROM pending_uploads WHERE ownerId = :ownerId AND serverOrigin = :serverOrigin ORDER BY createdAt ASC")
    suspend fun getPendingForOrigin(
        ownerId: String,
        serverOrigin: String,
    ): List<PendingUploadEntity>

    @Query("SELECT * FROM sync_cursors WHERE ownerId = :ownerId AND serverOrigin = :serverOrigin")
    suspend fun getCursorsForOrigin(
        ownerId: String,
        serverOrigin: String,
    ): List<SyncCursorEntity>

    /**
     * Moves every queued upload of one owner and server to another, keeping each operation's id and
     * retry count. A row whose entity the new scope already queues stays where it is, never merged or
     * dropped, and is counted in the result so the caller can decide about it.
     */
    @Transaction
    suspend fun reassignPending(
        oldOwner: String,
        oldOrigin: String,
        newOwner: String,
        newOrigin: String,
    ): Int {
        if (oldOwner == newOwner && oldOrigin == newOrigin) return 0
        var leftBehind = 0
        for (row in getPendingForOrigin(oldOwner, oldOrigin)) {
            if (getPending(newOwner, newOrigin, row.entityType, row.entityId) != null) {
                leftBehind++
                continue
            }
            insertPending(row.copy(ownerId = newOwner, serverOrigin = newOrigin))
            deletePending(oldOwner, oldOrigin, row.entityType, row.entityId)
        }
        return leftBehind
    }

    /**
     * Moves download cursors from one owner and server to another. Where the new scope already has a
     * cursor for the same entity type, the earlier of the two wins, so nothing is skipped.
     */
    @Transaction
    suspend fun reassignCursors(
        oldOwner: String,
        oldOrigin: String,
        newOwner: String,
        newOrigin: String,
    ) {
        if (oldOwner == newOwner && oldOrigin == newOrigin) return
        for (cursor in getCursorsForOrigin(oldOwner, oldOrigin)) {
            val existing = getCursor(newOwner, newOrigin, cursor.entityType)
            val timestamp = existing?.let { minOf(it.lastSyncTimestamp, cursor.lastSyncTimestamp) } ?: cursor.lastSyncTimestamp
            upsertCursor(SyncCursorEntity(newOwner, newOrigin, cursor.entityType, timestamp))
        }
        deleteCursorsForOrigin(oldOwner, oldOrigin)
    }
}
