package app.logdate.client.media

import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class StreamingManagedMediaImportSourceTest {
    private val directory =
        Path(SystemTemporaryDirectory, "logdate-streaming-stage-${Uuid.random()}").also(SystemFileSystem::createDirectories)

    @AfterTest
    fun removeDirectory() {
        SystemFileSystem.list(directory).forEach { SystemFileSystem.delete(it) }
        SystemFileSystem.delete(directory)
    }

    @Test
    fun `staging streams the opened media to disk without reading it into memory`() =
        runTest {
            val bytes = ByteArray(200_000) { (it % 251).toByte() }
            val media =
                OpenOnlyMediaManager {
                    MediaFileSource("clip.mov", "video/quicktime", bytes.size.toLong()) { Buffer().apply { write(bytes) } }
                }

            val staged = StreamingManagedMediaImportSource(media, directory.toString()).stage("ph://clip")

            assertEquals("clip.mov", staged.fileName)
            assertEquals("video/quicktime", staged.mimeType)
            assertContentEquals(bytes, SystemFileSystem.source(Path(staged.sourceFilePath)).buffered().use { it.readByteArray() })
            assertEquals(0, media.readCount)
        }

    @Test
    fun `a read that fails partway leaves no staged file behind`() =
        runTest {
            val media = OpenOnlyMediaManager { MediaFileSource("photo.heic", "image/heic", 10) { FailingSource() } }

            assertFailsWith<IOException> {
                StreamingManagedMediaImportSource(media, directory.toString()).stage("ph://photo")
            }

            assertTrue(SystemFileSystem.list(directory).isEmpty())
        }

    private class OpenOnlyMediaManager(
        private val file: () -> MediaFileSource,
    ) : InMemoryMediaManager() {
        var readCount = 0

        override suspend fun readMedia(uri: String): MediaPayload {
            readCount++
            error("Staging must stream the media instead of reading it whole")
        }

        override suspend fun openMedia(uri: String): MediaFileSource = file()
    }

    private class FailingSource : RawSource {
        private var served = false

        override fun readAtMostTo(
            sink: Buffer,
            byteCount: Long,
        ): Long {
            if (served) throw IOException("Photo library stopped responding")
            served = true
            sink.write(byteArrayOf(1, 2, 3))
            return 3
        }

        override fun close() = Unit
    }
}
