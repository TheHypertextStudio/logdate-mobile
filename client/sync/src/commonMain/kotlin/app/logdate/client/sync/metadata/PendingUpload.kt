package app.logdate.client.sync.metadata

import kotlinx.serialization.Serializable

/** Operational ownership is never copied into diagnostic events. */
@Serializable
data class UploadScope(
    val ownerId: String,
    val serverOrigin: String,
)

/**
 * Represents a pending sync operation for an entity.
 */
data class PendingUpload(
    val entityId: String,
    val operation: PendingOperation,
    val retryCount: Int = 0,
    val expectedServerVersion: Long? = null,
    val scope: UploadScope? = null,
    val operationId: String? = null,
)

/**
 * One change waiting in the outbox, as the backup status screen lists it.
 *
 * [entityType] is null for a row whose type this build doesn't know, such as one written by a
 * newer version. It is still waiting and still counted, so it is kept rather than dropped.
 */
data class QueuedUpload(
    val entityType: EntityType?,
    val entityId: String,
    val operation: PendingOperation,
    val retryCount: Int,
    val scope: UploadScope? = null,
    val operationId: String? = null,
    val expectedServerVersion: Long? = null,
)

/**
 * Sync operations queued in the outbox.
 */
enum class PendingOperation {
    CREATE,
    UPDATE,
    DELETE,
    ;

    companion object {
        fun fromStorage(value: String): PendingOperation = values().firstOrNull { it.name == value } ?: UPDATE

        /**
         * Collapse the queued op for an entity given an [existing] outbox state and an [incoming]
         * write. A deletion remains queued because a create may already be in flight.
         */
        fun coalesce(
            existing: PendingOperation?,
            incoming: PendingOperation,
        ): PendingOperation? =
            fromStorage(
                app.logdate.client.database.entities.sync.PendingOperation
                    .coalesce(existing?.name, incoming.name),
            )
    }
}

/** Scope and operation identity stay captured across suspending work. */
internal fun PendingUpload.isSameOperation(other: PendingUpload): Boolean =
    entityId == other.entityId &&
        scope == other.scope &&
        operationId == other.operationId &&
        operation == other.operation &&
        expectedServerVersion == other.expectedServerVersion

internal fun PendingUpload.retryKey(): String =
    if (operationId == null) {
        entityId
    } else {
        val owner = scope?.ownerId.orEmpty()
        val origin = scope?.serverOrigin.orEmpty()
        "operation:${owner.length}:$owner:${origin.length}:$origin:$operationId"
    }
