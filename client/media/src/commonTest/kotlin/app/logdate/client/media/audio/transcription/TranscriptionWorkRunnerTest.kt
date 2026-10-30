package app.logdate.client.media.audio.transcription

import app.logdate.client.repository.transcription.TranscriptionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

class TranscriptionWorkRunnerTest {
    @Test
    fun `exhausted persistence failure becomes failed rather than retrying forever`() =
        runTest {
            val statuses = mutableListOf<TranscriptionStatus>()
            val runner =
                TranscriptionWorkRunner(
                    update = { _, _, status, _ ->
                        statuses += status
                        status != TranscriptionStatus.COMPLETED
                    },
                    transcribe = { TranscriptionResult.Success("Recognized words", isFinal = true) },
                    retryAllowed = false,
                )
            assertEquals(TranscriptionWorkOutcome.Failure, runner.run(Uuid.random(), "memo.m4a"))
            assertEquals(
                listOf(TranscriptionStatus.IN_PROGRESS, TranscriptionStatus.COMPLETED, TranscriptionStatus.FAILED),
                statuses,
            )
        }

    @Test
    fun `recognition waiting for persistence relinquishes active status`() =
        runTest {
            val statuses = mutableListOf<TranscriptionStatus>()
            val runner =
                TranscriptionWorkRunner(
                    update = { _, _, status, _ ->
                        statuses += status
                        status != TranscriptionStatus.COMPLETED
                    },
                    transcribe = { TranscriptionResult.Success("Recognized words", isFinal = true) },
                )
            assertEquals(TranscriptionWorkOutcome.Retry, runner.run(Uuid.random(), "memo.m4a"))
            assertEquals(
                listOf(TranscriptionStatus.IN_PROGRESS, TranscriptionStatus.COMPLETED, TranscriptionStatus.PENDING),
                statuses,
            )
        }

    @Test
    fun `exhausted interrupted recognition becomes a recoverable failure`() =
        runTest {
            val statuses = mutableListOf<TranscriptionStatus>()
            val runner =
                TranscriptionWorkRunner(
                    update = { _, _, status, _ ->
                        statuses += status
                        true
                    },
                    transcribe = { TranscriptionResult.Cancelled },
                    retryAllowed = false,
                )
            assertEquals(TranscriptionWorkOutcome.Failure, runner.run(Uuid.random(), "memo.m4a"))
            assertEquals(listOf(TranscriptionStatus.IN_PROGRESS, TranscriptionStatus.FAILED), statuses)
        }

    @Test
    fun `an engine that never finishes reaches a persisted failure`() =
        runTest {
            val statuses = mutableListOf<TranscriptionStatus>()
            val runner =
                TranscriptionWorkRunner(
                    update = { _, _, status, _ ->
                        statuses += status
                        true
                    },
                    transcribe = {
                        delay(10_000)
                        TranscriptionResult.Success("Late words", isFinal = true)
                    },
                    timeoutMillis = 100,
                )
            assertEquals(TranscriptionWorkOutcome.Failure, runner.run(Uuid.random(), "memo.m4a"))
            assertEquals(listOf(TranscriptionStatus.IN_PROGRESS, TranscriptionStatus.FAILED), statuses)
        }

    @Test
    fun `a nonterminal file result stays queued instead of completing partial text`() =
        runTest {
            val statuses = mutableListOf<TranscriptionStatus>()
            val runner =
                TranscriptionWorkRunner(
                    update = { _, _, status, _ ->
                        statuses += status
                        true
                    },
                    transcribe = { TranscriptionResult.Success("Partial words", isFinal = false) },
                )
            assertEquals(TranscriptionWorkOutcome.Retry, runner.run(Uuid.random(), "memo.m4a"))
            assertEquals(listOf(TranscriptionStatus.IN_PROGRESS, TranscriptionStatus.PENDING), statuses)
        }

    @Test
    fun `an interrupted engine returns durable work to queued before retry`() =
        runTest {
            for (result in listOf(TranscriptionResult.Cancelled, TranscriptionResult.InProgress)) {
                val statuses = mutableListOf<TranscriptionStatus>()
                val runner =
                    TranscriptionWorkRunner(
                        update = { _, _, status, _ ->
                            statuses += status
                            true
                        },
                        transcribe = { result },
                    )
                assertEquals(TranscriptionWorkOutcome.Retry, runner.run(Uuid.random(), "memo.m4a"))
                assertEquals(listOf(TranscriptionStatus.IN_PROGRESS, TranscriptionStatus.PENDING), statuses)
            }
        }

    @Test
    fun `worker cancellation propagates after relinquishing active state`() =
        runTest {
            val statuses = mutableListOf<TranscriptionStatus>()
            val runner =
                TranscriptionWorkRunner(
                    update = { _, _, status, _ ->
                        statuses += status
                        true
                    },
                    transcribe = { throw CancellationException("WorkManager stopped") },
                )
            assertFailsWith<CancellationException> { runner.run(Uuid.random(), "memo.m4a") }
            assertEquals(listOf(TranscriptionStatus.IN_PROGRESS, TranscriptionStatus.PENDING), statuses)
        }
}
