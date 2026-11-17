package app.logdate.client.sync.peer.delivery

import app.logdate.client.database.dao.sync.PeerDeliveryDao
import app.logdate.client.database.entities.sync.PeerDeliveryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** The delivery ledger's table in memory, keyed like the real one. */
class MemoryPeerDeliveryDao : PeerDeliveryDao {
    private val rows = MutableStateFlow<Map<List<String>, PeerDeliveryEntity>>(emptyMap())

    override suspend fun upsert(entry: PeerDeliveryEntity) {
        rows.value = rows.value + (key(entry.peerScope, entry.direction, entry.entityType, entry.entityId) to entry)
    }

    override suspend fun get(
        peerScope: String,
        direction: String,
        entityType: String,
        entityId: String,
    ): PeerDeliveryEntity? = rows.value[key(peerScope, direction, entityType, entityId)]

    override suspend fun due(
        peerScope: String,
        direction: String,
        now: Long,
        limit: Int,
    ): List<PeerDeliveryEntity> =
        rows.value.values
            .filter { it.peerScope == peerScope && it.direction == direction && (it.nextAttemptAt ?: Long.MAX_VALUE) <= now }
            .sortedBy { it.nextAttemptAt }
            .take(limit)

    override suspend fun delete(
        peerScope: String,
        direction: String,
        entityType: String,
        entityId: String,
    ) {
        rows.value = rows.value - listOf(key(peerScope, direction, entityType, entityId))
    }

    override suspend fun all(
        peerScope: String,
        direction: String,
    ): List<PeerDeliveryEntity> = rows.value.values.filter { it.peerScope == peerScope && it.direction == direction }

    override fun observeAll(
        peerScope: String,
        direction: String,
    ): Flow<List<PeerDeliveryEntity>> = rows.map { all -> all.values.filter { it.peerScope == peerScope && it.direction == direction } }

    private fun key(
        peerScope: String,
        direction: String,
        entityType: String,
        entityId: String,
    ) = listOf(peerScope, direction, entityType, entityId)
}
