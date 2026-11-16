package app.logdate.client.data.restore

import androidx.datastore.preferences.core.Preferences
import androidx.sqlite.db.SupportSQLiteDatabase
import app.logdate.client.data.journals.ArchiveDraftStorageState
import app.logdate.client.repository.journals.CaptureActivity
import app.logdate.client.repository.journals.CaptureDevice
import app.logdate.client.repository.location.LocationCapturePipeline
import app.logdate.client.repository.location.LocationCaptureSource
import app.logdate.shared.model.PhotoPresentation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.uuid.Uuid

/** A conservative inventory of user rows before claiming a complete Android-to-Apple export. */
internal object NativeArchive3SourceCensus {
    private val profilePreferences =
        setOf(
            "display_name",
            "birthday",
            "bio",
            "original_bio",
            "profile_created_at",
            "profile_last_updated_at",
            "profile_photo_uri",
        )

    fun hasPreDatabaseIosContent(preferences: Preferences): Boolean =
        preferences.asMap().any { (key, value) ->
            key.name in profilePreferences ||
                key.name == "identity.canonical_owner_id" ||
                (key.name == "identity.canonical_owner_bound" && value == "true")
        }

    /** An empty installation cannot discard draft custody or an unrecognized draft payload. */
    fun hasOnlyEmptyDraftState(preferences: Preferences): Boolean =
        preferences.asMap().all { (key, value) ->
            when {
                key.name == "entry_drafts" || isRetainedDraftStorageKey(key.name) -> {
                    val state =
                        (value as? String)
                            ?.let { runCatching { ArchiveDraftStorageState.decode(Json, it) }.getOrNull() }
                            ?: return@all false
                    state.drafts.isEmpty() &&
                        state.appliedRecoveries.isEmpty() &&
                        state.localDeletionIds.isEmpty() &&
                        state.custodyCopies.isEmpty() &&
                        state.pendingMutations.isEmpty()
                }
                key.name == "editor_drafts_legacy_owner" || key.name.endsWith("_legacy_copied") -> true
                key.name.startsWith("editor_drafts") -> false
                else -> true
            }
        }

    // Source columns were reviewed against the exported Room 58 schema before claiming complete scope.
    private const val REVIEWED_ROOM_IDENTITY = "d8b23ded9a1a82c49e39b8df9ef25e35"
    private val represented =
        setOf(
            "journals",
            "text_notes",
            "image_notes",
            "audio_notes",
            "video_notes",
            "journal_content_links",
            "media_captions",
            "location_logs",
            "user_places",
            "places",
        )
    private val regenerable =
        setOf(
            "room_master_table",
            "android_metadata",
            "sqlite_sequence",
            "native_journal_source_scope",
            "search_index_metadata",
            "media_exif_metadata",
            "indexed_media_images",
            "indexed_media_videos",
            "entries_fts",
            "entries_fts_config",
            "entries_fts_data",
            "entries_fts_docsize",
            "entries_fts_idx",
            "entries_fts_vocab",
            "storage_metadata",
            "user_devices",
        )

    fun unsupportedPopulatedTables(rows: Map<String, Long>): Set<String> =
        rows
            .filter { (name, count) -> count > 0 && name !in represented && name !in regenerable }
            .keys

    fun inspect(database: SupportSQLiteDatabase): Set<String> {
        database.query("SELECT identity_hash FROM room_master_table WHERE id = 42").use { identity ->
            check(identity.moveToFirst() && reviewedRoomIdentity(identity.getString(0))) {
                "Native source schema has not been reviewed for complete transfer"
            }
        }
        val rows = mutableMapOf<String, Long>()
        database.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { tables ->
            while (tables.moveToNext()) {
                val name = tables.getString(0)
                check(name.matches(Regex("[A-Za-z_][A-Za-z_0-9]*"))) { "Unrecognized native source table" }
                database.query("SELECT COUNT(*) FROM \"$name\"").use { count ->
                    check(count.moveToFirst())
                    rows[name] = count.getLong(0)
                }
            }
        }
        val unsupported = unsupportedPopulatedTables(rows).toMutableSet()
        unsupported += deletedSourceRows(database)
        unsupported += unrepresentedNoteFields(database)
        unsupported += unrepresentedLocationFields(database)
        if (hasUnresolvedNotePlace(database)) unsupported += "unresolvedNotePlace"
        if (hasUnrepresentedJournalLinks(database)) unsupported += "journalContentWithoutNote"
        if (hasUnrepresentedMediaCaptions(database)) unsupported += "mediaCaptionWithoutMediaNote"
        if ("journal_notes" in unsupported && legacyJournalLinksCovered(database)) unsupported.remove("journal_notes")
        return unsupported
    }

