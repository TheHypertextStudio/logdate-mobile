package app.logdate.client.database.entities.sync

import androidx.room.ColumnInfo
import androidx.room.Entity
import kotlin.uuid.Uuid

/**
 * Tracks entities that have been modified locally and need to be uploaded.
 * Acts as an outbox for the sync system.
 *
 * [nextAttemptAt] and [unfinishedAttempts] belong to the queued operation: replacing the row with
 * a new operation starts its retry schedule over.
 */
@Entity(
    tableName = "pending_uploads",
    primaryKeys = ["ownerId", "serverOrigin", "entityType", "entityId"],
)
data class PendingUploadEntity(
    @ColumnInfo(defaultValue = "''")
    val ownerId: String,
    val serverOrigin: String,
    val entityType: String,
    val entityId: String,
    val operation: String,
    val createdAt: Long,
    val retryCount: Int = 0,
    val expectedServerVersion: Long? = null,
    @ColumnInfo(defaultValue = "''")
    val operationId: String = Uuid.random().toString(),
    /** When the next upload attempt may start, in epoch milliseconds; null when it is not waiting. */
    @ColumnInfo(defaultValue = "NULL")
    val nextAttemptAt: Long? = null,
    /** Upload attempts that started and never recorded an end, including any lost with the process. */
    @ColumnInfo(defaultValue = "0")
    val unfinishedAttempts: Int = 0,
)

/**
 * Operations that can be pending for sync.
 */
object PendingOperation {
    const val CREATE = "CREATE"
    const val UPDATE = "UPDATE"
    const val DELETE = "DELETE"

    fun coalesce(
        existing: String?,
        incoming: String,
    ): String =
        when {
            incoming == DELETE -> DELETE
            incoming == CREATE -> CREATE
            existing == CREATE || existing == DELETE -> CREATE
            else -> UPDATE
        }
}
