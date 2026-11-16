@file:Suppress("ktlint:standard:filename")

package app.logdate.client.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Stores each queued upload's retry schedule and unfinished attempts with the queued operation,
 * and indexes the download inbox by state and due time. Rows applied before this version are
 * stamped with the migration time, so a retention window counts from the upgrade rather than
 * pruning every existing receipt at once.
 */
val MIGRATION_60_61 =
    object : Migration(60, 61) {
        override fun migrate(connection: SQLiteConnection) {
            if (!connection.hasColumn("pending_uploads", "nextAttemptAt")) {
                connection.execSQL("ALTER TABLE `pending_uploads` ADD COLUMN `nextAttemptAt` INTEGER DEFAULT NULL")
            }
            if (!connection.hasColumn("pending_uploads", "unfinishedAttempts")) {
                connection.execSQL("ALTER TABLE `pending_uploads` ADD COLUMN `unfinishedAttempts` INTEGER NOT NULL DEFAULT 0")
            }
            if (!connection.hasColumn("sync_download_inbox", "appliedAt")) {
                connection.execSQL("ALTER TABLE `sync_download_inbox` ADD COLUMN `appliedAt` INTEGER NOT NULL DEFAULT 0")
                connection.execSQL(
                    "UPDATE `sync_download_inbox` SET `appliedAt` = CAST(strftime('%s', 'now') AS INTEGER) * 1000 " +
                        "WHERE `state` = 'APPLIED'",
                )
            }
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_sync_download_inbox_ownerId_serverOrigin_state_nextAttemptAt` " +
                    "ON `sync_download_inbox` (`ownerId`, `serverOrigin`, `state`, `nextAttemptAt`)",
            )
        }
    }
