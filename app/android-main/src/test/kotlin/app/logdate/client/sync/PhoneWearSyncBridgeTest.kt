package app.logdate.client.sync

import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.sync.datalayer.NoteDataMapper
import app.logdate.client.sync.datalayer.RemoteCameraCaptureResult
import app.logdate.client.sync.datalayer.RemoteCameraCaptureResultDataMapper
import app.logdate.client.sync.datalayer.WearAudioRequestPaths
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Tests the orchestration of data synchronization between the phone and Wear OS devices.
 *
 * This suite verifies that [DefaultPhoneWearSyncBridge] correctly transforms repository entities
 * into wire-compatible formats and utilizes the [PhoneWearTransport] to deliver both
 * discrete data items (like text notes) and binary streams (like audio recordings).
 */
class PhoneWearSyncBridgeTest {
    private val notesRepository = mockk<JournalNotesRepository>()
    private val noteDataMapper = NoteDataMapper()

    @Test
    fun `publishNotesToWatch sends serialized notes through transport`() =
        runTest {
            val textNote =
                JournalNote.Text(
                    uid = Uuid.random(),
                    creationTimestamp = Instant.fromEpochMilliseconds(1_710_000_000_000),
                    lastUpdated = Instant.fromEpochMilliseconds(1_710_000_000_000),
                    content = "Phone note",
                )
            val audioNote =
                JournalNote.Audio(
                    uid = Uuid.random(),
                    creationTimestamp = Instant.fromEpochMilliseconds(1_710_000_010_000),
                    lastUpdated = Instant.fromEpochMilliseconds(1_710_000_010_000),
                    mediaRef = "/storage/emulated/0/notes/audio.m4a",
                    durationMs = 4_200,
                )
            val transport = RecordingTransport()

            every { notesRepository.observeNotesStream(any()) } returns flowOf(listOf(textNote, audioNote))

            val bridge =
                DefaultPhoneWearSyncBridge(
                    notesRepository = notesRepository,
                    noteDataMapper = noteDataMapper,
                    transport = transport,
                    audioStreamOpener = UnusedAudioOpener,
                )

            bridge.publishNotesToWatch(sourceNodeId = "watch-node")

            assertEquals(2, transport.putRequests.size)
            assertEquals(NoteDataMapper.notePath(textNote.uid), transport.putRequests[0].path)
            assertEquals(textNote, noteDataMapper.fromDataMap(transport.putRequests[0].data))
            assertEquals(NoteDataMapper.notePath(audioNote.uid), transport.putRequests[1].path)
            assertEquals(audioNote, noteDataMapper.fromDataMap(transport.putRequests[1].data))
        }

    @Test
    fun `streamAudioToWatch pipes requested note bytes to transfer channel`() =
        runTest {
            val noteId = Uuid.random()
            val payload = "synced-audio".encodeToByteArray()
            val audioNote =
                JournalNote.Audio(
                    uid = noteId,
                    creationTimestamp = Instant.fromEpochMilliseconds(1_710_000_000_000),
                    lastUpdated = Instant.fromEpochMilliseconds(1_710_000_000_000),
                    mediaRef = "content://phone/audio/$noteId",
                    durationMs = 4_200,
                )
            val transport = RecordingTransport()

            coEvery { notesRepository.getNoteById(noteId) } returns audioNote

            val bridge =
                DefaultPhoneWearSyncBridge(
                    notesRepository = notesRepository,
                    noteDataMapper = noteDataMapper,
                    transport = transport,
                    audioStreamOpener =
                        object : PhoneAudioStreamOpener {
                            override fun open(mediaRef: String): InputStream? = ByteArrayInputStream(payload)
                        },
                )

            bridge.streamAudioToWatch(noteId = noteId, sourceNodeId = "watch-node")

            assertEquals(1, transport.streamRequests.size)
            val request = transport.streamRequests.single()
            assertEquals("watch-node", request.nodeId)
            assertEquals(WearAudioRequestPaths.audioTransferPath(noteId), request.channelPath)
            assertContentEquals(payload, request.payload)
        }