    fun deletedSourceRows(database: SupportSQLiteDatabase): Set<String> =
        setOf("text_notes", "image_notes", "audio_notes", "video_notes", "journal_content_links").filterTo(mutableSetOf()) { table ->
            database.query("SELECT 1 FROM $table WHERE deletedAt IS NOT NULL LIMIT 1").use { it.moveToFirst() }
        }

    fun unrepresentedNoteFields(database: SupportSQLiteDatabase): Set<String> =
        buildSet {
            for (table in listOf("text_notes", "image_notes", "audio_notes", "video_notes")) {
                if (hasUnexpectedValue(database, table, "captured_on", CaptureDevice.entries.map(CaptureDevice::wireName))) {
                    add("noteCaptureDevice")
                }
                if (hasUnexpectedValue(database, table, "captured_during", CaptureActivity.entries.map(CaptureActivity::wireName))) {
                    add("noteCaptureActivity")
                }
            }
            if (
                hasUnexpectedValue(
                    database,
                    "image_notes",
                    "presentation",
                    PhotoPresentation.entries.map { it.name },
                    false,
                )
            ) {
                add("imagePresentation")
            }
        }

    fun noteLocationMetadata(database: SupportSQLiteDatabase): Map<Uuid, String> =
        buildMap {
            val seenIds = mutableSetOf<Uuid>()
            for (table in listOf("text_notes", "image_notes", "audio_notes", "video_notes")) {
                database.query("SELECT uid, location_metadata FROM $table").use { rows ->
                    while (rows.moveToNext()) {
                        val id = Uuid.parse(rows.getString(0))
                        check(seenIds.add(id)) { "Native source note identities conflict" }
                        if (!rows.isNull(1)) put(id, rows.getString(1))
                    }
                }
            }
        }

