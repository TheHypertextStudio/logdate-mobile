package app.logdate.client.data.restore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
class NativeArchive3SourceCensusTest {
    @Test
    fun `only recognized empty draft state permits an empty source`() {
        val key = stringPreferencesKey("editor_drafts_anonymous")
        assertTrue(NativeArchive3SourceCensus.hasOnlyEmptyDraftState(preferencesOf(key to "[]")))
        assertFalse(
            NativeArchive3SourceCensus.hasOnlyEmptyDraftState(
                preferencesOf(key to """{"localDeletionIds":["22222222-2222-2222-2222-222222222222"]}"""),
            ),
        )
        assertFalse(NativeArchive3SourceCensus.hasOnlyEmptyDraftState(preferencesOf(key to """{"futureReceipt":true}""")))
        assertFalse(
            NativeArchive3SourceCensus.hasOnlyEmptyDraftState(
                preferencesOf(stringPreferencesKey("editor_drafts_native_unparsed_1") to "unreadable"),
            ),
        )
    }

    @Test
    fun `a note linked to a deleted semantic place remains fail closed`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper =
            FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration
                    .builder(context)
                    .name("native-note-place-${System.nanoTime()}.db")
                    .callback(
                        object : SupportSQLiteOpenHelper.Callback(1) {
                            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                                db.execSQL("CREATE TABLE places (id TEXT, deleted_at INTEGER)")
                                for (table in listOf("text_notes", "image_notes", "audio_notes", "video_notes")) {
                                    db.execSQL("CREATE TABLE $table (place_id TEXT)")
                                }
                            }

                            override fun onUpgrade(
                                db: androidx.sqlite.db.SupportSQLiteDatabase,
                                oldVersion: Int,
                                newVersion: Int,
                            ) = Unit
                        },
                    ).build(),
            )
        try {
            val database = helper.writableDatabase
            database.execSQL("INSERT INTO places VALUES ('place-1', NULL)")
            database.execSQL("INSERT INTO text_notes VALUES ('place-1')")
            assertFalse(NativeArchive3SourceCensus.hasUnresolvedNotePlace(database))
            database.execSQL("UPDATE places SET deleted_at = 123")
            assertTrue(NativeArchive3SourceCensus.hasUnresolvedNotePlace(database))
        } finally {
            helper.close()
        }
    }

    @Test
    fun `semantic places retain every persisted field including deletion`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper =
            FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration
                    .builder(context)
                    .name("native-semantic-places-${System.nanoTime()}.db")
                    .callback(
                        object : SupportSQLiteOpenHelper.Callback(1) {
                            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                                db.execSQL(
                                    "CREATE TABLE places (id TEXT, name TEXT, latitude REAL, longitude REAL, " +
                                        "altitude REAL, accuracy REAL, " +
                                        "radius REAL, units TEXT, description TEXT, ap_uri TEXT, created INTEGER, last_updated INTEGER, " +
                                        "sync_version INTEGER, last_synced INTEGER, deleted_at INTEGER)",
                                )
                            }

                            override fun onUpgrade(
                                db: androidx.sqlite.db.SupportSQLiteDatabase,
                                oldVersion: Int,
                                newVersion: Int,
                            ) = Unit
                        },
                    ).build(),
            )
        try {
            val database = helper.writableDatabase
            database.execSQL(
                "INSERT INTO places VALUES ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'Trailhead', 36.1, -115.1, 620.5, 0.75, " +
                    "30.0, 'm', 'The path', 'https://example.test/place', 1700000000123, 1700000500456, 7, 1700000550555, 1700000600789)",
            )
            val row = NativeArchive3SourceCensus.semanticPlaceRows(database).single().jsonObject
            assertEquals(15, row.size)
            assertEquals("1700000600789", row.getValue("deleted_at").jsonPrimitive.content)
            assertEquals("https://example.test/place", row.getValue("ap_uri").jsonPrimitive.content)
            assertEquals("0.75", row.getValue("accuracy").jsonPrimitive.content)
            assertTrue("places" !in NativeArchive3SourceCensus.unsupportedPopulatedTables(mapOf("places" to 1L)))
        } finally {
            helper.close()
        }
    }

    @Test
    fun `content not represented by archive three blocks complete claim`() {
        val rows =
            mapOf(
                "journals" to 2L,
                "text_notes" to 1L,
                "user_places" to 1L,
                "location_logs" to 4L,
                "transcriptions" to 1L,
                "audio_tags" to 1L,
                "people" to 1L,
                "unknown_future_table" to 1L,
                "room_master_table" to 1L,
                "native_journal_source_scope" to 1L,
                "entries_fts" to 2L,
                "entries_fts_config" to 1L,
                "entries_fts_data" to 2L,
                "entries_fts_docsize" to 1L,
                "entries_fts_idx" to 1L,
                "entries_fts_vocab" to 1L,
                "entries_fts_future" to 1L,
                "indexed_media" to 3L,
                "storage_metadata" to 1L,
            )
        assertEquals(
            setOf("transcriptions", "audio_tags", "people", "unknown_future_table", "entries_fts_future", "indexed_media"),
            NativeArchive3SourceCensus.unsupportedPopulatedTables(rows),
        )
    }

    @Test
    fun `raw note fields that model mappers discard cannot claim complete transfer`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper =
            FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration
                    .builder(context)
                    .name("native-archive3-note-fields-${System.nanoTime()}.db")
                    .callback(
                        object : SupportSQLiteOpenHelper.Callback(1) {
                            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                                for (table in listOf("text_notes", "image_notes", "audio_notes", "video_notes")) {
                                    val presentationColumn = if (table == "image_notes") ", presentation TEXT" else ""
                                    db.execSQL(
                                        "CREATE TABLE $table " +
                                            "(captured_on TEXT, captured_during TEXT, location_metadata TEXT$presentationColumn)",
                                    )
                                }
                            }

                            override fun onUpgrade(
                                db: androidx.sqlite.db.SupportSQLiteDatabase,
                                oldVersion: Int,
                                newVersion: Int,
                            ) = Unit
                        },
                    ).build(),
            )
        try {
            val database = helper.writableDatabase
            database.execSQL("INSERT INTO text_notes VALUES ('phone', 'moving', NULL)")
            database.execSQL("INSERT INTO image_notes VALUES ('watch', 'in_vehicle', NULL, 'Framed')")
            assertEquals(emptySet(), NativeArchive3SourceCensus.unrepresentedNoteFields(database))
            database.execSQL("INSERT INTO text_notes VALUES ('future-device', NULL, NULL)")
            assertEquals(setOf("noteCaptureDevice"), NativeArchive3SourceCensus.unrepresentedNoteFields(database))
            database.execSQL("INSERT INTO audio_notes VALUES (NULL, 'future-activity', NULL)")
            database.execSQL("INSERT INTO video_notes VALUES (NULL, NULL, '{\"future\":true}')")
            database.execSQL("INSERT INTO image_notes VALUES (NULL, NULL, NULL, 'FuturePresentation')")
            assertEquals(
                setOf("noteCaptureDevice", "noteCaptureActivity", "imagePresentation"),
                NativeArchive3SourceCensus.unrepresentedNoteFields(database),
            )
        } finally {
            helper.close()
        }
    }

    @Test
    fun `raw note location metadata retains exact source bytes and refuses conflicting identities`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper =
            FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration
                    .builder(context)
                    .name("native-archive3-location-custody-${System.nanoTime()}.db")
                    .callback(
                        object : SupportSQLiteOpenHelper.Callback(1) {
                            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                                for (table in listOf("text_notes", "image_notes", "audio_notes", "video_notes")) {
                                    db.execSQL("CREATE TABLE $table (uid TEXT NOT NULL, location_metadata TEXT)")
                                }
                            }

                            override fun onUpgrade(
                                db: androidx.sqlite.db.SupportSQLiteDatabase,
                                oldVersion: Int,
                                newVersion: Int,
                            ) = Unit
                        },
                    ).build(),
            )
        try {
            val database = helper.writableDatabase
            val id = Uuid.parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
            val raw = "{ \"coordinates\": {\"latitude\":36.1}, \"preserveSourcePlace\":true }"
            database.execSQL("INSERT INTO text_notes VALUES (?, ?)", arrayOf(id.toString(), raw))
            assertEquals(mapOf(id to raw), NativeArchive3SourceCensus.noteLocationMetadata(database))
            database.execSQL("INSERT INTO image_notes VALUES (?, ?)", arrayOf(id.toString(), "{}"))
            assertFailsWith<IllegalStateException> { NativeArchive3SourceCensus.noteLocationMetadata(database) }
        } finally {
            helper.close()
        }
    }

    @Test
    fun `unknown location capture values cannot be silently rewritten`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper =
            FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration
                    .builder(context)
                    .name("native-archive3-location-fields-${System.nanoTime()}.db")
                    .callback(
                        object : SupportSQLiteOpenHelper.Callback(1) {
                            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                                db.execSQL("CREATE TABLE location_logs (capture_pipeline TEXT, capture_source TEXT)")
                            }

                            override fun onUpgrade(
                                db: androidx.sqlite.db.SupportSQLiteDatabase,
                                oldVersion: Int,
                                newVersion: Int,
                            ) = Unit
                        },
                    ).build(),
            )
        try {
            val database = helper.writableDatabase
            database.execSQL("INSERT INTO location_logs VALUES ('HIGH_DETAIL', 'PASSIVE_UPDATE')")
            assertEquals(emptySet(), NativeArchive3SourceCensus.unrepresentedLocationFields(database))
            database.execSQL("INSERT INTO location_logs VALUES ('FUTURE_PIPELINE', 'FUTURE_SOURCE')")
            assertEquals(
                setOf("locationCapturePipeline", "locationCaptureSource"),
                NativeArchive3SourceCensus.unrepresentedLocationFields(database),
            )
        } finally {
            helper.close()
        }
    }

    @Test
    fun `device indexes and installation bookkeeping do not block journal transfer`() {
        val rows =
            mapOf(
                "journals" to 2L,
                "indexed_media_images" to 90L,
                "indexed_media_videos" to 12L,
                "storage_metadata" to 3L,
                "user_devices" to 2L,
                "journal_notes" to 1L,
            )
        assertEquals(setOf("journal_notes"), NativeArchive3SourceCensus.unsupportedPopulatedTables(rows))
    }

    @Test
    fun `legacy journal links are safe only when active modern links contain them`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper =
            FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration
                    .builder(context)
                    .name("native-archive3-legacy-links-${System.nanoTime()}.db")
                    .callback(
                        object : SupportSQLiteOpenHelper.Callback(1) {
                            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                                db.execSQL("CREATE TABLE journal_notes (id TEXT NOT NULL, uid TEXT NOT NULL)")
                                db.execSQL(
                                    "CREATE TABLE journal_content_links " +
                                        "(journal_id TEXT NOT NULL, content_id TEXT NOT NULL, deletedAt INTEGER)",
                                )
                            }

                            override fun onUpgrade(
                                db: androidx.sqlite.db.SupportSQLiteDatabase,
                                oldVersion: Int,
                                newVersion: Int,
                            ) = Unit
                        },
                    ).build(),
            )
        try {
            val database = helper.writableDatabase
            database.execSQL("INSERT INTO journal_notes VALUES ('journal', 'note')")
            assertFalse(NativeArchive3SourceCensus.legacyJournalLinksCovered(database))
            database.execSQL("INSERT INTO journal_content_links VALUES ('journal', 'note', 1)")
            assertFalse(NativeArchive3SourceCensus.legacyJournalLinksCovered(database))
            database.execSQL("UPDATE journal_content_links SET deletedAt = NULL")
            assertTrue(NativeArchive3SourceCensus.legacyJournalLinksCovered(database))
        } finally {
            helper.close()
        }
    }

    @Test
    fun `deleted notes and journal links cannot be exported as active records`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper =
            FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration
                    .builder(context)
                    .name("native-archive3-deleted-source-${System.nanoTime()}.db")
                    .callback(
                        object : SupportSQLiteOpenHelper.Callback(1) {
                            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                                for (table in listOf("text_notes", "image_notes", "audio_notes", "video_notes", "journal_content_links")) {
                                    db.execSQL("CREATE TABLE $table (deletedAt INTEGER)")
                                }
                            }

                            override fun onUpgrade(
                                db: androidx.sqlite.db.SupportSQLiteDatabase,
                                oldVersion: Int,
                                newVersion: Int,
                            ) = Unit
                        },
                    ).build(),
            )
        try {
            val database = helper.writableDatabase
            database.execSQL("INSERT INTO text_notes VALUES (NULL)")
            database.execSQL("INSERT INTO image_notes VALUES (123)")
            database.execSQL("INSERT INTO journal_content_links VALUES (456)")
            assertEquals(
                setOf("image_notes", "journal_content_links"),
                NativeArchive3SourceCensus.deletedSourceRows(database),
            )
        } finally {
            helper.close()
        }
    }

    @Test
    fun `journal links must resolve to an exported journal and note`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper =
            FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration
                    .builder(context)
                    .name("native-archive3-link-coverage-${System.nanoTime()}.db")
                    .callback(
                        object : SupportSQLiteOpenHelper.Callback(1) {
                            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                                db.execSQL("CREATE TABLE journals (id TEXT NOT NULL)")
                                for (table in listOf("text_notes", "image_notes", "audio_notes", "video_notes")) {
                                    db.execSQL("CREATE TABLE $table (uid TEXT NOT NULL)")
                                }
                                db.execSQL("CREATE TABLE journal_content_links (journal_id TEXT NOT NULL, content_id TEXT NOT NULL)")
                            }

                            override fun onUpgrade(
                                db: androidx.sqlite.db.SupportSQLiteDatabase,
                                oldVersion: Int,
                                newVersion: Int,
                            ) = Unit
                        },
                    ).build(),
            )
        try {
            val database = helper.writableDatabase
            database.execSQL("INSERT INTO journals VALUES ('journal')")
            database.execSQL("INSERT INTO text_notes VALUES ('note')")
            database.execSQL("INSERT INTO journal_content_links VALUES ('journal', 'note')")
            assertFalse(NativeArchive3SourceCensus.hasUnrepresentedJournalLinks(database))
            database.execSQL("INSERT INTO journal_content_links VALUES ('journal', 'missing')")
            assertTrue(NativeArchive3SourceCensus.hasUnrepresentedJournalLinks(database))
            database.execSQL("DELETE FROM journal_content_links WHERE content_id = 'missing'")
            database.execSQL("INSERT INTO journal_content_links VALUES ('missing-journal', 'note')")
            assertTrue(NativeArchive3SourceCensus.hasUnrepresentedJournalLinks(database))
        } finally {
            helper.close()
        }
    }

    @Test
    fun `media captions must resolve to an exported media note`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper =
            FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration
                    .builder(context)
                    .name("native-archive3-caption-coverage-${System.nanoTime()}.db")
                    .callback(
                        object : SupportSQLiteOpenHelper.Callback(1) {
                            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                                for (table in listOf("image_notes", "audio_notes", "video_notes")) {
                                    db.execSQL("CREATE TABLE $table (uid TEXT NOT NULL)")
                                }
                                db.execSQL("CREATE TABLE media_captions (noteId TEXT NOT NULL)")
                            }

                            override fun onUpgrade(
                                db: androidx.sqlite.db.SupportSQLiteDatabase,
                                oldVersion: Int,
                                newVersion: Int,
                            ) = Unit
                        },
                    ).build(),
            )
        try {
            val database = helper.writableDatabase
            database.execSQL("INSERT INTO image_notes VALUES ('image')")
            database.execSQL("INSERT INTO media_captions VALUES ('image')")
            assertFalse(NativeArchive3SourceCensus.hasUnrepresentedMediaCaptions(database))
            database.execSQL("INSERT INTO media_captions VALUES ('missing')")
            assertTrue(NativeArchive3SourceCensus.hasUnrepresentedMediaCaptions(database))
        } finally {
            helper.close()
        }
    }

    @Test
    fun `unreviewed source schema cannot claim a complete archive`() {
        assertEquals(true, NativeArchive3SourceCensus.reviewedRoomIdentity("d8b23ded9a1a82c49e39b8df9ef25e35"))
        assertEquals(false, NativeArchive3SourceCensus.reviewedRoomIdentity("a future schema"))
    }

    @Test
    fun `empty unsupported tables do not block a journal-only source`() {
        val rows = mapOf("journals" to 1L, "text_notes" to 1L, "transcriptions" to 0L, "audio_tags" to 0L)
        assertEquals(emptySet(), NativeArchive3SourceCensus.unsupportedPopulatedTables(rows))
    }

    @Test
    fun `unmapped settings and future preference keys block complete scope`() {
        val preferences =
            preferencesOf(
                stringPreferencesKey("display_name") to "A person",
                stringPreferencesKey("identity.canonical_owner_id") to "11111111-1111-1111-1111-111111111111",
                booleanPreferencesKey("quiet_save_preference_enabled") to false,
                stringPreferencesKey("future_personal_setting") to "value",
                stringPreferencesKey("identity.future_personal_setting") to "value",
                stringPreferencesKey("sync_future_personal_setting") to "value",
            )
        assertEquals(
            setOf(
                "quiet_save_preference_enabled",
                "future_personal_setting",
                "identity.future_personal_setting",
                "sync_future_personal_setting",
            ),
            NativeArchive3SourceCensus.unsupportedPreferences(preferences),
        )
    }
}
