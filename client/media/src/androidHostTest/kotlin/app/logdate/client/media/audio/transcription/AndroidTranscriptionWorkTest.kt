package app.logdate.client.media.audio.transcription

import androidx.work.NetworkType
import app.logdate.client.repository.transcription.TranscriptionStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class AndroidTranscriptionWorkTest {
    @Test
    fun `final no speech is completed and is not retried`() =
        runTest {
            val statuses = mutableListOf<TranscriptionStatus>()
            val runner =
                TranscriptionWorkRunner(
                    update = { _, _, status, _ ->
                        statuses += status
                        true
                    },
                    transcribe = { TranscriptionResult.Success("", isFinal = true) },
                )
            assertEquals(TranscriptionWorkOutcome.Success, runner.run(Uuid.random(), "file:///silent.m4a"))
            assertEquals(listOf(TranscriptionStatus.IN_PROGRESS, TranscriptionStatus.COMPLETED), statuses)
        }

    @Test
    fun `on-device transcription work is offline-capable and carries the cancellation tag`() {
        val request = buildTranscriptionWorkRequest(Uuid.random(), "file:///recording.m4a")

        assertEquals(NetworkType.NOT_REQUIRED, request.workSpec.constraints.requiredNetworkType)
        assertTrue(TranscriptionWorker.TAG in request.tags)
    }

    @Test
    fun `successful recognition retries when the completed transcript cannot be persisted`() =
        runTest {
            val statuses = mutableListOf<TranscriptionStatus>()
            val runner =
                TranscriptionWorkRunner(
                    update = { _, _, status, _ ->
                        statuses += status
                        status != TranscriptionStatus.COMPLETED
                    },
                    transcribe = { TranscriptionResult.Success("words", isFinal = true) },
                )

            val outcome = runner.run(Uuid.random(), "file:///recording.m4a")

            assertEquals(TranscriptionWorkOutcome.Retry, outcome)
            assertEquals(
                listOf(TranscriptionStatus.IN_PROGRESS, TranscriptionStatus.COMPLETED, TranscriptionStatus.PENDING),
                statuses,
            )
        }

    @Test
    fun `an engine exception is persisted as failed before the worker terminates`() =
        runTest {
            val statuses = mutableListOf<TranscriptionStatus>()
            val runner =
                TranscriptionWorkRunner(
                    update = { _, _, status, _ ->
                        statuses += status
                        true
                    },
                    transcribe = { error("native crash") },
                )

            val outcome = runner.run(Uuid.random(), "file:///recording.m4a")

            assertEquals(TranscriptionWorkOutcome.Failure, outcome)
            assertEquals(
                listOf(TranscriptionStatus.IN_PROGRESS, TranscriptionStatus.FAILED),
                statuses,
            )
        }

    @Test
    fun `a database exception while entering in-progress stays retryable`() =
        runTest {
            val runner =
                TranscriptionWorkRunner(
                    update = { _, _, _, _ -> error("database unavailable") },
                    transcribe = { TranscriptionResult.Success("words", isFinal = true) },
                )

            assertEquals(
                TranscriptionWorkOutcome.Retry,
                runner.run(Uuid.random(), "file:///recording.m4a"),
            )
        }
}
