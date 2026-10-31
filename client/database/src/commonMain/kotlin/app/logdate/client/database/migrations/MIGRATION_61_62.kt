@file:Suppress("ktlint:standard:filename")

package app.logdate.client.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Indexes location history by its unsent state, so finding the records still waiting to upload
 * reads only those records, in upload order, instead of scanning every saved sample.
 */
val MIGRATION_61_62 =
    object : Migration(61, 62) {
        override fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_history_records_ownerId_origin_dirty_id` " +
                    "ON `history_records` (`ownerId`, `origin`, `dirty`, `id`)",
            )
        }
    }
