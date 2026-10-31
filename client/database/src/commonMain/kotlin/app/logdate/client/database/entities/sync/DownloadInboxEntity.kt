package app.logdate.client.database.entities.sync

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * Original wire payloads belong to recovery state, never diagnostic reports. Settled rows retain
 * version watermarks; [appliedAt] records when a row settled, so old receipts can be pruned.
 */
@Entity(
    tableName = "sync_download_inbox",
    primaryKeys = ["ownerId", "serverOrigin", "entityType", "entityId"],
    indices = [Index(value = ["ownerId", "serverOrigin", "state", "nextAttemptAt"])],
)
data class DownloadInboxEntity(
    val ownerId: String,
    val serverOrigin: String,
    val entityType: String,
    val entityId: String,
    val version: Long,
    val deleted: Boolean,
    val payload: String,
    val operationId: String,
    val state: String,
    val attempts: Int,
    val nextAttemptAt: Long,
    val reason: String,
    /** When the row was applied, in epoch milliseconds; 0 for rows that are not applied. */
    @ColumnInfo(defaultValue = "0")
    val appliedAt: Long = 0,
)

@Entity(tableName = "sync_download_checkpoints", primaryKeys = ["ownerId", "serverOrigin", "entityType"])
data class DownloadCheckpointEntity(
    val ownerId: String,
    val serverOrigin: String,
    val entityType: String,
    val cursor: Long,
    val fetchFailure: String = "NONE",
)
