package app.logdate.client.database

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.logdate.client.database.entities.JournalEntity
import app.logdate.client.database.entities.TextNoteEntity
import app.logdate.client.database.migrations.MIGRATION_25_26
import app.logdate.client.database.migrations.MIGRATION_26_27
import app.logdate.client.database.migrations.MIGRATION_39_40
import app.logdate.client.database.migrations.MIGRATION_40_41
import app.logdate.client.database.migrations.MIGRATION_45_46
import app.logdate.client.database.migrations.MIGRATION_46_47
import app.logdate.client.database.migrations.MIGRATION_5_6
import app.logdate.client.database.migrations.MIGRATION_6_7
import app.logdate.client.database.migrations.MIGRATION_7_8
import app.logdate.client.database.migrations.MIGRATION_8_9
import app.logdate.client.database.migrations.MIGRATION_9_10
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Android instrumentation tests for verifying Room database migrations and overall data integrity.
 *
 * This test suite utilizes [MigrationTestHelper] to perform step-through migrations across
 * various schema versions of [LogDateDatabase]. It ensures that existing data is correctly
 * transformed, new tables and indices are properly created, and that the database remains
 * functional after both individual migrations and long-chain migration paths.
 */
@OptIn(ExperimentalUuidApi::class)
@RunWith(AndroidJUnit4::class)
class DatabaseMigrationIntegrationTest {
    private val testDatabaseName = "migration-test"

