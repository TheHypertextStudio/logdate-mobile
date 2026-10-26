package app.logdate.client.sync

import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Tests [FileWatchAudioStore], the on-disk home for audio and metadata received from the watch.
 */
class FileWatchAudioStoreTest {
    private val root = Files.createTempDirectory("watch-audio-store").toFile()
    private val audioDirectory = File(root, "audio_notes")
    private val incomingDirectory = File(root, "watch_incoming")
    private val store = FileWatchAudioStore(audioDirectory, incomingDirectory)
    private val noteId = Uuid.random()

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun `audio path is a deterministic file inside the audio directory`() {
        val path = File(store.audioPath(noteId))

        assertEquals(audioDirectory.canonicalFile, path.parentFile?.canonicalFile)
        assertEquals("wear_$noteId.m4a", path.name)
    }

    @Test
    fun `written audio is readable at the audio path`() =
        runTest {
            val written = store.writeAudio(noteId, ByteArrayInputStream(byteArrayOf(1, 2, 3)))

            assertTrue(written)
            assertTrue(store.hasAudio(noteId))
            assertContentEquals(byteArrayOf(1, 2, 3), File(store.audioPath(noteId)).readBytes())
        }

    @Test
    fun `a versioned transfer acknowledges only the expected complete bytes`() =
        runTest {
            val expected = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
            assertFalse(store.matchesAudioDigest(noteId, expected))
            store.writeAudio(noteId, ByteArrayInputStream("abc".encodeToByteArray()))

            assertTrue(FileWatchAudioStore(audioDirectory, incomingDirectory).matchesAudioDigest(noteId, expected))
            assertFalse(store.matchesAudioDigest(noteId, "different"))
        }

    @Test
    fun `a transfer that fails part way leaves no file behind`() =
        runTest {
            val written = store.writeAudio(noteId, FailingStream(byteArrayOf(1, 2, 3)))

            assertFalse(written)
            assertFalse(store.hasAudio(noteId))
            assertTrue(audioDirectory.listFiles().orEmpty().isEmpty())
        }

    @Test
    fun `a failed transfer keeps the earlier complete file`() =
        runTest {
            store.writeAudio(noteId, ByteArrayInputStream(byteArrayOf(1, 2, 3)))

            val written = store.writeAudio(noteId, FailingStream(byteArrayOf(9, 9)))

            assertFalse(written)
            assertContentEquals(byteArrayOf(1, 2, 3), File(store.audioPath(noteId)).readBytes())
        }

    @Test
    fun `an empty transfer is not stored`() =
        runTest {
            val written = store.writeAudio(noteId, ByteArrayInputStream(ByteArray(0)))

            assertFalse(written)
            assertFalse(store.hasAudio(noteId))
        }

    @Test
    fun `stashed metadata round trips`() =
        runTest {
            val data = mapOf("uid" to noteId.toString(), "jsonPayload" to """{"a":"b"}""")

            store.stashMetadata(noteId, data)

            assertEquals(data, store.stashedMetadata(noteId))
        }

    @Test
    fun `missing metadata reads as null`() =
        runTest {
            assertNull(store.stashedMetadata(noteId))
        }

    @Test
    fun `corrupt metadata reads as null`() =
        runTest {
            incomingDirectory.mkdirs()
            File(incomingDirectory, "$noteId.json").writeText("not json")

            assertNull(store.stashedMetadata(noteId))
        }

    @Test
    fun `clearing metadata keeps the audio file`() =
        runTest {
            store.writeAudio(noteId, ByteArrayInputStream(byteArrayOf(1)))
            store.stashMetadata(noteId, mapOf("uid" to noteId.toString()))

            store.clearMetadata(noteId)

            assertNull(store.stashedMetadata(noteId))
            assertTrue(store.hasAudio(noteId))
        }

    @Test
    fun `discard removes the audio file and the metadata`() =
        runTest {
            store.writeAudio(noteId, ByteArrayInputStream(byteArrayOf(1)))
            store.stashMetadata(noteId, mapOf("uid" to noteId.toString()))

            store.discard(noteId)

            assertFalse(store.hasAudio(noteId))
            assertNull(store.stashedMetadata(noteId))
        }

    @Test
    fun `a note is not deleted until it is marked`() =
        runTest {
            assertFalse(store.isDeleted(noteId))
        }

