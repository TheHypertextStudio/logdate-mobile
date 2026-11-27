package app.logdate.client.data.places

import app.logdate.client.database.dao.PlaceResolutionCacheDao
import app.logdate.client.database.entities.PlaceResolutionCacheEntity
import app.logdate.client.domain.places.CachedPlaceRow
import app.logdate.client.domain.places.PlaceResolutionCacheStore

class RoomPlaceResolutionCacheStore(
    private val dao: PlaceResolutionCacheDao,
) : PlaceResolutionCacheStore {
    override suspend fun get(
        cellX: Int,
        cellY: Int,
    ): CachedPlaceRow? =
        dao.get(cellX, cellY)?.let { row ->
            CachedPlaceRow(
                cellX = row.cellX,
                cellY = row.cellY,
                resultKind = row.resultKind,
                payloadJson = row.payloadJson,
                resolvedAtMs = row.resolvedAtMs,
                expiresAtMs = row.expiresAtMs,
            )
        }

    override suspend fun upsert(row: CachedPlaceRow) {
        dao.upsert(
            PlaceResolutionCacheEntity(
                cellX = row.cellX,
                cellY = row.cellY,
                resultKind = row.resultKind,
                payloadJson = row.payloadJson,
                resolvedAtMs = row.resolvedAtMs,
                expiresAtMs = row.expiresAtMs,
            ),
        )
    }

    override suspend fun deleteNeighborhood(
        minX: Int,
        maxX: Int,
        minY: Int,
        maxY: Int,
    ) {
        dao.deleteNeighborhood(minX, maxX, minY, maxY)
    }

    override suspend fun deleteExpired(nowMs: Long) {
        dao.deleteExpired(nowMs)
    }
}
