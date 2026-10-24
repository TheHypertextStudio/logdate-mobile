package app.logdate.server.logdate

/** What a create-only write did. */
sealed interface ConditionalCreate<out T> {
    /** Nothing held the ID, so [record] was written. */
    data class Created<T>(
        val record: T,
    ) : ConditionalCreate<T>

    /** A live record holds the ID and was left alone. [serverVersion] is null only if the record vanished before it was read. */
    data class Exists(
        val serverVersion: Long?,
    ) : ConditionalCreate<Nothing>

    /** The ID was deleted, and the caller asked not to bring it back. */
    data class Deleted(
        val deletedAt: Long,
        val serverVersion: Long,
    ) : ConditionalCreate<Nothing>
}

/** Whether a record ID is live, deleted, or was never written (or its tombstone was purged). */
sealed interface LogDateRecordState {
    data class Live(
        val serverVersion: Long,
    ) : LogDateRecordState

    data class Deleted(
        val deletedAt: Long,
        val serverVersion: Long,
    ) : LogDateRecordState

    data object Absent : LogDateRecordState
}

/** A create-only association batch, split by what happened to each link. */
data class AssociationCreateResults(
    val created: List<LogDateAssociation>,
    val existing: List<LogDateAssociationRef>,
    val deleted: List<LogDateAssociationDeletion>,
)

internal inline fun <T> ConditionalCreate<Long>.mapCreated(record: (Long) -> T): ConditionalCreate<T> =
    when (this) {
        is ConditionalCreate.Created -> ConditionalCreate.Created(record(this.record))
        is ConditionalCreate.Exists -> this
        is ConditionalCreate.Deleted -> this
    }

/**
 * Runs [create] only when this state leaves the ID free: a live record always blocks it, and a deleted
 * one blocks it when [respectTombstones] is set.
 */
internal inline fun <T> LogDateRecordState.createUnlessTaken(
    respectTombstones: Boolean,
    create: () -> T,
): ConditionalCreate<T> =
    when (this) {
        is LogDateRecordState.Live -> {
            ConditionalCreate.Exists(serverVersion)
        }

        is LogDateRecordState.Deleted -> {
            if (respectTombstones) ConditionalCreate.Deleted(deletedAt, serverVersion) else ConditionalCreate.Created(create())
        }

        LogDateRecordState.Absent -> {
            ConditionalCreate.Created(create())
        }
    }
