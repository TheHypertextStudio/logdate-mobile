package app.logdate.client.media

import android.Manifest
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Instrumented tests for the [AndroidMediaManager] implementation.
 *
 * This suite validates the application's interaction with the Android [MediaStore] and
 * local file system. It specifically tests media discovery, persistence of newly
 * captured images and videos, and complex recovery scenarios for media files with
 * missing or corrupted metadata across various Android API levels.
 */
@RunWith(AndroidJUnit4::class)
class AndroidMediaManagerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val mediaManager =
        AndroidMediaManager(
            context.contentResolver,
            context,
            Dispatchers.Unconfined,
        )
    private val requiredPermissions: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    @get:Rule
    val permissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(*requiredPermissions)

    @After
    fun tearDown() {
        context.filesDir.resolve("user_media").listFiles()?.forEach { file ->
            file.delete()
        }
    }

    @Test
    fun `get recent media backfills legacy entry media into media store`() =
        runTest {
            val legacyFile = createLegacyImageFile()
            var publishedUri: String? = null

            try {
                val recentMedia = mediaManager.getRecentMedia().first()
                val backfilled = recentMedia.firstOrNull { it.name == legacyFile.name }

                assertNotNull(backfilled, "Expected the legacy file to appear in recent media")
                assertTrue(backfilled.uri.startsWith("content://media"))
                assertTrue(legacyFile.exists())

                publishedUri = backfilled.uri
            } finally {
                publishedUri?.let { uri ->
                    context.contentResolver.delete(android.net.Uri.parse(uri), null, null)
                }
                legacyFile.delete()
            }
        }

    @Test
    fun `get media reads file uris and uses date added fallback when needed`() =
        runTest {
            val imageFile = createImageFile(context.cacheDir, "file-image")
            val videoFile = File(context.cacheDir, "file-video-${Uuid.random()}.mp4")
            videoFile.writeBytes(byteArrayOf(0, 1, 2, 3))
            videoFile.setLastModified(1_234_567_890_000L)

            try {
                val image = mediaManager.getMedia(Uri.fromFile(imageFile).toString())
                assertTrue(image is MediaObject.Image)
                assertEquals(imageFile.name, image.name)
                assertEquals(imageFile.length().toInt(), image.size)

                // Corrupt video file: MediaMetadataRetriever can't parse 4 bytes of garbage,
                // but the user must still see the file in the gallery rather than have it
                // disappear. Duration falls back to ZERO so the row stays visible.
                val unreadableVideo = mediaManager.getMedia(Uri.fromFile(videoFile).toString())
                assertTrue(unreadableVideo is MediaObject.Video)
                assertEquals(videoFile.name, unreadableVideo.name)
                assertEquals(videoFile.length().toInt(), unreadableVideo.size)
                assertEquals(Duration.ZERO, unreadableVideo.duration)

                val environment = createMockEnvironment()
                val fallbackUri = Uri.parse("content://example.media/items/fallback.png")

                try {
                    every { environment.contentResolver.getType(fallbackUri) } returns null
                    every {
                        environment.contentResolver.query(
                            fallbackUri,
                            any(),
                            null,
                            null,
                            null,
                        )
                    } answers {
                        when (secondArg<Array<String>>().toList()) {
                            listOf(MediaStore.MediaColumns.DISPLAY_NAME) -> {
                                emptyCursor(MediaStore.MediaColumns.DISPLAY_NAME)
                            }

                            listOf(
                                MediaStore.MediaColumns.DATE_TAKEN,
                                MediaStore.MediaColumns.DATE_ADDED,
                            ),
                            -> {
                                singleRowCursor(
                                    MediaStore.MediaColumns.DATE_TAKEN to 0L,
                                    MediaStore.MediaColumns.DATE_ADDED to 42L,
                                )
                            }

                            else -> {
                                emptyCursor(*secondArg<Array<String>>())
                            }
                        }
                    }
                    every {
                        environment.contentResolver.openInputStream(fallbackUri)
                    } returns ByteArrayInputStream(byteArrayOf(9, 8, 7))

                    val payload = environment.mediaManager.readMedia(fallbackUri.toString())
                    assertEquals("fallback.png", payload.fileName)
                    assertEquals("image/png", payload.mimeType)
                    assertEquals(3, payload.data.size)

                    val fallbackNameUri = Uri.parse("content://example.media/items/blob")
                    every { environment.contentResolver.getType(fallbackNameUri) } returns null
                    every {
                        environment.contentResolver.query(
                            fallbackNameUri,
                            any(),
                            null,
                            null,
                            null,
                        )
                    } answers {
                        when (secondArg<Array<String>>().toList()) {
                            listOf(MediaStore.MediaColumns.DISPLAY_NAME) -> emptyCursor(MediaStore.MediaColumns.DISPLAY_NAME)
                            else -> emptyCursor(*secondArg<Array<String>>())
                        }
                    }
                    every {
                        environment.contentResolver.openInputStream(fallbackNameUri)
                    } returns ByteArrayInputStream(byteArrayOf())

                    assertFailsWith<IllegalArgumentException> {
                        environment.mediaManager.readMedia(fallbackNameUri.toString())
                    }
                } finally {
                    environment.filesDir.deleteRecursively()
                    clearAllMocks()
                }
            } finally {
                imageFile.delete()
                videoFile.delete()
            }
        }

    @Test
    fun `read media reads raw and file uri audio recordings`() =
        runTest {
            val audioBytes = byteArrayOf(0, 0, 0, 24, 102, 116, 121, 112, 77, 52, 65, 32)
            val audioFile = File(context.cacheDir, "recording-${Uuid.random()}.m4a")
            audioFile.writeBytes(audioBytes)

            try {
                listOf(audioFile.absolutePath, Uri.fromFile(audioFile).toString()).forEach { mediaRef ->
                    val payload = mediaManager.readMedia(mediaRef)

                    assertEquals(audioFile.name, payload.fileName)
                    assertEquals("audio/mp4", payload.mimeType)
                    assertEquals(audioBytes.size.toLong(), payload.sizeBytes)
                    assertTrue(audioBytes.contentEquals(payload.data))
                }
            } finally {
                audioFile.delete()
            }
        }

    @Test
    fun `read media reads content uri audio recording`() =
        runTest {
            val environment = createMockEnvironment()
            val audioUri = Uri.parse("content://example.media/audio/42")
            val audioBytes = byteArrayOf(1, 3, 3, 7)

            try {
                every { environment.contentResolver.getType(audioUri) } returns "audio/mp4"
                every {
                    environment.contentResolver.query(
                        audioUri,
                        arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                        null,
                        null,
                        null,
                    )
                } returns singleRowCursor(MediaStore.MediaColumns.DISPLAY_NAME to "voice-note.m4a")
                every { environment.contentResolver.openInputStream(audioUri) } returns ByteArrayInputStream(audioBytes)

                val payload = environment.mediaManager.readMedia(audioUri.toString())

                assertEquals("voice-note.m4a", payload.fileName)
                assertEquals("audio/mp4", payload.mimeType)
                assertEquals(audioBytes.size.toLong(), payload.sizeBytes)
                assertTrue(audioBytes.contentEquals(payload.data))
            } finally {
                environment.filesDir.deleteRecursively()
                clearAllMocks()
            }
        }

    @Test
    fun `save media from file persists image in private canonical store`() =
        runTest {
            val sourceFile = createImageFile(context.cacheDir, "source")
            var savedUri: String? = null

            try {
                savedUri =
                    mediaManager.saveMediaFromFile(
                        sourceFilePath = sourceFile.absolutePath,
                        fileName = sourceFile.name,
                        mimeType = "image/png",
                    )

                assertTrue(savedUri.startsWith("file:"))
                assertTrue(File(Uri.parse(savedUri).path.orEmpty()).startsWith(context.filesDir.resolve("media/objects/sha256")))
                assertTrue(File(Uri.parse(savedUri).path.orEmpty()).readBytes().contentEquals(sourceFile.readBytes()))
            } finally {
                savedUri?.let { uri -> File(Uri.parse(uri).path.orEmpty()).delete() }
                sourceFile.delete()
            }
        }

    @Test
    fun `save media from file never publishes private media to media store`() =
        runTest {
            val environment = createMockEnvironment()
            val sourceFile = createImageFile(environment.filesDir, "private-copy")

            try {
                val uri =
                    environment.mediaManager.saveMediaFromFile(
                        sourceFilePath = sourceFile.absolutePath,
                        fileName = sourceFile.name,
                        mimeType = "image/png",
                    )

                assertTrue(uri.startsWith("file:"))
                verify(exactly = 0) { environment.contentResolver.insert(any(), any()) }
                verify(exactly = 0) { environment.contentResolver.openOutputStream(any(), any()) }
            } finally {
                environment.filesDir.deleteRecursively()
                clearAllMocks()
            }
        }

    @Test
    fun `save media from file rejects unsupported mime type`() =
        runTest {
            val sourceFile = createImageFile(context.cacheDir, "fallback-file")

            try {
                assertFailsWith<IllegalArgumentException> {
                    mediaManager.saveMediaFromFile(
                        sourceFilePath = sourceFile.absolutePath,
                        fileName = "../fallback.txt",
                        mimeType = "text/plain",
                    )
                }
                assertTrue(
                    context.filesDir
                        .resolve("user_media")
                        .listFiles()
                        .isNullOrEmpty(),
                )
            } finally {
                sourceFile.delete()
            }
        }

    @Test
    fun `save media persists image in private canonical store`() =
        runTest {
            val sourceFile = createImageFile(context.cacheDir, "payload")
            val payloadBytes = sourceFile.readBytes()
            var savedUri: String? = null

            try {
                savedUri =
                    mediaManager.saveMedia(
                        MediaPayload(
                            fileName = "../payload.png",
                            mimeType = "image/png",
                            sizeBytes = payloadBytes.size.toLong(),
                            data = payloadBytes,
                        ),
                    )

                assertTrue(savedUri.startsWith("file:"))
                assertTrue(File(Uri.parse(savedUri).path.orEmpty()).startsWith(context.filesDir.resolve("media/objects/sha256")))
                assertTrue(File(Uri.parse(savedUri).path.orEmpty()).readBytes().contentEquals(payloadBytes))
            } finally {
                savedUri?.let { uri -> File(Uri.parse(uri).path.orEmpty()).delete() }
                sourceFile.delete()
            }
        }

    @Test
    fun `save media persists downloaded audio in private canonical storage`() =
        runTest {
            val environment = createMockEnvironment()
            val audioBytes = byteArrayOf(9, 8, 7, 6, 5)

            try {
                val savedUri =
                    environment.mediaManager.saveMedia(
                        MediaPayload(
                            fileName = "../restored-voice.m4a",
                            mimeType = "audio/mp4",
                            sizeBytes = audioBytes.size.toLong(),
                            data = audioBytes,
                        ),
                    )

                val savedFile = File(requireNotNull(Uri.parse(savedUri).path))
                assertTrue(savedUri.startsWith("file:"))
                assertTrue(savedFile.startsWith(environment.filesDir.resolve("media/objects/sha256")))
                assertTrue(savedFile.name.endsWith(".m4a"))
                assertTrue(audioBytes.contentEquals(savedFile.readBytes()))
                verify(exactly = 0) { environment.contentResolver.insert(any(), any()) }
            } finally {
                environment.filesDir.deleteRecursively()
                clearAllMocks()
            }
        }

    @Test
    fun `save media from file persists downloaded audio in private canonical storage`() =
        runTest {
            val environment = createMockEnvironment()
            val audioBytes = byteArrayOf(4, 3, 2, 1)
            val sourceFile = File(environment.filesDir, "source-${Uuid.random()}.m4a").apply { writeBytes(audioBytes) }

            try {
                val savedUri =
                    environment.mediaManager.saveMediaFromFile(
                        sourceFilePath = sourceFile.absolutePath,
                        fileName = "restored.m4a",
                        mimeType = "audio/mp4",
                    )

                val savedFile = File(requireNotNull(Uri.parse(savedUri).path))
                assertTrue(savedFile.startsWith(environment.filesDir.resolve("media/objects/sha256")))
                assertTrue(audioBytes.contentEquals(savedFile.readBytes()))
                assertTrue(sourceFile.exists(), "Adopting an audio file must not destroy the caller's source")
                verify(exactly = 0) { environment.contentResolver.insert(any(), any()) }
            } finally {
                environment.filesDir.deleteRecursively()
                clearAllMocks()
            }
        }

    @Test
    fun `save media normalizes audio mime and mismatched file extension`() =
        runTest {
            val environment = createMockEnvironment()
            val audioBytes = byteArrayOf(7, 7, 7)

            try {
                val savedUri =
                    environment.mediaManager.saveMedia(
                        MediaPayload(
                            fileName = "VOICE.MP4",
                            mimeType = " Audio/MP4; codecs=mp4a.40.2 ",
                            sizeBytes = audioBytes.size.toLong(),
                            data = audioBytes,
                        ),
                    )

                val savedFile = File(requireNotNull(Uri.parse(savedUri).path))
                val restoredPayload = environment.mediaManager.readMedia(savedUri)
                assertTrue(savedFile.name.endsWith(".m4a"))
                assertEquals("audio/mp4", restoredPayload.mimeType)
                assertTrue(audioBytes.contentEquals(restoredPayload.data))
            } finally {
                environment.filesDir.deleteRecursively()
                clearAllMocks()
            }
        }

    @Test
    fun `save media adds audio extension when file name has none`() =
        runTest {
            val environment = createMockEnvironment()
            val audioBytes = byteArrayOf(6, 5, 4)

            try {
                val savedUri =
                    environment.mediaManager.saveMedia(
                        MediaPayload(
                            fileName = "voice-note",
                            mimeType = "audio/mp4",
                            sizeBytes = audioBytes.size.toLong(),
                            data = audioBytes,
                        ),
                    )

                val restoredPayload = environment.mediaManager.readMedia(savedUri)
                assertEquals("audio/mp4", restoredPayload.mimeType)
                assertTrue(audioBytes.contentEquals(restoredPayload.data))
            } finally {
                environment.filesDir.deleteRecursively()
                clearAllMocks()
            }
        }

    @Test
    fun `save media canonicalizes audio without leaking file names`() =
        runTest {
            val environment = createMockEnvironment()
            val audioBytes = byteArrayOf(1, 2, 1, 2)
            val oversizedNames =
                listOf(
                    "a".repeat(300) + ".m4a",
                    "🎵".repeat(100) + ".m4a",
                )

            try {
                oversizedNames.forEach { oversizedName ->
                    val savedUri =
                        environment.mediaManager.saveMedia(
                            MediaPayload(
                                fileName = oversizedName,
                                mimeType = "audio/mp4",
                                sizeBytes = audioBytes.size.toLong(),
                                data = audioBytes,
                            ),
                        )

                    val savedFile = File(requireNotNull(Uri.parse(savedUri).path))
                    assertTrue(savedFile.name.matches(Regex("[0-9a-f]{64}\\.m4a")))
                    assertEquals("audio/mp4", environment.mediaManager.readMedia(savedUri).mimeType)
                }
            } finally {
                environment.filesDir.deleteRecursively()
                clearAllMocks()
            }
        }

    @Test
    fun `save media removes stale canonical staging files but preserves fresh writers`() =
        runTest {
            val environment = createMockEnvironment()
            val stagingDirectory = environment.filesDir.resolve("media/staging").apply { mkdirs() }
            val staleTemp = stagingDirectory.resolve("${Uuid.random()}.tmp").apply { writeBytes(ByteArray(8)) }
            val freshTemp = stagingDirectory.resolve("${Uuid.random()}.tmp").apply { writeBytes(ByteArray(8)) }
            assertTrue(staleTemp.setLastModified(System.currentTimeMillis() - 25 * 60 * 60 * 1_000L))

            try {
                environment.mediaManager.saveMedia(
                    MediaPayload(
                        fileName = "restored.m4a",
                        mimeType = "audio/mp4",
                        sizeBytes = 1,
                        data = byteArrayOf(1),
                    ),
                )

                assertFalse(staleTemp.exists())
                assertTrue(freshTemp.exists())
            } finally {
                environment.filesDir.deleteRecursively()
                clearAllMocks()
            }
        }

    @Test
    fun `save media does not require media store publishing`() =
        runTest {
            val environment = createMockEnvironment()
            val payloadBytes = byteArrayOf(1, 2, 3, 4)
            val payload =
                MediaPayload(
                    fileName = "publish.png",
                    mimeType = "image/png",
                    sizeBytes = payloadBytes.size.toLong(),
                    data = payloadBytes,
                )

            try {
                val uri = environment.mediaManager.saveMedia(payload)

                assertTrue(uri.startsWith("file:"))
                verify(exactly = 0) { environment.contentResolver.insert(any(), any()) }
            } finally {
                environment.filesDir.deleteRecursively()
                clearAllMocks()
            }
        }

    @Test
    fun `save media rejects unsupported mime type`() =
        runTest {
            val sourceFile = createImageFile(context.cacheDir, "fallback")
            val payloadBytes = sourceFile.readBytes()

            try {
                assertFailsWith<IllegalArgumentException> {
                    mediaManager.saveMedia(
                        MediaPayload(
                            fileName = "../fallback.txt",
                            mimeType = "",
                            sizeBytes = payloadBytes.size.toLong(),
                            data = payloadBytes,
                        ),
                    )
                }
                assertTrue(
                    context.filesDir
                        .resolve("user_media")
                        .listFiles()
                        .isNullOrEmpty(),
                )
            } finally {
                sourceFile.delete()
            }
        }

    @Test
    fun `save media from file does not require media store publishing`() =
        runTest {
            val environment = createMockEnvironment()
            val sourceFile = createImageFile(environment.filesDir, "publish-fallback")

            try {
                val uri =
                    environment.mediaManager.saveMediaFromFile(
                        sourceFilePath = sourceFile.absolutePath,
                        fileName = sourceFile.name,
                        mimeType = "image/png",
                    )

                assertTrue(uri.startsWith("file:"))
                verify(exactly = 0) { environment.contentResolver.insert(any(), any()) }
            } finally {
                environment.filesDir.deleteRecursively()
                clearAllMocks()
            }
        }

    @Test
    fun `save media from file persists video in private canonical store`() =
        runTest {
            val sourceFile = File(context.cacheDir, "video-${Uuid.random()}.mp4")
            sourceFile.writeBytes(byteArrayOf(0, 1, 2, 3, 4))
            sourceFile.setLastModified(1_234_567_890_000L)
            var savedUri: String? = null

            try {
                savedUri =
                    mediaManager.saveMediaFromFile(
                        sourceFilePath = sourceFile.absolutePath,
                        fileName = "../clip.mp4",
                        mimeType = "video/mp4",
                    )

                assertTrue(savedUri.startsWith("file:"))
                assertTrue(File(Uri.parse(savedUri).path.orEmpty()).startsWith(context.filesDir.resolve("media/objects/sha256")))
                assertTrue(File(Uri.parse(savedUri).path.orEmpty()).readBytes().contentEquals(sourceFile.readBytes()))
            } finally {
                savedUri?.let { uri -> File(Uri.parse(uri).path.orEmpty()).delete() }
                sourceFile.delete()
            }
        }

    @Test
    fun `add to default collection publishes content uri into media store`() =
        runTest {
            val environment = createMockEnvironment()
            val sourceUri = Uri.parse("content://example.media/items/1")
            val payloadBytes = byteArrayOf(10, 11, 12)
            val valuesSlot = slot<ContentValues>()

            try {
                every { environment.contentResolver.getType(sourceUri) } returns "image/png"
                every {
                    environment.contentResolver.query(
                        sourceUri,
                        any(),
                        null,
                        null,
                        null,
                    )
                } answers {
                    when (secondArg<Array<String>>().toList()) {
                        listOf(MediaStore.MediaColumns.DISPLAY_NAME) -> {
                            singleRowCursor(MediaStore.MediaColumns.DISPLAY_NAME to "../camera.png")
                        }

                        listOf(
                            MediaStore.MediaColumns.DATE_TAKEN,
                            MediaStore.MediaColumns.DATE_ADDED,
                        ),
                        -> {
                            singleRowCursor(
                                MediaStore.MediaColumns.DATE_TAKEN to 1_000L,
                                MediaStore.MediaColumns.DATE_ADDED to 1L,
                            )
                        }

                        else -> {
                            emptyCursor(*secondArg<Array<String>>())
                        }
                    }
                }
                every { environment.contentResolver.openInputStream(sourceUri) } returns ByteArrayInputStream(payloadBytes)
                every { environment.contentResolver.insert(any(), capture(valuesSlot)) } returns
                    Uri.parse("content://media/external/images/media/101")
                every { environment.contentResolver.openOutputStream(any(), any()) } returns java.io.ByteArrayOutputStream()
                every { environment.contentResolver.update(any(), any(), any(), any()) } returns 1

                environment.mediaManager.addToDefaultCollection(sourceUri.toString())

                verify(exactly = 1) {
                    environment.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, any())
                    environment.contentResolver.openOutputStream(Uri.parse("content://media/external/images/media/101"), "w")
                    environment.contentResolver.update(Uri.parse("content://media/external/images/media/101"), any(), null, null)
                }
                assertEquals("__camera.png", valuesSlot.captured.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
                assertEquals("image/png", valuesSlot.captured.getAsString(MediaStore.MediaColumns.MIME_TYPE))
                assertEquals(1_000L, valuesSlot.captured.getAsLong(MediaStore.MediaColumns.DATE_TAKEN))
                assertEquals(1, valuesSlot.captured.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
            } finally {
                environment.filesDir.deleteRecursively()
                clearAllMocks()
            }
        }

    @Test
    fun `add to default collection throws when timestamp metadata is missing`() =
        runTest {
            val environment = createMockEnvironment()
            val sourceUri = Uri.parse("content://example.media/items/no-timestamp.png")

            try {
                every { environment.contentResolver.getType(sourceUri) } returns "image/png"
                every {
                    environment.contentResolver.query(
                        sourceUri,
                        any(),
                        null,
                        null,
                        null,
                    )
                } answers {
                    when (secondArg<Array<String>>().toList()) {
                        listOf(MediaStore.MediaColumns.DISPLAY_NAME) -> {
                            singleRowCursor(MediaStore.MediaColumns.DISPLAY_NAME to "../no-timestamp.png")
                        }

                        listOf(
                            MediaStore.MediaColumns.DATE_TAKEN,
                            MediaStore.MediaColumns.DATE_ADDED,
                        ),
                        -> {
                            emptyCursor(
                                MediaStore.MediaColumns.DATE_TAKEN,
                                MediaStore.MediaColumns.DATE_ADDED,
                            )
                        }

                        else -> {
                            emptyCursor(*secondArg<Array<String>>())
                        }
                    }
                }

                assertFailsWith<IllegalStateException> {
                    environment.mediaManager.addToDefaultCollection(sourceUri.toString())
                }
                assertTrue(
                    environment.filesDir
                        .resolve("user_media")
                        .listFiles()
                        .isNullOrEmpty(),
                )
            } finally {
                environment.filesDir.deleteRecursively()
                clearAllMocks()
            }
        }

    @Test
    fun `add to default collection fails when publish and fallback copy fail`() =
        runTest {
            val environment = createMockEnvironment()
            val sourceUri = Uri.parse("content://example.media/items/fail.png")

            try {
                every { environment.contentResolver.getType(sourceUri) } returns "image/png"
                every {
                    environment.contentResolver.query(
                        sourceUri,
                        any(),
                        null,
                        null,
                        null,
                    )
                } answers {
                    when (secondArg<Array<String>>().toList()) {
                        listOf(MediaStore.MediaColumns.DISPLAY_NAME) -> {
                            singleRowCursor(MediaStore.MediaColumns.DISPLAY_NAME to "fail.png")
                        }

                        listOf(
                            MediaStore.MediaColumns.DATE_TAKEN,
                            MediaStore.MediaColumns.DATE_ADDED,
                        ),
                        -> {
                            singleRowCursor(
                                MediaStore.MediaColumns.DATE_TAKEN to 2_000L,
                                MediaStore.MediaColumns.DATE_ADDED to 2L,
                            )
                        }

                        else -> {
                            emptyCursor(*secondArg<Array<String>>())
                        }
                    }
                }
                every {
                    environment.contentResolver.openInputStream(sourceUri)
                } returns
                    object : java.io.InputStream() {
                        override fun read(): Int = throw IllegalStateException("boom")
                    }
                every { environment.contentResolver.insert(any(), any()) } returns Uri.parse("content://media/external/images/media/202")
                every { environment.contentResolver.openOutputStream(any(), any()) } returns java.io.ByteArrayOutputStream()
                every { environment.contentResolver.update(any(), any(), any(), any()) } returns 1
                every { environment.contentResolver.delete(any(), any(), any()) } returns 1

                assertFailsWith<IllegalStateException> {
                    environment.mediaManager.addToDefaultCollection(sourceUri.toString())
                }
                assertTrue(
                    environment.filesDir
                        .resolve("user_media")
                        .listFiles()
                        .isNullOrEmpty(),
                )
            } finally {
                environment.filesDir.deleteRecursively()
                clearAllMocks()
            }
        }

    @Test
    fun `add to default collection exports new file uri from private storage`() =
        runTest {
            val freshFile = createImageFile(context.cacheDir, "fresh")
            val duplicateFile = createImageFile(context.cacheDir, "duplicate")
            duplicateFile.setLastModified(2_222_222_222_000L)
            var freshPublishedUri: String? = null
            var duplicatePublishedUri: String? = null

            try {
                mediaManager.addToDefaultCollection(Uri.fromFile(freshFile).toString())
                val afterFresh = mediaManager.getRecentMedia().first().count { it.name == freshFile.name }
                assertEquals(1, afterFresh)

                duplicatePublishedUri =
                    mediaManager.saveMediaFromFile(
                        sourceFilePath = duplicateFile.absolutePath,
                        fileName = duplicateFile.name,
                        mimeType = "image/png",
                    )
                val beforeDuplicate = mediaManager.getRecentMedia().first().count { it.name == duplicateFile.name }
                mediaManager.addToDefaultCollection(Uri.fromFile(duplicateFile).toString())
                val afterDuplicate = mediaManager.getRecentMedia().first().count { it.name == duplicateFile.name }

                assertEquals(beforeDuplicate + 1, afterDuplicate)
                assertEquals(1, afterDuplicate)

                freshPublishedUri =
                    mediaManager
                        .getRecentMedia()
                        .first()
                        .firstOrNull { it.name == freshFile.name }
                        ?.uri
            } finally {
                freshPublishedUri?.let { uri ->
                    context.contentResolver.delete(Uri.parse(uri), null, null)
                }
                duplicatePublishedUri?.let { uri ->
                    File(requireNotNull(Uri.parse(uri).path)).delete()
                }
                freshFile.delete()
                duplicateFile.delete()
            }
        }

    @Test
    fun `add to default collection rejects unsupported content uri`() =
        runTest {
            val environment = createMockEnvironment()
            val sourceUri = Uri.parse("content://example.media/items/1")

            try {
                every { environment.contentResolver.getType(sourceUri) } returns "text/plain"
                every {
                    environment.contentResolver.query(
                        sourceUri,
                        arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                        null,
                        null,
                        null,
                    )
                } returns
                    singleRowCursor(
                        MediaStore.MediaColumns.DISPLAY_NAME to "../note.txt",
                    )

                assertFailsWith<IllegalArgumentException> {
                    environment.mediaManager.addToDefaultCollection(sourceUri.toString())
                }
                assertTrue(
                    environment.filesDir
                        .resolve("user_media")
                        .listFiles()
                        .isNullOrEmpty(),
                )
            } finally {
                environment.filesDir.deleteRecursively()
                clearAllMocks()
            }
        }

    @Test
    fun `get media rejects unsupported file type`() =
        runTest {
            val file = File(context.cacheDir, "unsupported-${Uuid.random()}.txt")
            file.writeText("not media")

            try {
                assertFailsWith<IllegalArgumentException> {
                    mediaManager.getMedia(Uri.fromFile(file).toString())
                }
            } finally {
                file.delete()
            }
        }

    @Test
    fun `get media reads image and video metadata from cursor`() =
        runTest {
            val environment = createMockEnvironment()
            val imageUri = Uri.parse("content://example.media/images/1")
            val videoUri = Uri.parse("content://example.media/videos/2")

            try {
                every { environment.contentResolver.getType(imageUri) } returns "image/png"
                every {
                    environment.contentResolver.query(
                        imageUri,
                        any(),
                        null,
                        null,
                        null,
                    )
                } returns
                    singleRowCursor(
                        MediaStore.MediaColumns.DISPLAY_NAME to "image.png",
                        MediaStore.MediaColumns.SIZE to 321,
                        MediaStore.MediaColumns.DATE_TAKEN to 10_000L,
                        MediaStore.MediaColumns.DATE_ADDED to 9L,
                    )

                every { environment.contentResolver.getType(videoUri) } returns "video/mp4"
                every {
                    environment.contentResolver.query(
                        videoUri,
                        any(),
                        null,
                        null,
                        null,
                    )
                } returns
                    singleRowCursor(
                        MediaStore.MediaColumns.DISPLAY_NAME to "video.mp4",
                        MediaStore.MediaColumns.SIZE to 654,
                        MediaStore.Video.Media.DURATION to 4_500L,
                        MediaStore.MediaColumns.DATE_TAKEN to 0L,
                        MediaStore.MediaColumns.DATE_ADDED to 11L,
                    )

                val image = environment.mediaManager.getMedia(imageUri.toString())
                assertTrue(image is MediaObject.Image)
                assertEquals("image.png", image.name)
                assertEquals(321, image.size)
                assertEquals(10_000L, image.timestamp.toEpochMilliseconds())

                val video = environment.mediaManager.getMedia(videoUri.toString())
                assertTrue(video is MediaObject.Video)
                assertEquals("video.mp4", video.name)
                assertEquals(654, video.size)
                assertEquals(4_500L, video.duration.inWholeMilliseconds)
                assertEquals(11_000L, video.timestamp.toEpochMilliseconds())
            } finally {
                environment.filesDir.deleteRecursively()
                clearAllMocks()
            }
        }

    @Test
    fun `get media throws when cursor has no rows`() =
        runTest {
            // Empty cursor means the URI itself doesn't resolve to any MediaStore row,
            // which is genuinely unrecoverable: there's nothing to materialize. Throwing
            // here is correct — distinct from the "row exists but metadata is incomplete"
            // case, which the fallback chain handles by surfacing the row anyway.
            val environment = createMockEnvironment()
            val imageUri = Uri.parse("content://example.media/images/empty")
            val videoUri = Uri.parse("content://example.media/videos/empty")

            try {
                every { environment.contentResolver.getType(imageUri) } returns "image/png"
                every {
                    environment.contentResolver.query(
                        imageUri,
                        any(),
                        null,
                        null,
                        null,
                    )
                } returns
                    emptyCursor(
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.SIZE,
                        MediaStore.MediaColumns.DATE_TAKEN,
                        MediaStore.MediaColumns.DATE_ADDED,
                    )

                every { environment.contentResolver.getType(videoUri) } returns "video/mp4"
                every {
                    environment.contentResolver.query(
                        videoUri,
                        any(),
                        null,
                        null,
                        null,
                    )
                } returns
                    emptyCursor(
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.SIZE,
                        MediaStore.Video.Media.DURATION,
                        MediaStore.MediaColumns.DATE_TAKEN,
                        MediaStore.MediaColumns.DATE_ADDED,
                    )

                assertFailsWith<IllegalStateException> {
                    environment.mediaManager.getMedia(imageUri.toString())
                }

                assertFailsWith<IllegalStateException> {
                    environment.mediaManager.getMedia(videoUri.toString())
                }
            } finally {
                environment.filesDir.deleteRecursively()
                clearAllMocks()
            }
        }

    @Test
    fun `query media by date returns sorted image and video results`() =
        runTest {
            val environment = createMockEnvironment()
            val start = Instant.fromEpochMilliseconds(0)
            val end = Instant.fromEpochMilliseconds(20_000L)
            val imageCollection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val videoCollection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

            try {
                every {
                    environment.contentResolver.query(
                        imageCollection,
                        any(),
                        any(),
                        any(),
                        any(),
                    )
                } returns
                    singleRowCursor(
                        MediaStore.Images.Media._ID to 2L,
                        MediaStore.MediaColumns.DISPLAY_NAME to "image.png",
                        MediaStore.MediaColumns.SIZE to 321,
                        MediaStore.MediaColumns.DATE_TAKEN to 15_000L,
                        MediaStore.MediaColumns.DATE_ADDED to 14L,
                    )

                every {
                    environment.contentResolver.query(
                        videoCollection,
                        any(),
                        any(),
                        any(),
                        any(),
                    )
                } returns
                    singleRowCursor(
                        MediaStore.Video.Media._ID to 1L,
                        MediaStore.MediaColumns.DISPLAY_NAME to "video.mp4",
                        MediaStore.MediaColumns.SIZE to 654,
                        MediaStore.Video.Media.DURATION to 4_500L,
                        MediaStore.MediaColumns.DATE_TAKEN to 5_000L,
                        MediaStore.MediaColumns.DATE_ADDED to 4L,
                    )

                val result = environment.mediaManager.queryMediaByDate(start, end).first()
                assertEquals(2, result.size)
                assertTrue(result[0] is MediaObject.Video)
                assertTrue(result[1] is MediaObject.Image)
                assertEquals(5_000L, result[0].timestamp.toEpochMilliseconds())
                assertEquals(15_000L, result[1].timestamp.toEpochMilliseconds())
            } finally {
                environment.filesDir.deleteRecursively()
                clearAllMocks()
            }
        }

    @Test
    fun `query media by date throws when queries fail and no media found`() =
        runTest {
            val environment = createMockEnvironment()
            val start = Instant.fromEpochMilliseconds(0)
            val end = Instant.fromEpochMilliseconds(20_000L)
            val imageCollection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val videoCollection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

            try {
                every {
                    environment.contentResolver.query(
                        imageCollection,
                        any(),
                        any(),
                        any(),
                        any(),
                    )
                } throws SecurityException("no image access")

                every {
                    environment.contentResolver.query(
                        videoCollection,
                        any(),
                        any(),
                        any(),
                        any(),
                    )
                } throws SecurityException("no video access")

                assertFailsWith<SecurityException> {
                    environment.mediaManager.queryMediaByDate(start, end).first()
                }
            } finally {
                environment.filesDir.deleteRecursively()
                clearAllMocks()
            }
        }

    @Test
    fun `get recent media throws when queries fail`() =
        runTest {
            val environment = createMockEnvironment()
            val imageCollection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val videoCollection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

            try {
                every {
                    environment.contentResolver.query(
                        imageCollection,
                        any<Array<String>>(),
                        any<Bundle>(),
                        null,
                    )
                } throws SecurityException("no permission")

                every {
                    environment.contentResolver.query(
                        videoCollection,
                        any<Array<String>>(),
                        any<Bundle>(),
                        null,
                    )
                } throws IllegalStateException("broken provider")

                assertFailsWith<SecurityException> {
                    environment.mediaManager.getRecentMedia().first()
                }
            } finally {
                environment.filesDir.deleteRecursively()
                clearAllMocks()
            }
        }

    @Test
    fun `query media by date recovers rows with missing timestamp metadata`() =
        runTest {
            // The user must never see media disappear from the gallery just because
            // MediaStore left a row's metadata blank. A row with both DATE_TAKEN and
            // DATE_ADDED set to zero must still surface, with the timestamp falling
            // back to "now" so the row stays visible.
            val environment = createMockEnvironment()
            val start = Instant.fromEpochMilliseconds(0)
            val end = Instant.fromEpochMilliseconds(20_000L)
            val imageCollection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val videoCollection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

            try {
                every {
                    environment.contentResolver.query(
                        imageCollection,
                        any(),
                        any(),
                        any(),
                        any(),
                    )
                } returns
                    singleRowCursor(
                        MediaStore.Images.Media._ID to 2L,
                        MediaStore.MediaColumns.DISPLAY_NAME to "image.png",
                        MediaStore.MediaColumns.SIZE to 321,
                        MediaStore.MediaColumns.DATE_TAKEN to 0L,
                        MediaStore.MediaColumns.DATE_ADDED to 0L,
                    )

                every {
                    environment.contentResolver.query(
                        videoCollection,
                        any(),
                        any(),
                        any(),
                        any(),
                    )
                } returns
                    emptyCursor(
                        MediaStore.Video.Media._ID,
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.SIZE,
                        MediaStore.Video.Media.DURATION,
                        MediaStore.MediaColumns.DATE_TAKEN,
                        MediaStore.MediaColumns.DATE_ADDED,
                    )

                val result = environment.mediaManager.queryMediaByDate(start, end).first()

                assertEquals(1, result.size)
                val recovered = result.single()
                assertTrue(recovered is MediaObject.Image)
                assertEquals("image.png", recovered.name)
                assertEquals(321, recovered.size)
            } finally {
                environment.filesDir.deleteRecursively()
                clearAllMocks()
            }
        }

    @Test
    fun `get recent media recovers rows with missing timestamp metadata`() =
        runTest {
            // Same contract as the date-range query: the user must never see a row
            // disappear from the recent media gallery just because MediaStore left
            // its DATE_TAKEN and DATE_ADDED columns blank.
            val environment = createMockEnvironment()
            val imageCollection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val videoCollection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

            try {
                every {
                    environment.contentResolver.query(
                        imageCollection,
                        any<Array<String>>(),
                        any<Bundle>(),
                        null,
                    )
                } returns
                    singleRowCursor(
                        MediaStore.Images.Media._ID to 2L,
                        MediaStore.MediaColumns.DISPLAY_NAME to "image.png",
                        MediaStore.MediaColumns.SIZE to 321,
                        MediaStore.MediaColumns.DATE_TAKEN to 0L,
                        MediaStore.MediaColumns.DATE_ADDED to 0L,
                    )

                every {
                    environment.contentResolver.query(
                        videoCollection,
                        any<Array<String>>(),
                        any<Bundle>(),
                        null,
                    )
                } returns
                    emptyCursor(
                        MediaStore.Video.Media._ID,
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.SIZE,
                        MediaStore.Video.Media.DURATION,
                        MediaStore.MediaColumns.DATE_TAKEN,
                        MediaStore.MediaColumns.DATE_ADDED,
                    )

                val result = environment.mediaManager.getRecentMedia().first()

                assertEquals(1, result.size)
                val recovered = result.single()
                assertTrue(recovered is MediaObject.Image)
                assertEquals("image.png", recovered.name)
                assertEquals(321, recovered.size)
            } finally {
                environment.filesDir.deleteRecursively()
                clearAllMocks()
            }
        }

    @Test
    fun `get recent media recovers videos with null duration metadata`() =
        runTest {
            // The originally reported bug: a video row whose DURATION column is null
            // (e.g. MediaStore failed to index it properly) used to throw and abort the
            // entire query, hiding every other video in the gallery. The user would
            // see videos disappear. The fallback chain must keep the video visible.
            val environment = createMockEnvironment()
            val imageCollection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val videoCollection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

            try {
                every {
                    environment.contentResolver.query(
                        imageCollection,
                        any<Array<String>>(),
                        any<Bundle>(),
                        null,
                    )
                } returns
                    emptyCursor(
                        MediaStore.Images.Media._ID,
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.SIZE,
                        MediaStore.MediaColumns.DATE_TAKEN,
                        MediaStore.MediaColumns.DATE_ADDED,
                    )

                every {
                    environment.contentResolver.query(
                        videoCollection,
                        any<Array<String>>(),
                        any<Bundle>(),
                        null,
                    )
                } returns
                    singleRowCursor(
                        MediaStore.Video.Media._ID to 1_000_025_185L,
                        MediaStore.MediaColumns.DISPLAY_NAME to "broken.mp4",
                        MediaStore.MediaColumns.SIZE to 654,
                        MediaStore.Video.Media.DURATION to null,
                        MediaStore.MediaColumns.DATE_TAKEN to 5_000L,
                        MediaStore.MediaColumns.DATE_ADDED to 4L,
                    )

                val result = environment.mediaManager.getRecentMedia().first()

                assertEquals(1, result.size)
                val recovered = result.single()
                assertTrue(recovered is MediaObject.Video)
                assertEquals("broken.mp4", recovered.name)
                assertEquals(654, recovered.size)
                assertEquals(5_000L, recovered.timestamp.toEpochMilliseconds())
                // Duration falls all the way through to ZERO so the row stays visible.
                assertEquals(Duration.ZERO, recovered.duration)
            } finally {
                environment.filesDir.deleteRecursively()
                clearAllMocks()
            }
        }

    @Test
    fun `get media recovers media with missing timestamp metadata`() =
        runTest {
            // Direct lookups by URI must follow the same "never lose user data"
            // contract as the bulk queries: a row with both DATE_TAKEN and DATE_ADDED
            // set to zero must still materialize, with the timestamp falling back to
            // "now" so the user keeps seeing their photo or video.
            val environment = createMockEnvironment()
            val imageUri = Uri.parse("content://example.media/images/missing-timestamp")
            val videoUri = Uri.parse("content://example.media/videos/missing-timestamp")

            try {
                every { environment.contentResolver.getType(imageUri) } returns "image/png"
                every {
                    environment.contentResolver.query(
                        imageUri,
                        any(),
                        null,
                        null,
                        null,
                    )
                } returns
                    singleRowCursor(
                        MediaStore.MediaColumns.DISPLAY_NAME to "image.png",
                        MediaStore.MediaColumns.SIZE to 321,
                        MediaStore.MediaColumns.DATE_TAKEN to 0L,
                        MediaStore.MediaColumns.DATE_ADDED to 0L,
                    )

                every { environment.contentResolver.getType(videoUri) } returns "video/mp4"
                every {
                    environment.contentResolver.query(
                        videoUri,
                        any(),
                        null,
                        null,
                        null,
                    )
                } returns
                    singleRowCursor(
                        MediaStore.MediaColumns.DISPLAY_NAME to "video.mp4",
                        MediaStore.MediaColumns.SIZE to 654,
                        MediaStore.Video.Media.DURATION to 4_500L,
                        MediaStore.MediaColumns.DATE_TAKEN to 0L,
                        MediaStore.MediaColumns.DATE_ADDED to 0L,
                    )

                val image = environment.mediaManager.getMedia(imageUri.toString())
                assertTrue(image is MediaObject.Image)
                assertEquals("image.png", image.name)
                assertEquals(321, image.size)

                val video = environment.mediaManager.getMedia(videoUri.toString())
                assertTrue(video is MediaObject.Video)
                assertEquals("video.mp4", video.name)
                assertEquals(654, video.size)
                assertEquals(4_500L, video.duration.inWholeMilliseconds)
            } finally {
                environment.filesDir.deleteRecursively()
                clearAllMocks()
            }
        }

    @Test
    fun `exists recognizes persisted media uris`() =
        runTest {
            val environment = createMockEnvironment()
            val sourceFile = createImageFile(environment.filesDir, "exists")
            val contentUri = Uri.parse("content://example.media/items/1")
            val rawId = "123"

            try {
                every {
                    environment.contentResolver.query(
                        any(),
                        any(),
                        null,
                        null,
                        null,
                    )
                } answers {
                    val uri = firstArg<Uri>().toString()
                    val lastPathSegment = firstArg<Uri>().lastPathSegment
                    if (uri.contains("missing") || lastPathSegment == "not-a-real-id") {
                        emptyCursor(MediaStore.MediaColumns._ID)
                    } else {
                        singleRowCursor(MediaStore.MediaColumns._ID to 1L)
                    }
                }

                assertTrue(environment.mediaManager.exists(contentUri.toString()))
                assertTrue(environment.mediaManager.exists(rawId))
                assertTrue(environment.mediaManager.exists(Uri.fromFile(sourceFile).toString()))
                assertFalse(environment.mediaManager.exists("content://example.media/missing"))
                assertFalse(environment.mediaManager.exists("not-a-real-id"))
                assertFalse(environment.mediaManager.exists("file://"))
            } finally {
                environment.filesDir.deleteRecursively()
                clearAllMocks()
            }
        }

    @Test
    fun `get media with malformed file uri throws illegal argument exception`() =
        runTest {
            assertFailsWith<IllegalArgumentException> {
                mediaManager.getMedia("file://")
            }
        }

    private fun createLegacyImageFile(): File {
        val directory =
            context.filesDir.resolve("user_media").apply {
                mkdirs()
            }
        return createImageFile(directory, "legacy")
    }

    private fun createImageFile(
        directory: File,
        prefix: String,
    ): File {
        val file = File(directory, "$prefix-${Uuid.random()}.png")
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.MAGENTA)
            FileOutputStream(file).use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
        } finally {
            bitmap.recycle()
        }
        return file
    }

    private data class MockEnvironment(
        val contentResolver: ContentResolver,
        val mediaManager: AndroidMediaManager,
        val filesDir: File,
    )

    private fun createMockEnvironment(ioDispatcher: CoroutineDispatcher = Dispatchers.Unconfined): MockEnvironment {
        val filesDir = File(context.cacheDir, "media-${Uuid.random()}").apply { mkdirs() }
        val contentResolver = mockk<ContentResolver>()
        val mockContext = mockk<Context>()

        every { mockContext.filesDir } returns filesDir

        return MockEnvironment(
            contentResolver = contentResolver,
            mediaManager = AndroidMediaManager(contentResolver, mockContext, ioDispatcher),
            filesDir = filesDir,
        )
    }

    private fun singleRowCursor(vararg values: Pair<String, Any?>): Cursor =
        MatrixCursor(values.map { it.first }.toTypedArray()).apply {
            addRow(values.map { it.second }.toTypedArray())
        }

    private fun emptyCursor(vararg columns: String): Cursor = MatrixCursor(columns)
}
