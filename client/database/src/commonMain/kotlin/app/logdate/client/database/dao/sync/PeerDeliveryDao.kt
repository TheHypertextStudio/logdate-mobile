package app.logdate.client.database.dao.sync

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.logdate.client.database.entities.sync.PeerDeliveryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PeerDeliveryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: PeerDeliveryEntity)

    /** Joins a caller's capture transaction without acquiring a service-level mutex. */
    @Transaction
    suspend fun retainNewest(entry: PeerDeliveryEntity) {
        val prior = get(entry.peerScope, entry.direction, entry.entityType, entry.entityId)
        if (prior == null || prior.contentVersion < entry.contentVersion) upsert(entry)
    }

    @Query(
        "SELECT * FROM peer_delivery WHERE peerScope = :peerScope AND direction = :direction " +
            "AND entityType = :entityType AND entityId = :entityId",
    )
    suspend fun get(
        peerScope: String,
        direction: String,
        entityType: String,
        entityId: String,
    ): PeerDeliveryEntity?

    @Query(
        "SELECT * FROM peer_delivery WHERE peerScope = :peerScope AND direction = :direction " +
            "AND nextAttemptAt IS NOT NULL AND nextAttemptAt <= :now ORDER BY nextAttemptAt ASC LIMIT :limit",
    )
    suspend fun due(
        peerScope: String,
        direction: String,
        now: Long,
        limit: Int,
    ): List<PeerDeliveryEntity>

    @Query(
        "DELETE FROM peer_delivery WHERE peerScope = :peerScope AND direction = :direction " +
            "AND entityType = :entityType AND entityId = :entityId",
    )
    suspend fun delete(
        peerScope: String,
        direction: String,
        entityType: String,
        entityId: String,
    )

    @Query("SELECT * FROM peer_delivery WHERE peerScope = :peerScope AND direction = :direction")
    suspend fun all(
        peerScope: String,
        direction: String,
    ): List<PeerDeliveryEntity>

    @Query("SELECT * FROM peer_delivery WHERE peerScope = :peerScope AND direction = :direction")
    fun observeAll(
        peerScope: String,
        direction: String,
    ): Flow<List<PeerDeliveryEntity>>
}
