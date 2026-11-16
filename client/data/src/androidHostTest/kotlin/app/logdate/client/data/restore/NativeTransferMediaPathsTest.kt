package app.logdate.client.data.restore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import app.logdate.client.data.notes.drafts.AndroidLocalEntryDraftStore
import app.logdate.client.repository.journals.EntryDraft
import app.logdate.client.repository.journals.PendingMediaRecord
import app.logdate.client.repository.journals.PendingMediaType
import app.logdate.shared.model.EditorDraft
import app.logdate.shared.model.SerializableImageBlock
import app.logdate.shared.model.SerializableVideoBlock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

class NativeTransferMediaPathsTest {
    @Test
    fun `only app-owned media references become native transfer paths`() =
        fixture { files ->
            val paths =
                nativeTransferMediaPaths(
                    listOf(
                        "logdate-media://library/objects/sha256/aa/aabb.jpg",
                        "logdate-media://recordings/voice.m4a",
                        "file:///data/user/0/studio.hypertext.logdate/files/user_media/old%20photo.jpg",
                        "file:///data/user/0/studio.hypertext.logdate/files/audio_notes/staging/pending.m4a",
                        "content://media/external/images/media/1",
                        "file:///data/user/0/other.app/files/media/private.jpg",
                        "logdate-media://library/objects/sha256/aa/evil%0A.jpg",
                    ),
                    "studio.hypertext.logdate",
                    files,
                )

            assertTrue("files/media/objects/sha256/aa/aabb.jpg" in paths)
            assertTrue("files/audio_notes/voice.m4a" in paths)
            assertTrue("files/user_media/old photo.jpg" in paths)
            assertTrue("files/audio_notes/staging/pending.m4a" in paths)
            assertEquals(4, paths.size)
        }

    @Test
    fun `encoded legacy reference selects the first existing spelling only`() =
        fixture { files ->
            File(files, "user_media/old photo.jpg").apply {
                parentFile!!.mkdirs()
                writeText("selected")
            }
            File(files, "user_media/old%20photo.jpg").writeText("orphan")

            assertEquals(
                setOf("files/user_media/old photo.jpg"),
                nativeTransferMediaPaths(
                    listOf("file:///data/user/0/studio.hypertext.logdate/files/user_media/old%20photo.jpg"),
                    "studio.hypertext.logdate",
                    files,
                ),
            )
        }

    @Test
    fun `editor and entry draft references include pending recording fallback`() =
        fixture { files ->
            val now = Clock.System.now()
            val image = "logdate-media://library/objects/sha256/aa/aabb.jpg"
            val thumbnail = "logdate-media://library/thumbnail.jpg"
            val draft =
                EditorDraft(
                    blocks =
                        listOf(
                            SerializableImageBlock(Uuid.random(), now, uri = image),
                            SerializableVideoBlock(Uuid.random(), now, uri = "content://external/video", thumbnailUri = thumbnail),
                        ),
                )
            val pending = "/data/user/0/studio.hypertext.logdate/files/audio_notes/staging/pending.m4a"
            val fallback = "/data/user/0/studio.hypertext.logdate/files/audio_notes/fallback.m4a"
            val entryDraft =
                EntryDraft(
                    id = Uuid.random(),
                    notes = emptyList(),
                    createdAt = now,
                    updatedAt = now,
                    pendingMedia =
                        listOf(
                            PendingMediaRecord(
                                Uuid.random(),
                                PendingMediaType.AUDIO,
                                now,
                                filePath = pending,
                                fallbackFilePath = fallback,
                            ),
                        ),
                )
            val preferences = mutablePreferencesOf(stringPreferencesKey("editor_drafts_anonymous") to Json.encodeToString(listOf(draft)))
            val references = nativeTransferDraftMediaReferences(preferences, listOf(entryDraft))

            assertEquals(setOf(image, thumbnail, "content://external/video", pending, fallback), references)
            assertEquals(
                setOf(
                    "files/media/objects/sha256/aa/aabb.jpg",
                    "files/media/thumbnail.jpg",
                    "files/audio_notes/staging/pending.m4a",
                    "files/audio_notes/fallback.m4a",
                ),
                nativeTransferMediaPaths(references, "studio.hypertext.logdate", files),
            )
        }

    @Test
    fun `pending camera draft selects its private original for device transfer`() =
        fixture { files ->
            val source =
                "/data/user/0/studio.hypertext.logdate/files/camera-captures/LOGDATE_20261024_120000.jpg.00000000-0000-0000-0000-000000000001.tmp"
            val now = Clock.System.now()
            val draft =
                EntryDraft(
                    id = Uuid.random(),
                    notes = emptyList(),
                    createdAt = now,
                    updatedAt = now,
                    pendingMedia =
                        listOf(PendingMediaRecord(Uuid.random(), PendingMediaType.CAMERA_PHOTO, now, filePath = source)),
                )

            val references = nativeTransferDraftMediaReferences(mutablePreferencesOf(), listOf(draft))

            assertEquals(
                setOf("files/camera-captures/${source.substringAfterLast('/')}"),
                nativeTransferMediaPaths(references, "studio.hypertext.logdate", files),
            )
        }

    @Test
    fun `archive entry drafts are read from the pinned transfer copy`() =
        fixture { files ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val sourceFile = File(files, "source/entry_drafts.preferences_pb").apply { parentFile!!.mkdirs() }
                val sourceStore =
                    AndroidLocalEntryDraftStore(
                        PreferenceDataStoreFactory.createWithPath(scope = scope, produceFile = { sourceFile.path.toPath() }),
                    )
                val now = Clock.System.now()
                val pinnedDraft = EntryDraft(Uuid.random(), emptyList(), now, now)
                val laterDraft = EntryDraft(Uuid.random(), emptyList(), now, now)
                runBlocking { sourceStore.saveDraft(pinnedDraft) }

                val pinnedFile =
                    File(files, "snapshot/files/datastore/entry_drafts.preferences_pb").apply {
                        parentFile!!.mkdirs()
                    }
                Files.copy(sourceFile.toPath(), pinnedFile.toPath())
                runBlocking { sourceStore.saveDraft(laterDraft) }

                assertEquals(listOf(pinnedDraft), runBlocking { readNativeTransferEntryDrafts(pinnedFile) })
            } finally {
                scope.cancel()
            }
        }

    private fun fixture(block: (File) -> Unit) {
        val files = Files.createTempDirectory("native-transfer-media-paths-").toFile()
        try {
            block(files)
        } finally {
            check(files.deleteRecursively())
        }
    }
}