    /** Every persisted semantic place field is copied from the pinned database, including tombstones. */
    fun semanticPlaceRows(database: SupportSQLiteDatabase): JsonArray {
        val rows = mutableListOf<kotlinx.serialization.json.JsonObject>()
        database
            .query(
                "SELECT id, name, latitude, longitude, altitude, accuracy, radius, units, description, ap_uri, created, last_updated, sync_version, last_synced, deleted_at FROM places ORDER BY id",
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    rows +=
                        buildJsonObject {
                            put("id", JsonPrimitive(cursor.getString(0)))
                            put("name", JsonPrimitive(cursor.getString(1)))
                            put("latitude", JsonPrimitive(cursor.getDouble(2)))
                            put("longitude", JsonPrimitive(cursor.getDouble(3)))
                            for (index in listOf(4, 5, 6)) {
                                if (!cursor.isNull(index)) {
                                    put(listOf("altitude", "accuracy", "radius")[index - 4], JsonPrimitive(cursor.getDouble(index)))
                                }
                            }
                            put("units", JsonPrimitive(cursor.getString(7)))
                            for ((index, name) in listOf(8 to "description", 9 to "ap_uri")) {
                                if (!cursor.isNull(index)) put(name, JsonPrimitive(cursor.getString(index)))
                            }
                            for ((index, name) in listOf(
                                10 to "created",
                                11 to "last_updated",
                                12 to "sync_version",
                                13 to "last_synced",
                                14 to "deleted_at",
                            )) {
                                if (!cursor.isNull(index)) put(name, JsonPrimitive(cursor.getLong(index)))
                            }
                        }
                }
            }
        return JsonArray(rows)
    }

    fun unrepresentedLocationFields(database: SupportSQLiteDatabase): Set<String> =
        buildSet {
            if (hasUnexpectedValue(database, "location_logs", "capture_pipeline", LocationCapturePipeline.entries.map { it.name }, false)) {
                add("locationCapturePipeline")
            }
            if (hasUnexpectedValue(database, "location_logs", "capture_source", LocationCaptureSource.entries.map { it.name }, false)) {
                add("locationCaptureSource")
            }
        }

    fun hasUnresolvedNotePlace(database: SupportSQLiteDatabase): Boolean =
        listOf("text_notes", "image_notes", "audio_notes", "video_notes").any { table ->
            database
                .query(
                    "SELECT 1 FROM $table AS note WHERE note.place_id IS NOT NULL " +
                        "AND NOT EXISTS (SELECT 1 FROM places AS place WHERE place.id = note.place_id AND place.deleted_at IS NULL) LIMIT 1",
                ).use { it.moveToFirst() }
        }

    private fun hasUnexpectedValue(
        database: SupportSQLiteDatabase,
        table: String,
        column: String,
        accepted: List<String>,
        allowNull: Boolean = true,
    ): Boolean {
        val known =
            accepted.joinToString(",") { value ->
                require(value.matches(Regex("[A-Za-z_]+")))
                "'$value'"
            }
        val predicate = if (allowNull) "$column IS NOT NULL AND $column NOT IN ($known)" else "$column IS NULL OR $column NOT IN ($known)"
        return database.query("SELECT 1 FROM $table WHERE $predicate LIMIT 1").use { it.moveToFirst() }
    }

    fun hasUnrepresentedJournalLinks(database: SupportSQLiteDatabase): Boolean =
        database
            .query(
                """
                SELECT 1 FROM journal_content_links AS link
                WHERE NOT EXISTS (SELECT 1 FROM journals AS journal WHERE journal.id = link.journal_id)
                   OR (
                       NOT EXISTS (SELECT 1 FROM text_notes AS note WHERE note.uid = link.content_id)
                       AND NOT EXISTS (SELECT 1 FROM image_notes AS note WHERE note.uid = link.content_id)
                       AND NOT EXISTS (SELECT 1 FROM audio_notes AS note WHERE note.uid = link.content_id)
                       AND NOT EXISTS (SELECT 1 FROM video_notes AS note WHERE note.uid = link.content_id)
                   ) LIMIT 1
                """.trimIndent(),
            ).use { it.moveToFirst() }

    fun hasUnrepresentedMediaCaptions(database: SupportSQLiteDatabase): Boolean =
        database
            .query(
                """
                SELECT 1 FROM media_captions AS caption
                WHERE NOT EXISTS (SELECT 1 FROM image_notes AS note WHERE note.uid = caption.noteId)
                  AND NOT EXISTS (SELECT 1 FROM audio_notes AS note WHERE note.uid = caption.noteId)
                  AND NOT EXISTS (SELECT 1 FROM video_notes AS note WHERE note.uid = caption.noteId)
                LIMIT 1
                """.trimIndent(),
            ).use { it.moveToFirst() }

    fun reviewedRoomIdentity(identity: String?): Boolean = identity == REVIEWED_ROOM_IDENTITY

    fun legacyJournalLinksCovered(database: SupportSQLiteDatabase): Boolean =
        database
            .query(
                """
                SELECT 1 FROM journal_notes AS legacy
                WHERE NOT EXISTS (
                    SELECT 1 FROM journal_content_links AS current
                    WHERE current.journal_id = legacy.id
                      AND current.content_id = legacy.uid
                      AND current.deletedAt IS NULL
                ) LIMIT 1
                """.trimIndent(),
            ).use { !it.moveToFirst() }

    /** Only profile fields are currently represented in the Apple auxiliary payload. */
    fun unsupportedPreferences(preferences: Preferences): Set<String> {
        val operational =
            setOf(
                "backend_url",
                "is_onboarded",
                "onboarded_timestamp",
                "backfilled_account_ids",
                "entry_drafts",
                "identity.canonical_owner_id",
                "identity.canonical_owner_bound",
                "identity.completed_history_owner_adoption.v1",
                "identity.native_restore_commit",
                "identity.pending_history_owner_adoption.v1",
            )
        return preferences.asMap().keys.map(Preferences.Key<*>::name).filterTo(mutableSetOf()) { name ->
            name !in profilePreferences &&
                name !in operational &&
                !name.startsWith("editor_drafts") &&
                !name.matches(
                    Regex(
                        "sync_(first_enqueue_done_|inflight_|last_error_|legacy_audit_v1_|local_backfill_v[12]_|media_ref_|resume_build_v1_|retry_|route_v1_|association_backfill_v1_).*",
                    ),
                )
        }
    }
}
