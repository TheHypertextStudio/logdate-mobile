package app.logdate.client.database.entities

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "history_records",
    primaryKeys = ["ownerId", "origin", "id"],
    indices = [
        Index(value = ["ownerId", "origin", "observedAt"]),
        Index(value = ["ownerId", "origin", "recordType", "deviceId", "observedAt", "id"]),
        Index(value = ["ownerId", "origin", "dirty", "id"]),
    ],
)
data class HistoryRecordEntity(
    val ownerId: String,
    val origin: String,
    val id: String,
    val recordType: String,
    val payload: String?,
    val deviceId: String,
    val deviceVersion: Long,
    val serverVersion: Long,
    val deleted: Boolean,
    val dirty: Boolean,
    val observedAt: Long? = null,
)

@Entity(tableName = "history_cursors", primaryKeys = ["ownerId", "origin"])
data class HistoryCursorEntity(
    val ownerId: String,
    val origin: String,
    val cursor: Long,
)