    @Test
    fun `streamAudioToWatch skips transport when media stream cannot be opened`() =
        runTest {
            val noteId = Uuid.random()
            val audioNote =
                JournalNote.Audio(
                    uid = noteId,
                    creationTimestamp = Instant.fromEpochMilliseconds(1_710_000_000_000),
                    lastUpdated = Instant.fromEpochMilliseconds(1_710_000_000_000),
                    mediaRef = "content://phone/audio/$noteId",
                    durationMs = 4_200,
                )
            val transport = RecordingTransport()

            coEvery { notesRepository.getNoteById(noteId) } returns audioNote

            val bridge =
                DefaultPhoneWearSyncBridge(
                    notesRepository = notesRepository,
                    noteDataMapper = noteDataMapper,
                    transport = transport,
                    audioStreamOpener =
                        object : PhoneAudioStreamOpener {
                            override fun open(mediaRef: String): InputStream? = null
                        },
                )

            bridge.streamAudioToWatch(noteId = noteId, sourceNodeId = "watch-node")

            assertTrue(transport.streamRequests.isEmpty())
        }

    @Test
    fun `publishRemoteCameraCaptureResult sends saved result through transport`() =
        runTest {
            val transport = RecordingTransport()
            val bridge =
                DefaultPhoneWearSyncBridge(
                    notesRepository = notesRepository,
                    noteDataMapper = noteDataMapper,
                    transport = transport,
                    audioStreamOpener = UnusedAudioOpener,
                )

            bridge.publishRemoteCameraCaptureResult(
                RemoteCameraCaptureResult(
                    isSaved = true,
                    message = "Remote photo saved",
                    mediaType = "photo",
                ),
            )

            val request = transport.putRequests.single()
            assertEquals(RemoteCameraCaptureResultDataMapper.PATH_CAMERA_CAPTURE_RESULT, request.path)
            assertEquals(
                RemoteCameraCaptureResult(
                    isSaved = true,
                    message = "Remote photo saved",
                    mediaType = "photo",
                ),
                RemoteCameraCaptureResultDataMapper.fromDataMap(request.data),
            )
        }

    @Test
    fun `notes go to the watch with the usual batching and camera results go at once`() =
        runTest {
            val transport = RecordingTransport()
            val phoneNote =
                JournalNote.Text(
                    uid = Uuid.random(),
                    creationTimestamp = Instant.fromEpochMilliseconds(1_710_000_000_000),
                    lastUpdated = Instant.fromEpochMilliseconds(1_710_000_000_000),
                    content = "Phone note",
                )
            every { notesRepository.observeNotesStream(any()) } returns flowOf(listOf(phoneNote))
            val bridge =
                DefaultPhoneWearSyncBridge(
                    notesRepository = notesRepository,
                    noteDataMapper = noteDataMapper,
                    transport = transport,
                    audioStreamOpener = UnusedAudioOpener,
                )

            bridge.publishNotesToWatch(sourceNodeId = "watch-node")
            bridge.publishRemoteCameraCaptureResult(
                RemoteCameraCaptureResult(isSaved = true, message = "Remote photo saved", mediaType = "photo"),
            )

            assertEquals(listOf(false, true), transport.putRequests.map { it.urgent })
        }

    private object UnusedAudioOpener : PhoneAudioStreamOpener {
        override fun open(mediaRef: String): InputStream? = null
    }

    private class RecordingTransport : PhoneWearTransport {
        val putRequests = mutableListOf<PutRequest>()
        val streamRequests = mutableListOf<StreamRequest>()

        override suspend fun putDataItem(
            path: String,
            data: Map<String, String>,
            urgent: Boolean,
        ): Boolean {
            putRequests += PutRequest(path, data, urgent)
            return true
        }

        override suspend fun streamToNode(
            nodeId: String,
            channelPath: String,
            inputStream: InputStream,
        ): Boolean {
            streamRequests += StreamRequest(nodeId, channelPath, inputStream.readBytes())
            return true
        }
    }

    private data class PutRequest(
        val path: String,
        val data: Map<String, String>,
        val urgent: Boolean,
    )

    private data class StreamRequest(
        val nodeId: String,
        val channelPath: String,
        val payload: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as StreamRequest

            if (nodeId != other.nodeId) return false
            if (channelPath != other.channelPath) return false
            if (!payload.contentEquals(other.payload)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = nodeId.hashCode()
            result = 31 * result + channelPath.hashCode()
            result = 31 * result + payload.contentHashCode()
            return result
        }
    }
}
