package app.logdate.client.media.audio.transcription

import app.logdate.client.media.audio.FakeTranscriptionRepository
import app.logdate.client.media.storage.MediaCollection
import app.logdate.client.media.storage.MediaDirectories
import app.logdate.client.media.storage.MediaFileResolver
import app.logdate.client.repository.transcription.TranscriptDocument
import app.logdate.client.repository.transcription.TranscriptDocumentStatus
import app.logdate.client.repository.transcription.TranscriptionData
import app.logdate.client.repository.transcription.TranscriptionRepository
import app.logdate.client.repository.transcription.TranscriptionStatus
import app.logdate.client.repository.transcription.TranscriptionWorkToken
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.Uuid

class AndroidStoredTranscriptionWorkTest {
    @Test
    fun `failed recognition preserves already accepted partial words`() =
        runTest {
            val id = Uuid.random()
            val partial = TranscriptDocument.fromPlainText("Already accepted words", TranscriptDocumentStatus.LISTENING)
            var saved: TranscriptDocument? = null
            var savedStatus: TranscriptionStatus? = null
            var legacy = false
            val repository =
                object : TranscriptionRepository by FakeTranscriptionRepository() {
                    override suspend fun getTranscription(noteId: Uuid): TranscriptionData =
                        TranscriptionData(
                            noteId = noteId,
                            text = partial.plainText,
                            transcriptDocument = if (legacy) null else partial,
                            status = TranscriptionStatus.IN_PROGRESS,
                            created = Instant.fromEpochMilliseconds(0),
                            lastUpdated = Instant.fromEpochMilliseconds(0),
                            id = id,
                        )

                    override suspend fun completeTranscription(
                        work: TranscriptionWorkToken,
                        document: TranscriptDocument,
                        status: TranscriptionStatus,
                        errorMessage: String?,
                    ): Boolean {
                        saved = document
                        savedStatus = status
                        return true
                    }
                }
            for (legacyRow in listOf(false, true)) {
                legacy = legacyRow
                for (failure in listOf(TranscriptionFailure.AudioError, TranscriptionFailure.NoSpeechDetected)) {
                    val runner =
                        TranscriptionWorkRunner(
                            repository::updateTranscription,
                            { TranscriptionResult.Error(failure) },
                            repository,
                        )
                    assertEquals(TranscriptionWorkOutcome.Failure, runner.run(id, "memo.m4a"))
                    assertEquals(partial.plainText, saved?.plainText)
                    assertEquals(TranscriptDocumentStatus.FAILED, saved?.status)
                    assertEquals(TranscriptionStatus.FAILED, savedStatus)
                }
            }
        }

    @Test
    fun `owned and legacy references claim stored media but decode its local file`() =
        runTest {
            for (stored in listOf("logdate-media://recordings/walk.m4a", "file:///install/recordings/walk.m4a")) {
                val id = Uuid.random()
                var storedStatus = TranscriptionStatus.PENDING
                var saved: String? = null
                var decoded: String? = null
                val repository =
                    object : TranscriptionRepository by FakeTranscriptionRepository() {
                        override suspend fun beginTranscription(
                            noteId: Uuid,
                            audioUri: String,
                        ): TranscriptionWorkToken? {
                            if (audioUri != stored) return null
                            storedStatus = TranscriptionStatus.IN_PROGRESS
                            return TranscriptionWorkToken(noteId, audioUri, 0)
                        }

                        override suspend fun completeTranscription(
                            work: TranscriptionWorkToken,
                            document: TranscriptDocument,
                            status: TranscriptionStatus,
                            errorMessage: String?,
                        ): Boolean {
                            assertEquals(stored, work.audioUri)
                            saved = document.plainText
                            storedStatus = status
                            return true
                        }
                    }
                val service = mockk<TranscriptionService>()
                coEvery { service.transcribeAudioFile(any()) } answers {
                    decoded = firstArg()
                    TranscriptionResult.Success("Words on a walk", isFinal = true)
                }
                val media =
                    MediaFileResolver(
                        object : MediaDirectories {
                            override fun directory(collection: MediaCollection): String = "/install/recordings"
                        },
                        fileExists = { true },
                    )

                assertEquals(TranscriptionWorkOutcome.Success, runAndroidTranscriptionWork(id, stored, repository, service, media))
                assertEquals("Words on a walk", saved, "A successful worker must attach its actual recognized words")
                assertEquals("/install/recordings/walk.m4a", decoded)
                assertEquals(TranscriptionStatus.COMPLETED, storedStatus)
            }
        }
}
