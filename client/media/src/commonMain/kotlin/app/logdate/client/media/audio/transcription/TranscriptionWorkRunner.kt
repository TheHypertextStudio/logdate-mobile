package app.logdate.client.media.audio.transcription

import app.logdate.client.repository.transcription.TranscriptDocument
import app.logdate.client.repository.transcription.TranscriptDocumentStatus
import app.logdate.client.repository.transcription.TranscriptSource
import app.logdate.client.repository.transcription.TranscriptionRepository
import app.logdate.client.repository.transcription.TranscriptionStatus
import app.logdate.client.repository.transcription.TranscriptionWorkToken
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.uuid.Uuid

internal enum class TranscriptionWorkOutcome {
    Success,
    Retry,
    Failure,
}

internal class TranscriptionWorkRunner(
    private val update: suspend (Uuid, String?, TranscriptionStatus, String?) -> Boolean,
    private val transcribe: suspend (String) -> TranscriptionResult,
    private val repository: TranscriptionRepository? = null,
    private val timeoutMillis: Long = 5 * 60_000L,
    private val retryAllowed: Boolean = true,
) {
    suspend fun run(
        noteId: Uuid,
        audioUri: String,
    ): TranscriptionWorkOutcome {
        var work: TranscriptionWorkToken? = null
        return try {
            work = repository?.beginTranscription(noteId, audioUri)
            if (repository != null && work == null) return TranscriptionWorkOutcome.Success
            if (repository == null && !update(noteId, null, TranscriptionStatus.IN_PROGRESS, null)) return retryOutcome()
            when (val result = withTimeout(timeoutMillis) { transcribe(audioUri) }) {
                is TranscriptionResult.Success -> {
                    val final = result.isFinal && !result.isRefining
                    val document =
                        result.timedTranscript?.toTranscriptDocument(
                            if (final) TranscriptDocumentStatus.FINAL else TranscriptDocumentStatus.LISTENING,
                            TranscriptSource.LOCAL_REFINEMENT,
                        ) ?: TranscriptDocument.fromPlainText(
                            result.text,
                            if (final) TranscriptDocumentStatus.FINAL else TranscriptDocumentStatus.LISTENING,
                            TranscriptSource.LOCAL_REFINEMENT,
                        )
                    if (final) finish(noteId, work, document, TranscriptionStatus.COMPLETED) else retry(noteId, work, document)
                }

                is TranscriptionResult.Error -> handleFailure(noteId, work, result.reason)

                is TranscriptionResult.InProgress, TranscriptionResult.Cancelled -> retry(noteId, work)
            }
        } catch (_: TimeoutCancellationException) {
            runCatching {
                finish(
                    noteId,
                    work,
                    retainedDocument(noteId, TranscriptDocumentStatus.FAILED),
                    TranscriptionStatus.FAILED,
                    TranscriptionFailure.AudioError,
                )
            }.getOrDefault(retryOutcome())
        } catch (e: CancellationException) {
            // WorkManager can stop a worker when constraints change. Its durable row must
            // relinquish active status, while the same token still rejects obsolete work.
            withContext(NonCancellable) { runCatching { retry(noteId, work) } }
            throw e
        } catch (_: Exception) {
            if (repository != null && work == null) return retryOutcome()
            runCatching {
                finish(
                    noteId,
                    work,
                    retainedDocument(noteId, TranscriptDocumentStatus.FAILED),
                    TranscriptionStatus.FAILED,
                    TranscriptionFailure.Unknown,
                )
            }.getOrDefault(retryOutcome())
        }
    }

    private suspend fun handleFailure(
        noteId: Uuid,
        work: TranscriptionWorkToken?,
        reason: TranscriptionFailure,
    ): TranscriptionWorkOutcome {
        val retained = retainedDocument(noteId, TranscriptDocumentStatus.FAILED)
        return if (reason == TranscriptionFailure.NoSpeechDetected && retained.segments.isEmpty()) {
            finish(noteId, work, retained.copy(status = TranscriptDocumentStatus.FINAL), TranscriptionStatus.COMPLETED)
        } else {
            finish(noteId, work, retained, TranscriptionStatus.FAILED, reason)
        }
    }

    private suspend fun retainedDocument(
        noteId: Uuid,
        status: TranscriptDocumentStatus,
    ): TranscriptDocument {
        val retained = repository?.getTranscription(noteId)
        return (retained?.transcriptDocument ?: TranscriptDocument.fromPlainText(retained?.text.orEmpty(), status)).copy(status = status)
    }

    private suspend fun retry(
        noteId: Uuid,
        work: TranscriptionWorkToken?,
        document: TranscriptDocument? = null,
    ): TranscriptionWorkOutcome {
        if (repository != null && work == null) return retryOutcome()
        if (document == null) return relinquish(noteId, work, TranscriptionFailure.AudioError)
        val outcome =
            finish(
                noteId,
                work,
                document.copy(status = if (retryAllowed) TranscriptDocumentStatus.LISTENING else TranscriptDocumentStatus.FAILED),
                if (retryAllowed) TranscriptionStatus.PENDING else TranscriptionStatus.FAILED,
                if (retryAllowed) null else TranscriptionFailure.AudioError,
            )
        return if (outcome == TranscriptionWorkOutcome.Success) TranscriptionWorkOutcome.Retry else outcome
    }

    private suspend fun finish(
        noteId: Uuid,
        work: TranscriptionWorkToken?,
        document: TranscriptDocument,
        status: TranscriptionStatus,
        failure: TranscriptionFailure? = null,
    ): TranscriptionWorkOutcome {
        val persisted =
            if (work != null &&
                repository != null
            ) {
                repository.completeTranscription(work, document, status, failure?.toString())
            } else {
                update(noteId, document.plainText.takeIf(String::isNotBlank), status, failure?.toString())
            }
        return when {
            !persisted -> relinquish(noteId, work, TranscriptionFailure.PersistenceError)
            status == TranscriptionStatus.FAILED -> TranscriptionWorkOutcome.Failure
            else -> TranscriptionWorkOutcome.Success
        }
    }

    private fun retryOutcome(): TranscriptionWorkOutcome =
        if (retryAllowed) TranscriptionWorkOutcome.Retry else TranscriptionWorkOutcome.Failure

    private suspend fun relinquish(
        noteId: Uuid,
        work: TranscriptionWorkToken?,
        reason: TranscriptionFailure,
    ): TranscriptionWorkOutcome {
        val changed =
            if (work != null && repository != null) {
                if (retryAllowed) repository.deferTranscription(work) else repository.failTranscription(work, reason.toString())
            } else {
                update(
                    noteId,
                    null,
                    if (retryAllowed) TranscriptionStatus.PENDING else TranscriptionStatus.FAILED,
                    if (retryAllowed) null else reason.toString(),
                )
            }
        if (!changed) Napier.e("Could not persist transcription work state; original audio remains available for recovery")
        return retryOutcome()
    }
}
