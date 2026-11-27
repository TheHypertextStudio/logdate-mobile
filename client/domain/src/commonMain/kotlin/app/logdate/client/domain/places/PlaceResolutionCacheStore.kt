package app.logdate.client.domain.places

data class CachedPlaceRow(
    val cellX: Int,
    val cellY: Int,
    val resultKind: String,
    val payloadJson: String,
    val resolvedAtMs: Long,
    val expiresAtMs: Long,
)

interface PlaceResolutionCacheStore {
    suspend fun get(
        cellX: Int,
        cellY: Int,
    ): CachedPlaceRow?

    suspend fun upsert(row: CachedPlaceRow)

    suspend fun deleteNeighborhood(
        minX: Int,
        maxX: Int,
        minY: Int,
        maxY: Int,
    )

    suspend fun deleteExpired(nowMs: Long)
}
