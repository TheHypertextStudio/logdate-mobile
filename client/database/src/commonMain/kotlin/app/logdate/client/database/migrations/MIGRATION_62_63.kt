@file:Suppress("ktlint:standard:filename")

package app.logdate.client.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Records where each location sample came from and the order it was saved in, gives saved places
 * an optional category, and caches place lookups so reopening a day does not resolve the same
 * spot again. Each column is added only when missing so a database rolled back past this version
 * can migrate forward again.
 */
val MIGRATION_62_63 =
    object : Migration(62, 63) {
        override fun migrate(connection: SQLiteConnection) {
            addColumnIfMissing(connection, "location_logs", "provider", "TEXT")
            addColumnIfMissing(connection, "location_logs", "speed_accuracy_mps", "REAL")
            addColumnIfMissing(connection, "location_logs", "vertical_accuracy_meters", "REAL")
            addColumnIfMissing(connection, "location_logs", "elapsed_realtime_nanos", "INTEGER")
            addColumnIfMissing(connection, "location_logs", "ingestion_seq", "INTEGER NOT NULL DEFAULT 0")
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS index_location_logs_user_id_device_id_ingestion_seq_sample_id " +
                    "ON location_logs(user_id, device_id, ingestion_seq, sample_id)",
            )
            addColumnIfMissing(connection, "user_places", "category", "TEXT")
            connection.execSQL(
                """
                CREATE TABLE IF NOT EXISTS place_resolution_cache (
                    cell_x INTEGER NOT NULL,
                    cell_y INTEGER NOT NULL,
                    result_kind TEXT NOT NULL,
                    payload_json TEXT NOT NULL,
                    resolved_at_ms INTEGER NOT NULL,
                    expires_at_ms INTEGER NOT NULL,
                    PRIMARY KEY(cell_x, cell_y)
                )
                """.trimIndent(),
            )
            connection.execSQL(
                "CREATE INDEX IF NOT EXISTS index_place_resolution_cache_expires_at_ms " +
                    "ON place_resolution_cache(expires_at_ms)",
            )
        }
    }

private fun addColumnIfMissing(
    connection: SQLiteConnection,
    table: String,
    column: String,
    definition: String,
) {
    val exists =
        connection.prepare("PRAGMA table_info($table)").use { stmt ->
            var found = false
            while (stmt.step()) {
                if (stmt.getText(1) == column) {
                    found = true
                    break
                }
            }
            found
        }
    if (!exists) {
        connection.execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
    }
}