    @get:Rule
    val helper: MigrationTestHelper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            LogDateDatabase::class.java,
            listOf(),
            FrameworkSQLiteOpenHelperFactory(),
        )

    @Test
    fun `migration from5 to6`() {
        helper.createDatabase(testDatabaseName, 5).apply {
            // Insert test data in version 5 schema
            insertLegacyIntegerJournal(id = 1, title = "Test Journal", description = "Test Description")
            close()
        }

        val db = helper.runMigrationsAndValidate(testDatabaseName, 6, true, MIGRATION_5_6)

        // Verify data integrity after migration
        val cursor = db.query("SELECT * FROM journals WHERE title = 'Test Journal'")
        assertTrue(cursor.moveToFirst())
        assertEquals("Test Journal", cursor.getString(cursor.getColumnIndexOrThrow("title")))
        assertEquals("Test Description", cursor.getString(cursor.getColumnIndexOrThrow("description")))
        cursor.close()
    }

    @Test
    fun `migration from6 to7`() {
        helper.createDatabase(testDatabaseName, 6).apply {
            // Insert test data in version 6 schema
            insertLegacyIntegerJournal(id = 1, title = "Test Journal", description = "Test Description")
            close()
        }

        val db = helper.runMigrationsAndValidate(testDatabaseName, 7, true, MIGRATION_6_7)

        // Verify data integrity after migration
        val cursor = db.query("SELECT * FROM journals WHERE title = 'Test Journal'")
        assertTrue(cursor.moveToFirst())
        assertEquals("Test Journal", cursor.getString(cursor.getColumnIndexOrThrow("title")))
        cursor.close()
    }

    @Test
    fun `migration from7 to8`() {
        helper.createDatabase(testDatabaseName, 7).apply {
            // Insert test data in version 7 schema
            insertLegacyTextJournal(id = "1", title = "Test Journal", description = "Test Description")
            close()
        }

        val db = helper.runMigrationsAndValidate(testDatabaseName, 8, true, MIGRATION_7_8)

        // Verify data integrity after migration
        val cursor = db.query("SELECT * FROM journals WHERE title = 'Test Journal'")
        assertTrue(cursor.moveToFirst())
        assertEquals("Test Journal", cursor.getString(cursor.getColumnIndexOrThrow("title")))
        cursor.close()
    }

    @Test
    fun `migration from8 to9`() {
        helper.createDatabase(testDatabaseName, 8).apply {
            // Insert test data in version 8 schema
            insertLegacyTextJournal(id = "1", title = "Test Journal", description = "Test Description")
            insertLegacyTextNote(uid = "1", content = "Test content")
            close()
        }

        val db = helper.runMigrationsAndValidate(testDatabaseName, 9, true, MIGRATION_8_9)

        // Verify data integrity after migration
        val journalCursor = db.query("SELECT * FROM journals WHERE title = 'Test Journal'")
        assertTrue(journalCursor.moveToFirst())
        assertEquals("Test Journal", journalCursor.getString(journalCursor.getColumnIndexOrThrow("title")))
        journalCursor.close()

        val noteCursor = db.query("SELECT * FROM text_notes WHERE content = 'Test content'")
        assertTrue(noteCursor.moveToFirst())
        assertEquals("Test content", noteCursor.getString(noteCursor.getColumnIndexOrThrow("content")))
        noteCursor.close()
    }

    @Test
    fun `migration from9 to10`() {
        helper.createDatabase(testDatabaseName, 9).apply {
            // Insert test data in version 9 schema
            insertLegacyTextJournal(id = "1", title = "Test Journal", description = "Test Description")
            insertLegacyTextNote(uid = "1", content = "Test content")
            close()
        }

        val db = helper.runMigrationsAndValidate(testDatabaseName, 10, true, MIGRATION_9_10)

        // Verify data integrity after migration
        val journalCursor = db.query("SELECT * FROM journals WHERE title = 'Test Journal'")
        assertTrue(journalCursor.moveToFirst())
        assertEquals("Test Journal", journalCursor.getString(journalCursor.getColumnIndexOrThrow("title")))
        journalCursor.close()

        val noteCursor = db.query("SELECT * FROM text_notes WHERE content = 'Test content'")
        assertTrue(noteCursor.moveToFirst())
        assertEquals("Test content", noteCursor.getString(noteCursor.getColumnIndexOrThrow("content")))
        noteCursor.close()
    }

    @Test
    fun `complete chain migration5 to10`() {
        helper.createDatabase(testDatabaseName, 5).apply {
            // Insert comprehensive test data in version 5 schema
            insertLegacyIntegerJournal(
                id = 1,
                title = "Journal One",
                description = "First journal",
                created = 1640995200000,
                lastUpdated = 1640995200000,
            )
            insertLegacyIntegerJournal(
                id = 2,
                title = "Journal Two",
                description = "Second journal",
                created = 1640995300000,
                lastUpdated = 1640995300000,
            )
            close()
        }

        // Run all migrations in sequence
        val db =
            helper.runMigrationsAndValidate(
                testDatabaseName,
                10,
                true,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
            )

        // Verify all data survived the complete migration chain
        val cursor = db.query("SELECT * FROM journals ORDER BY created")
        assertTrue(cursor.moveToFirst())

        // First journal
        assertEquals("Journal One", cursor.getString(cursor.getColumnIndexOrThrow("title")))
        assertEquals("First journal", cursor.getString(cursor.getColumnIndexOrThrow("description")))

        // Second journal
        assertTrue(cursor.moveToNext())
        assertEquals("Journal Two", cursor.getString(cursor.getColumnIndexOrThrow("title")))
        assertEquals("Second journal", cursor.getString(cursor.getColumnIndexOrThrow("description")))

        cursor.close()
    }

    @Test
    fun `migration from39 to40 creates people table`() {
        val databaseName = "$testDatabaseName-39-40"

        helper.createDatabase(databaseName, 39).use { db ->
            assertFalse(db.hasTable("people"))
        }

        val db = helper.runMigrationsAndValidate(databaseName, 40, true, MIGRATION_39_40)

        assertTrue(db.hasTable("people"))
        assertEquals(
            listOf("contact_lookup_key", "name", "origin"),
            db.indexNamesFor("people"),
        )

        db.execSQL(
            """
            INSERT INTO people (
                id,
                name,
                photo_uri,
                aliases,
                relationship_label,
                notes,
                origin,
                contact_lookup_key,
                created,
                last_updated,
                deleted_at
            ) VALUES (
                'person-1',
                'Ava',
                NULL,
                'Av|A',
                'Friend',
                'Met through work',
                'CONTACT_SELECTED',
                'lookup-ava',
                1710000000000,
                1710000000000,
                NULL
            )
            """.trimIndent(),
        )

        val cursor =
            db.query(
                """
                SELECT name, aliases, relationship_label, origin, contact_lookup_key
                FROM people
                WHERE id = 'person-1'
                """.trimIndent(),
            )
        assertTrue(cursor.moveToFirst())
        assertEquals("Ava", cursor.getString(cursor.getColumnIndexOrThrow("name")))
        assertEquals("Av|A", cursor.getString(cursor.getColumnIndexOrThrow("aliases")))
        assertEquals("Friend", cursor.getString(cursor.getColumnIndexOrThrow("relationship_label")))
        assertEquals("CONTACT_SELECTED", cursor.getString(cursor.getColumnIndexOrThrow("origin")))
        assertEquals("lookup-ava", cursor.getString(cursor.getColumnIndexOrThrow("contact_lookup_key")))
        cursor.close()
    }

    @Test
    fun `migration from40 to41 creates people graph tables`() {
        val databaseName = "$testDatabaseName-40-41"

        helper.createDatabase(databaseName, 40).use { db ->
            db.execSQL(
                """
                INSERT INTO people (
                    id,
                    name,
                    photo_uri,
                    aliases,
                    relationship_label,
                    notes,
                    origin,
                    contact_lookup_key,
                    created,
                    last_updated,
                    deleted_at
                ) VALUES (
                    'person-1',
                    'Ava',
                    NULL,
                    'Av|A',
                    NULL,
                    NULL,
                    'CONTACT_SELECTED',
                    'lookup-ava',
                    1710000000000,
                    1710000000000,
                    NULL
                )
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(databaseName, 41, true, MIGRATION_40_41)

        assertTrue(db.hasTable("inferred_person_clusters"))
        assertTrue(db.hasTable("inferred_person_evidence"))
        assertTrue(db.hasTable("person_links"))
        assertTrue(db.hasTable("person_resolution_decisions"))

        db.execSQL(
            """
            INSERT INTO inferred_person_clusters (
                id,
                display_name_hint,
                normalized_name,
                status,
                linked_person_id,
                created,
                last_updated
            ) VALUES (
                'cluster-1',
                'Sam',
                'sam',
                'OPEN',
                NULL,
                1710000000000,
                1710000000000
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO inferred_person_evidence (
                id,
                cluster_id,
                source_type,
                source_id,
                label,
                confidence,
                created
            ) VALUES (
                'evidence-1',
                'cluster-1',
                'ENTRY_TEXT',
                'note-1',
                'Sam came over for dinner',
                0.8,
                1710000000000
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO person_links (
                id,
                person_id,
                target_type,
                target_id,
                provenance,
                confidence,
                status,
                created,
                last_updated
            ) VALUES (
                'link-1',
                'person-1',
                'ENTRY',
                'note-1',
                'INFERRED',
                0.9,
                'ACTIVE',
                1710000000000,
                1710000000000
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO person_resolution_decisions (
                id,
                normalized_name,
                action,
                person_id,
                created,
                last_updated
            ) VALUES (
                'decision-1',
                'sam',
                'SUPPRESS',
                NULL,
                1710000000000,
                1710000000000
            )
            """.trimIndent(),
        )

        val linkCursor =
            db.query(
                """
                SELECT person_id, target_type, provenance
                FROM person_links
                WHERE id = 'link-1'
                """.trimIndent(),
            )
        assertTrue(linkCursor.moveToFirst())
        assertEquals("person-1", linkCursor.getString(linkCursor.getColumnIndexOrThrow("person_id")))
        assertEquals("ENTRY", linkCursor.getString(linkCursor.getColumnIndexOrThrow("target_type")))
        assertEquals("INFERRED", linkCursor.getString(linkCursor.getColumnIndexOrThrow("provenance")))
        linkCursor.close()
    }

    private fun SupportSQLiteDatabase.insertLegacyIntegerJournal(
        id: Int,
        title: String,
        description: String,
        created: Long = 1640995200000,
        lastUpdated: Long = created,
    ) {
        execSQL(
            """
            INSERT INTO journals (id, title, description, created, lastUpdated)
            VALUES ($id, '$title', '$description', $created, $lastUpdated)
            """.trimIndent(),
        )
    }

    private fun SupportSQLiteDatabase.hasTable(tableName: String): Boolean {
        val cursor =
            query(
                """
                SELECT name
                FROM sqlite_master
                WHERE type = 'table' AND name = ?
                """.trimIndent(),
                arrayOf(tableName),
            )
        return cursor.use { it.moveToFirst() }
    }

    private fun SupportSQLiteDatabase.indexNamesFor(tableName: String): List<String> {
        val cursor = query("PRAGMA index_list(`$tableName`)")
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    val indexName = it.getString(it.getColumnIndexOrThrow("name"))
                    add(indexName.removePrefix("index_${tableName}_"))
                }
            }.sorted()
        }
    }

    private fun SupportSQLiteDatabase.insertLegacyTextJournal(
        id: String,
        title: String,
        description: String,
        created: Long = 1640995200000,
        lastUpdated: Long = created,
    ) {
        execSQL(
            """
            INSERT INTO journals (id, title, description, created, lastUpdated)
            VALUES ('$id', '$title', '$description', $created, $lastUpdated)
            """.trimIndent(),
        )
    }

    private fun SupportSQLiteDatabase.insertLegacyTextNote(
        uid: String,
        content: String,
        created: Long = 1640995200000,
        lastUpdated: Long = created,
    ) {
        execSQL(
            """
            INSERT INTO text_notes (uid, content, lastUpdated, created)
            VALUES ('$uid', '$content', $lastUpdated, $created)
            """.trimIndent(),
        )
    }

    @Test
    fun `data integrity with full database operations`() =
        runTest {
            // Create database with latest schema and perform full CRUD operations
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val database =
                Room
                    .inMemoryDatabaseBuilder(context, LogDateDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()

            try {
                val journalDao = database.journalDao()
                val textNoteDao = database.textNoteDao()
                val currentTime = Clock.System.now()

                // Create test journal
                val journal =
                    JournalEntity(
                        id = Uuid.random(),
                        title = "Integration Test Journal",
                        description = "Testing data integrity",
                        created = currentTime,
                        lastUpdated = currentTime,
                    )
                journalDao.create(journal)

                // Create test notes
                val note1 =
                    TextNoteEntity(
                        content = "First test note content",
                        uid = Uuid.random(),
                        created = currentTime,
                        lastUpdated = currentTime,
                    )
                val note2 =
                    TextNoteEntity(
                        content = "Second test note content",
                        uid = Uuid.random(),
                        created = currentTime,
                        lastUpdated = currentTime,
                    )
                textNoteDao.addNote(note1)
                textNoteDao.addNote(note2)
                database.journalNotesDao().addNoteToJournal(journal.id, note1.uid)
                database.journalNotesDao().addNoteToJournal(journal.id, note2.uid)

                // Verify journal retrieval
                val retrievedJournal = journalDao.getJournalById(journal.id)
                assertNotNull(retrievedJournal)
                assertEquals(journal.title, retrievedJournal.title)
                assertEquals(journal.description, retrievedJournal.description)

                // Verify notes retrieval
                val journalWithNotes = database.journalNotesDao().getAll().single()
                assertEquals(2, journalWithNotes.textNotes.size)
                assertTrue(
                    journalWithNotes.textNotes.any { it.uid == note1.uid && it.content == note1.content },
                )
                assertTrue(
                    journalWithNotes.textNotes.any { it.uid == note2.uid && it.content == note2.content },
                )

                // Test journal with notes query
                assertEquals(journal.title, journalWithNotes.journal.title)

                // Test update operations
                val updatedJournal = journal.copy(title = "Updated Title")
                journalDao.update(updatedJournal)

                val reRetrievedJournal = journalDao.getJournalById(journal.id)
                assertNotNull(reRetrievedJournal)
                assertEquals("Updated Title", reRetrievedJournal.title)

                // Test delete operations
                textNoteDao.removeNote(note1.uid)
                val remainingNotes = textNoteDao.getAll()
                assertEquals(1, remainingNotes.size)
                assertEquals(note2.content, remainingNotes.single().content)
            } finally {
                database.close()
            }
        }

    @Test
    fun `migration from25 to26 backfills location sample metadata`() {
        helper.createDatabase(testDatabaseName, 25).apply {
            execSQL(
                """
                INSERT INTO location_logs (
                    user_id,
                    device_id,
                    timestamp,
                    latitude,
                    longitude,
                    altitude,
                    confidence,
                    is_genuine
                ) VALUES (
                    'user-1',
                    'device-1',
                    1710000000000,
                    37.7749,
                    -122.4194,
                    12.0,
                    0.9,
                    1
                )
                """.trimIndent(),
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDatabaseName, 26, true, MIGRATION_25_26)

        val cursor =
            db.query(
                """
                SELECT sample_id, logged_at, capture_pipeline, capture_source, is_mock
                FROM location_logs
                WHERE user_id = 'user-1' AND device_id = 'device-1'
                """.trimIndent(),
            )

        assertTrue(cursor.moveToFirst())
        assertEquals("user-1:device-1:1710000000000", cursor.getString(cursor.getColumnIndexOrThrow("sample_id")))
        assertEquals(1710000000000, cursor.getLong(cursor.getColumnIndexOrThrow("logged_at")))
        assertEquals("LEGACY", cursor.getString(cursor.getColumnIndexOrThrow("capture_pipeline")))
        assertEquals("BACKGROUND_PERIODIC", cursor.getString(cursor.getColumnIndexOrThrow("capture_source")))
        assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("is_mock")))
        cursor.close()

        val indexCursor =
            db.query(
                """
                SELECT name
                FROM sqlite_master
                WHERE type = 'index' AND name = 'index_location_logs_timestamp'
                """.trimIndent(),
            )

        assertFalse(indexCursor.moveToFirst())
        indexCursor.close()
    }

    @Test
    fun `migration from26 to27 removes unexpected location timestamp index`() {
        helper.createDatabase(testDatabaseName, 26).apply {
            execSQL("CREATE INDEX IF NOT EXISTS index_location_logs_timestamp ON location_logs(timestamp)")
            close()
        }

        val db = helper.runMigrationsAndValidate(testDatabaseName, 27, true, MIGRATION_26_27)

        val cursor =
            db.query(
                """
                SELECT name
                FROM sqlite_master
                WHERE type = 'index' AND name = 'index_location_logs_timestamp'
                """.trimIndent(),
            )

        assertFalse(cursor.moveToFirst())
        cursor.close()
    }

    @Test
    fun `migration from45 to46 preserves sync metadata as unowned`() {
        val databaseName = "$testDatabaseName-45-46"
        helper.createDatabase(databaseName, 45).use { db ->
            db.execSQL(
                """
                INSERT INTO pending_uploads (serverOrigin, entityType, entityId, operation, createdAt, retryCount)
                VALUES ('https://cloud-staging.logdate.app', 'JOURNAL', 'journal-1', 'CREATE', 1710000000000, 2)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO sync_cursors (serverOrigin, entityType, lastSyncTimestamp)
                VALUES ('https://cloud-staging.logdate.app', 'JOURNAL', 1710000000100)
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(databaseName, 46, true, MIGRATION_45_46)
        val pending = db.query("SELECT ownerId, retryCount FROM pending_uploads WHERE entityId = 'journal-1'")
        assertTrue(pending.moveToFirst())
        assertEquals("", pending.getString(pending.getColumnIndexOrThrow("ownerId")))
        assertEquals(2, pending.getInt(pending.getColumnIndexOrThrow("retryCount")))
        pending.close()

        val cursor = db.query("SELECT ownerId, lastSyncTimestamp FROM sync_cursors WHERE entityType = 'JOURNAL'")
        assertTrue(cursor.moveToFirst())
        assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("ownerId")))
        assertEquals(1710000000100, cursor.getLong(cursor.getColumnIndexOrThrow("lastSyncTimestamp")))
        cursor.close()

        db.execSQL(
            """
            INSERT INTO pending_uploads (ownerId, serverOrigin, entityType, entityId, operation, createdAt, retryCount)
            VALUES ('other-owner', 'https://cloud-staging.logdate.app', 'JOURNAL', 'journal-1', 'UPDATE', 1710000000200, 0)
            """.trimIndent(),
        )
        val ownerCount = db.query("SELECT COUNT(*) FROM pending_uploads WHERE entityId = 'journal-1'")
        assertTrue(ownerCount.moveToFirst())
        assertEquals(2, ownerCount.getInt(0), "Different owners must not overwrite each other's outbox rows")
        ownerCount.close()
    }

    @Test
    fun `migration from46 to47 gives existing notes a null capture time zone`() {
        val databaseName = "$testDatabaseName-46-47"
        helper.createDatabase(databaseName, 46).use { db ->
            db.insertLegacyTextNote(uid = "note-1", content = "Written before time zones were recorded")
        }

        val db = helper.runMigrationsAndValidate(databaseName, 47, true, MIGRATION_46_47)

        val note = db.query("SELECT content, time_zone_id FROM text_notes WHERE uid = 'note-1'")
        assertTrue(note.moveToFirst())
        assertEquals("Written before time zones were recorded", note.getString(note.getColumnIndexOrThrow("content")))
        assertTrue(note.isNull(note.getColumnIndexOrThrow("time_zone_id")))
        note.close()
    }
}
