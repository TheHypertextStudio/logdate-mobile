package app.logdate.client.sync.migration

import kotlin.uuid.Uuid

/**
 * Represents the progress of a data migration operation.
 */
data class MigrationProgress(
    /**
     * Whether migration is currently in progress.
     */
    val inProgress: Boolean,
    /**
     * The old user ID being migrated from.
     */
    val oldId: Uuid,
    /**
     * The new user ID being migrated to.
     */
    val newId: Uuid,
    /**
     * Number of items processed so far.
     */
    val itemsProcessed: Int,
    /**
     * Total number of items to process.
     */
    val totalItems: Int,
    /**
     * ID of the last processed item, used for resuming migration.
     */
    val lastProcessedId: String? = null,
) {
    /**
     * The percentage of migration completion, from 0 to 100.
     */
    val percentComplete: Int
        get() = if (totalItems > 0) (itemsProcessed * 100) / totalItems else 0

    /**
     * Whether the migration is complete.
     */
    val isComplete: Boolean
        get() = !inProgress && itemsProcessed >= totalItems
}
