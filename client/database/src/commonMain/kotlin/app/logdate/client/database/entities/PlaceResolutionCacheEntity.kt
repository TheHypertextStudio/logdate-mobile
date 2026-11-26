package app.logdate.client.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "place_resolution_cache",
    primaryKeys = ["cell_x", "cell_y"],
    indices = [Index(value = ["expires_at_ms"])],
)
data class PlaceResolutionCacheEntity(
    @ColumnInfo(name = "cell_x")
    val cellX: Int,
    @ColumnInfo(name = "cell_y")
    val cellY: Int,
    @ColumnInfo(name = "result_kind")
    val resultKind: String,
    @ColumnInfo(name = "payload_json")
    val payloadJson: String,
    @ColumnInfo(name = "resolved_at_ms")
    val resolvedAtMs: Long,
    @ColumnInfo(name = "expires_at_ms")
    val expiresAtMs: Long,
)
