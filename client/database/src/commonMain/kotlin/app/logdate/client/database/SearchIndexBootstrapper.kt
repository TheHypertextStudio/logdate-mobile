package app.logdate.client.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import io.github.aakira.napier.Napier

/**
 * Prepares the runtime-managed search index when the database opens: its tables and the triggers
 * that index every later write. Filling the index from existing content can take seconds on a
 * large journal, so it runs later through [app.logdate.client.database.dao.SearchDao.rebuildIfNeeded]
 * instead of holding up the first query.
 */
internal object SearchIndexBootstrapper {
    private const val CURRENT_SCHEMA_VERSION = SEARCH_INDEX_SCHEMA_VERSION
    private const val METADATA_TABLE = "search_index_metadata"

    fun bootstrap(connection: SQLiteConnection) {
        createMetadataTable(connection)

        if (!createFtsTable(connection)) {
            // FTS5 unavailable on this SQLite build (typically the framework SQLite used by
            // the in-memory recovery fallback). Skip the rest of search bootstrap so the app
            // can continue running without crashing — search will be disabled until recovery.
            Napier.w("SearchIndexBootstrapper: FTS5 unavailable; search index disabled")
            return
        }
        createVocabularyTable(connection)

        val schemaVersion = readSchemaVersion(connection)
        if (needsTriggerRefresh(connection, schemaVersion)) {
            recreateTriggers(connection)
        }
    }

    private fun createMetadataTable(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $METADATA_TABLE (
                id INTEGER NOT NULL PRIMARY KEY CHECK (id = 1),
                schemaVersion INTEGER NOT NULL,
                generation INTEGER NOT NULL DEFAULT 0,
                lastRebuiltAt INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        // Older databases were created before the generation column existed. Add it only when
        // missing — checking via PRAGMA avoids the noisy "duplicate column" SQLite log that
        // a blind ALTER + runCatching would otherwise emit.
        if (!hasColumn(connection, METADATA_TABLE, "generation")) {
            connection.execSQL(
                """
                ALTER TABLE $METADATA_TABLE
                ADD COLUMN generation INTEGER NOT NULL DEFAULT 0
                """.trimIndent(),
            )
        }
        connection.execSQL(
            """
            INSERT INTO $METADATA_TABLE(id, schemaVersion, generation, lastRebuiltAt)
            SELECT 1, 0, 0, 0
            WHERE NOT EXISTS(SELECT 1 FROM $METADATA_TABLE WHERE id = 1)
            """.trimIndent(),
        )
    }

    /**
     * Creates the FTS5 virtual table. Returns true on success, false if FTS5 is unavailable
     * on this SQLite build (e.g. the in-memory recovery fallback running on framework SQLite).
     */
    private fun createFtsTable(connection: SQLiteConnection): Boolean =
        runCatching {
            connection.execSQL(
                """
                CREATE VIRTUAL TABLE IF NOT EXISTS entries_fts USING fts5(
                    uid UNINDEXED,
                    content,
                    created UNINDEXED,
                    contentType UNINDEXED,
                    tokenize = 'porter unicode61'
                )
                """.trimIndent(),
            )
        }.onFailure { error ->
            Napier.w("SearchIndexBootstrapper: failed to create FTS5 table", error)
        }.isSuccess

    private fun hasColumn(
        connection: SQLiteConnection,
        tableName: String,
        columnName: String,
    ): Boolean =
        connection.prepare("PRAGMA table_info($tableName)").use { stmt ->
            while (stmt.step()) {
                if (stmt.getText(1) == columnName) return true
            }
            false
        }

    private fun createVocabularyTable(connection: SQLiteConnection) {
        runCatching {
            connection.execSQL(
                """
                CREATE VIRTUAL TABLE IF NOT EXISTS entries_fts_vocab
                USING fts5vocab(entries_fts, 'row')
                """.trimIndent(),
            )
        }
    }

    private fun recreateTriggers(connection: SQLiteConnection) {
        triggerNames.forEach { triggerName ->
            connection.execSQL("DROP TRIGGER IF EXISTS $triggerName")
        }
        triggerDefinitions.forEach(connection::execSQL)
    }

    private fun readSchemaVersion(connection: SQLiteConnection): Long? =
        connection.prepare("SELECT schemaVersion FROM $METADATA_TABLE WHERE id = 1").use { stmt ->
            if (!stmt.step()) {
                null
            } else {
                stmt.getLong(0)
            }
        }

    private fun needsTriggerRefresh(
        connection: SQLiteConnection,
        schemaVersion: Long?,
    ): Boolean {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            return true
        }

        return connection.prepare(sentinelTriggerExistsSql).use { stmt ->
            stmt.step()
            stmt.getLong(0) != 1L
        }
    }

    private val sentinelTriggerExistsSql =
        """
        SELECT EXISTS(
            SELECT 1
            FROM sqlite_master
            WHERE type = 'trigger'
              AND name = 'text_notes_fts_insert'
        )
        """.trimIndent()

    private val triggerNames =
        listOf(
            "text_notes_fts_insert",
            "text_notes_fts_content_update",
            "text_notes_fts_soft_delete",
            "text_notes_fts_delete",
            "transcriptions_fts_insert",
            "transcriptions_fts_update",
            "transcriptions_fts_delete",
            "audio_notes_transcriptions_fts_soft_delete",
            "audio_notes_transcriptions_fts_restore",
            "audio_notes_transcriptions_fts_delete",
            "journals_fts_insert",
            "journals_fts_content_update",
            "journals_fts_soft_delete",
            "journals_fts_delete",
            "media_captions_fts_insert",
            "media_captions_fts_update",
            "media_captions_fts_delete",
            "image_notes_media_captions_fts_soft_delete",
            "image_notes_media_captions_fts_restore",
            "image_notes_media_captions_fts_delete",
            "video_notes_media_captions_fts_soft_delete",
            "video_notes_media_captions_fts_restore",
            "video_notes_media_captions_fts_delete",
            "places_fts_insert",
            "places_fts_content_update",
            "places_fts_soft_delete",
            "places_fts_delete",
            "rewinds_fts_insert",
            "rewinds_fts_update",
            "rewinds_fts_delete",
            "stickers_fts_insert",
            "stickers_fts_update",
            "stickers_fts_delete",
            "postcards_fts_insert",
            "postcards_fts_update",
            "postcards_fts_delete",
            "audio_tags_fts_insert",
            "audio_tags_fts_update",
            "audio_tags_fts_delete",
            "audio_notes_audio_tags_fts_soft_delete",
            "audio_notes_audio_tags_fts_restore",
            "audio_notes_audio_tags_fts_delete",
        )

    private val triggerDefinitions = searchIndexContentTriggers + searchIndexContextTriggers
}
