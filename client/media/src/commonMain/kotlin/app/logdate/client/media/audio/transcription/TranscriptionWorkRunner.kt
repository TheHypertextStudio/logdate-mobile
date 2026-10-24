package app.logdate.client.media.audio.transcription

import app.logdate.client.repository.transcription.TranscriptDocument
import app.logdate.client.repository.transcription.TranscriptDocumentStatus
import app.logdate.client.repository.transcription.TranscriptSource
import app.logdate.client.repository.transcription.TranscriptionRepository
import app.logdate.client.repository.transcription.TranscriptionStatus
import app.logdate.client.repository.transcription.TranscriptionWorkToken
import kotlinx.coroutines.CancellationException
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
) {
    suspend fun run(
        noteId: Uuid,
        audioUri: String,
    ): TranscriptionWorkOutcome {
        var work: TranscriptionWorkToken? = null
        return try {
            work = repository?.beginTranscription(noteId, audioUri)
            if (repository != null && work == null) return TranscriptionWorkOutcome.Success
            if (repository == null && !update(noteId, null, TranscriptionStatus.IN_PROGRESS, null)) return TranscriptionWorkOutcome.Retry
            when (val result = transcribe(audioUri)) {
                is TranscriptionResult.Success -> {
                    val document =
                        result.timedTranscript?.toTranscriptDocument(TranscriptDocumentStatus.FINAL, TranscriptSource.LOCAL_REFINEMENT)
                            ?: TranscriptDocument.fromPlainText(result.text, source = TranscriptSource.LOCAL_REFINEMENT)
                    finish(noteId, work, document, TranscriptionStatus.COMPLETED)
                }

                is TranscriptionResult.Error -> {
                    if (result.reason == TranscriptionFailure.NoSpeechDetected) {
                        finish(noteId, work, TranscriptDocument(status = TranscriptDocumentStatus.FINAL), TranscriptionStatus.COMPLETED)
                    } else {
                        finish(
                            noteId,
                            work,
                            TranscriptDocument(status = TranscriptDocumentStatus.FAILED),
                            TranscriptionStatus.FAILED,
                            result.reason,
                        )
                    }
                }

                is TranscriptionResult.InProgress, TranscriptionResult.Cancelled -> {
                    TranscriptionWorkOutcome.Retry
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (repository != null && work == null) return TranscriptionWorkOutcome.Retry
            runCatching {
                finish(
                    noteId,
                    work,
                    TranscriptDocument(status = TranscriptDocumentStatus.FAILED),
                    TranscriptionStatus.FAILED,
                    TranscriptionFailure.Unknown,
                )
            }.getOrDefault(TranscriptionWorkOutcome.Retry)
        }
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
                update(noteId, document.plainText.takeIf { status == TranscriptionStatus.COMPLETED }, status, failure?.toString())
            }
        return when {
            !persisted -> TranscriptionWorkOutcome.Retry
            status == TranscriptionStatus.FAILED -> TranscriptionWorkOutcome.Failure
            else -> TranscriptionWorkOutcome.Success
        }
    }
}
