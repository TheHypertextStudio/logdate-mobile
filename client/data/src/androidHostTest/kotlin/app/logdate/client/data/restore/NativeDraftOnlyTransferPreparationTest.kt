package app.logdate.client.data.restore

import android.app.Application
import android.content.ContextWrapper
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import app.logdate.client.datastore.createAndroidMainDataStore
import app.logdate.client.device.restore.PostRestoreDetector
import app.logdate.client.device.restore.verifyNativeJournalMediaInventory
import app.logdate.client.device.restore.writeNativeJournalMediaInventory
import app.logdate.shared.model.EditorDraft
import app.logdate.shared.model.SerializableImageBlock
import app.logdate.shared.model.SerializableTextBlock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34], application = Application::class)
class NativeDraftOnlyTransferPreparationTest {
    @Test
    fun `drafts created before the journal open use the normal encrypted snapshot path`() {
        Fixture().use { fixture ->
            var opens = 0

            prepareNativeDraftOnlyDatabase(
                fixture.context,
                {
                    opens++
                    fixture.database.apply {
                        parentFile!!.mkdirs()
                        writeText("encrypted journal created")
                    }
                },
                hasEntryDrafts = { true },
            )

            assertEquals(1, opens)
            assertTrue(fixture.database.isFile)
        }
    }

    @Test
    fun `an empty installation is not initialized merely because Android requested backup`() {
        Fixture().use { fixture ->
            prepareNativeDraftOnlyDatabase(fixture.context, { error("unexpected database initialization") })

            assertFalse(fixture.database.exists())
        }
    }

    @Test
    fun `an editor draft in main preferences also gains an encrypted source journal`() {
        Fixture().use { fixture ->
            val time = Instant.fromEpochMilliseconds(1_700_000_000_000)
            val draft =
                EditorDraft(
                    blocks =
                        listOf(
                            SerializableImageBlock(
                                Uuid.random(),
                                time,
                                uri = "file:/data/user/0/studio.hypertext.logdate/files/user_media/draft.png",
                            ),
                        ),
                )
            runBlocking {
                createAndroidMainDataStore(fixture.context).edit { preferences ->
                    preferences[stringPreferencesKey("editor_drafts_anonymous")] = Json.encodeToString(listOf(draft))
                }
            }

            prepareNativeDraftOnlyDatabase(
                fixture.context,
                {
                    fixture.database.apply {
                        parentFile!!.mkdirs()
                        writeText("encrypted journal created")
                    }
                },
                hasEntryDrafts = { false },
            )

            assertTrue(fixture.database.isFile)
        }
    }

    @Test
    fun `a text only editor draft in main preferences also gains a source journal`() {
        Fixture().use { fixture ->
            val draft =
                EditorDraft(
                    blocks =
                        listOf(
                            SerializableTextBlock(
                                Uuid.random(),
                                Instant.fromEpochMilliseconds(1_700_000_000_000),
                                content = "Unfinished journal",
                            ),
                        ),
                )
            runBlocking {
                createAndroidMainDataStore(fixture.context).edit { preferences ->
                    preferences[stringPreferencesKey("editor_drafts_anonymous")] = Json.encodeToString(listOf(draft))
                }
            }

            prepareNativeDraftOnlyDatabase(
                fixture.context,
                {
                    fixture.database.apply {
                        parentFile!!.mkdirs()
                        writeText("encrypted journal created")
                    }
                },
                hasEntryDrafts = { false },
            )

            assertTrue(fixture.database.isFile)
        }
    }

    @Test
    fun `draft created during a database-free backup invalidates that backup`() {
        Fixture().use { fixture ->
            val snapshot = NativeJournalTransferSnapshot(fixture.context)
            snapshot.prepare()
            val draftFile = File(fixture.context.filesDir, "datastore/entry_drafts.preferences_pb")
            check(draftFile.parentFile!!.mkdirs() || draftFile.parentFile!!.isDirectory)
            draftFile.writeText("draft saved after source selection")

            assertFailsWith<IllegalStateException> { snapshot.finish() }
        }
    }