    @Test
    fun `a marked deletion is remembered`() =
        runTest {
            store.markDeleted(noteId)

            assertTrue(store.isDeleted(noteId))
        }

    @Test
    fun `a deletion survives a new store over the same directories`() =
        runTest {
            store.markDeleted(noteId)

            assertTrue(FileWatchAudioStore(audioDirectory, incomingDirectory).isDeleted(noteId))
        }

    @Test
    fun `discarding a note keeps its deletion marker`() =
        runTest {
            store.markDeleted(noteId)

            store.discard(noteId)

            assertTrue(store.isDeleted(noteId))
        }

    @Test
    fun `a deletion marker for one note does not mark another`() =
        runTest {
            store.markDeleted(noteId)

            assertFalse(store.isDeleted(Uuid.random()))
        }

    @Test
    fun `a new transfer clears staging files an earlier transfer abandoned`() =
        runTest {
            val abandoned = stagingFile(Uuid.random(), ageMillis = TWO_HOURS)

            store.writeAudio(noteId, ByteArrayInputStream(byteArrayOf(1)))

            assertFalse(abandoned.exists())
        }

    @Test
    fun `a new transfer leaves a staging file that is still being written`() =
        runTest {
            val inProgress = stagingFile(Uuid.random(), ageMillis = FIVE_MINUTES)

            store.writeAudio(noteId, ByteArrayInputStream(byteArrayOf(1)))

            assertTrue(inProgress.exists())
        }

    @Test
    fun `clearing staging files never touches stored audio`() =
        runTest {
            val other = Uuid.random()
            store.writeAudio(other, ByteArrayInputStream(byteArrayOf(7)))
            File(store.audioPath(other)).setLastModified(System.currentTimeMillis() - TWO_HOURS)

            store.writeAudio(noteId, ByteArrayInputStream(byteArrayOf(1)))

            assertTrue(store.hasAudio(other))
        }

    @Test
    fun `stashing metadata clears metadata whose audio never arrived`() =
        runTest {
            val stale = Uuid.random()
            store.stashMetadata(stale, mapOf("uid" to stale.toString()))
            metadataFile(stale).setLastModified(System.currentTimeMillis() - EIGHT_DAYS)

            store.stashMetadata(noteId, mapOf("uid" to noteId.toString()))

            assertNull(store.stashedMetadata(stale))
            assertEquals(mapOf("uid" to noteId.toString()), store.stashedMetadata(noteId))
        }

    @Test
    fun `stashing metadata leaves recent metadata for other notes`() =
        runTest {
            val recent = Uuid.random()
            store.stashMetadata(recent, mapOf("uid" to recent.toString()))
            metadataFile(recent).setLastModified(System.currentTimeMillis() - ONE_DAY)

            store.stashMetadata(noteId, mapOf("uid" to noteId.toString()))

            assertEquals(mapOf("uid" to recent.toString()), store.stashedMetadata(recent))
        }

    @Test
    fun `clearing stale metadata keeps old deletion markers`() =
        runTest {
            val deleted = Uuid.random()
            store.markDeleted(deleted)
            File(incomingDirectory, "$deleted.deleted").setLastModified(System.currentTimeMillis() - EIGHT_DAYS)

            store.stashMetadata(noteId, mapOf("uid" to noteId.toString()))

            assertTrue(store.isDeleted(deleted))
        }

    private fun stagingFile(
        id: Uuid,
        ageMillis: Long,
    ): File {
        audioDirectory.mkdirs()
        return File(audioDirectory, "wear_$id.m4a.part").apply {
            writeBytes(byteArrayOf(1, 2))
            setLastModified(System.currentTimeMillis() - ageMillis)
        }
    }

    private fun metadataFile(id: Uuid): File = File(incomingDirectory, "$id.json")

    private companion object {
        const val FIVE_MINUTES = 5 * 60 * 1000L
        const val TWO_HOURS = 2 * 60 * 60 * 1000L
        const val ONE_DAY = 24 * 60 * 60 * 1000L
        const val EIGHT_DAYS = 8 * ONE_DAY
    }

    private class FailingStream(
        private val prefix: ByteArray,
    ) : InputStream() {
        private var position = 0

        override fun read(): Int {
            if (position < prefix.size) return prefix[position++].toInt()
            throw IOException("connection lost")
        }
    }
}