    @Test
    fun `journal created during a database-free backup invalidates that backup`() {
        Fixture().use { fixture ->
            val snapshot = NativeJournalTransferSnapshot(fixture.context)
            snapshot.prepare()
            check(fixture.database.parentFile!!.mkdirs() || fixture.database.parentFile!!.isDirectory)
            fixture.database.writeText("journal created during traversal")

            assertFailsWith<IllegalStateException> { snapshot.finish() }
        }
    }

    @Test
    fun `an interrupted restore cannot gain a new empty database during backup`() {
        Fixture().use { fixture ->
            PostRestoreDetector(fixture.context).beginNativeRestoreSession()

            val error =
                assertFailsWith<IllegalStateException> {
                    prepareNativeDraftOnlyDatabase(
                        fixture.context,
                        { error("unexpected database initialization") },
                        hasEntryDrafts = { true },
                    )
                }
            assertTrue(error.message.orEmpty().contains("Native restore must finish"))
            assertFalse(fixture.database.exists())
        }
    }

    @Test
    fun `snapshot emits selected originals while orphaned and unselected staging media stay local`() {
        Fixture().use { fixture ->
            val files = fixture.context.filesDir
            val selected =
                listOf("media/objects/photo.jpg", "media/objects/movie.mp4", "audio_notes/accepted.m4a", "user_media/retained.jpg")
            val unselected = listOf("media/objects/deleted.jpg", "audio_notes/staging/unfinished.m4a", "user_media/orphan.jpg")
            (selected + unselected).forEach { path ->
                File(files, path).apply {
                    parentFile!!.mkdirs()
                    writeText(path)
                }
            }
            val inventoryFile = File(files, "inventory.json")
            val paths =
                nativeTransferMediaPaths(
                    selected.map {
                        "file:///data/user/0/${fixture.context.packageName}/files/$it"
                    },
                    fixture.context.packageName,
                    files,
                )
            writeNativeJournalMediaInventory(inventoryFile, files, paths)
            val inventory = verifyNativeJournalMediaInventory(inventoryFile, nativeSnapshotChecksum(inventoryFile))
            val pinnedPreferences =
                File(files, "native_journal_transfer/current/files/logdate.preferences_pb").apply {
                    parentFile!!.mkdirs()
                    writeText("pinned preferences")
                }
            val snapshot = NativeJournalTransferSnapshot(fixture.context)
            // Supply the verified selection at the emitter boundary without opening SQLCipher.
            mapOf(
                "prepared" to true,
                "selectedMedia" to inventory.files,
                "preferencesChecksum" to nativeSnapshotChecksum(pinnedPreferences),
            ).forEach { (name, value) ->
                snapshot.javaClass
                    .getDeclaredField(name)
                    .apply { isAccessible = true }
                    .set(snapshot, value)
            }
            val emitted = mutableListOf<Pair<String, String>>()

            snapshot.backupSelectedMedia { file -> emitted += file.relativeTo(files).invariantSeparatorsPath to file.readText() }

            assertEquals(selected.sorted().map { it to it }, emitted.sortedBy { it.first })
            assertTrue(unselected.all { File(files, it).isFile })
            assertFailsWith<IllegalStateException> {
                snapshot.backupSelectedMedia { it.writeText("changed during emission") }
            }
        }
    }

    private class Fixture : AutoCloseable {
        private val root = Files.createTempDirectory("native-draft-only-").toFile()
        private val files = File(root, "files").apply { mkdirs() }
        private val noBackup = File(root, "no-backup").apply { mkdirs() }
        val database = File(root, "database/logdate")
        val context =
            object : ContextWrapper(ApplicationProvider.getApplicationContext<Application>()) {
                override fun getFilesDir() = files

                override fun getNoBackupFilesDir() = noBackup

                override fun getApplicationContext() = this

                override fun getDatabasePath(name: String) = File(root, "database/$name")
            }

        override fun close() {
            check(root.deleteRecursively())
        }
    }
}
