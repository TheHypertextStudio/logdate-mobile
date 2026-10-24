package app.logdate.client.media.audio

import app.logdate.client.media.audio.transcription.TranscriptionFailure
import app.logdate.client.media.audio.transcription.TranscriptionResult
import app.logdate.client.media.audio.transcription.TranscriptionService
import app.logdate.client.media.audio.transcription.TranscriptionStartResult
import app.logdate.client.media.audio.transcription.stopLiveTranscriptionWithHandoff
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

internal fun AndroidAudioRecordingManager.bindTranscriptionService(service: TranscriptionService) {
    // Every audio block's view model hands over the same app-scoped service. Rebinding
    // it would restart the warm-up and drop the collector mid-refinement for nothing.
    if (service === transcriptionService && transcriptionCollectorJob?.isActive == true) return
    this.transcriptionService = service

    transcriptionWarmUpJob?.cancel()
    transcriptionWarmUpJob =
        scope.launch {
            try {
                service.warmUp()
            } catch (e: Exception) {
                Napier.e("Transcription model warm-up failed", e)
            }
        }

    // This collector lives on the singleton's own scope so a Whisper refinement pass
    // that is still mid-utterance when the editor view model goes away continues to
    // flow through here and gets persisted to the database.
    transcriptionCollectorJob?.cancel()
    val noteId = sessionTargetNoteId
    val transcriptSession = recordingTranscriptSession
    val inputHealth = transcriptionInputRouter.health
    transcriptionCollectorJob =
        scope.launch {
            service.getTranscriptionFlow().collectLatest { result ->
                onTranscriptionResult(result, noteId, inputHealth, transcriptSession)
            }
        }
}

internal fun AndroidAudioRecordingManager.onTranscriptionResult(
    result: TranscriptionResult,
    noteId: Uuid?,
    inputHealth: TranscriptionInputHealth,
    transcriptSession: RecordingTranscriptSession?,
) {
    if (result is TranscriptionResult.Success && !inputHealth.isHealthy) return
    if (result is TranscriptionResult.Error && result.reason == TranscriptionFailure.AudioError) inputHealth.isHealthy = false
    if (result is TranscriptionResult.Success) scheduleTranscriptPersistence(result, noteId, transcriptSession)
    if (noteId != sessionTargetNoteId) return
    when (result) {
        is TranscriptionResult.Success -> {
            transcriptionFlow.value = result.text
            structuredTranscriptionFlow.value = result
        }

        is TranscriptionResult.Error -> {
            Napier.e("Transcription error: ${result.reason}")
            if (result.reason != TranscriptionFailure.PersistenceError) {
                liveTranscriptionStarted = false
            }
            structuredTranscriptionFlow.value = result
        }

        is TranscriptionResult.InProgress,
        TranscriptionResult.Cancelled,
        -> {
            structuredTranscriptionFlow.value = result
        }
    }
}

internal fun AndroidAudioRecordingManager.updateTranscriptionInput(
    deviceId: String?,
    verifyActiveInput: Boolean = false,
) = transcriptionInputRouter.update(transcriptionService, deviceId, verifyActiveInput)

internal suspend fun AndroidAudioRecordingManager.startLiveTranscription() {
    val service = transcriptionService
    if (service == null) {
        structuredTranscriptionFlow.value = TranscriptionResult.Error(TranscriptionFailure.NotAvailable)
        return
    }
    try {
        if (transcriptionSessionInitialized) service.cancelTranscription()
        transcriptionCollectorJob?.cancelAndJoin()
        val noteId = sessionTargetNoteId
        val transcriptSession = recordingTranscriptSession
        val inputHealth = transcriptionInputRouter.health
        val results = service.getTranscriptionFlow()
        val outgoingReplayCount = results.replayCache.size
        transcriptionCollectorJob =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                results.drop(outgoingReplayCount).collectLatest { result ->
                    onTranscriptionResult(result, noteId, inputHealth, transcriptSession)
                }
            }
        transcriptionSessionInitialized = true
        service.resetTranscription()
        if (service.supportsLiveTranscription) {
            updateTranscriptionInput(
                serviceController.serviceState.value?.routedInputDeviceId ?: audioRouteRepository.requestedInputDeviceIds.first(),
            )
            applyLiveStart(service.startLiveTranscription())
            updateTranscriptionInput(
                serviceController.serviceState.value?.routedInputDeviceId ?: audioRouteRepository.requestedInputDeviceIds.first(),
                verifyActiveInput = true,
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Napier.e("Transcription failed to start; audio recording will continue", e)
        structuredTranscriptionFlow.value = TranscriptionResult.Error(TranscriptionFailure.Unknown)
    }
}

internal fun AndroidAudioRecordingManager.applyLiveStart(start: TranscriptionStartResult) {
    when (start) {
        TranscriptionStartResult.Started,
        TranscriptionStartResult.AlreadyRunning,
        -> {
            liveTranscriptionStarted = true
        }

        is TranscriptionStartResult.Failed -> {
            structuredTranscriptionFlow.value = TranscriptionResult.Error(start.reason)
        }
    }
}

internal suspend fun AndroidAudioRecordingManager.finishTranscription(
    filePath: String?,
    noteId: Uuid?,
) {
    val service = transcriptionService ?: return
    val transcriptSession = recordingTranscriptSession
    val liveSessionWasHealthy = liveTranscriptionStarted && transcriptionInputRouter.health.isHealthy
    val stopResult =
        if (service.supportsLiveTranscription) {
            try {
                stopLiveTranscriptionWithHandoff(service) { result ->
                    if (liveSessionWasHealthy) {
                        transcriptionFlow.value = result.text
                        structuredTranscriptionFlow.value = result
                        scheduleTranscriptPersistence(result, noteId, transcriptSession)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Napier.e("Transcription failed while stopping; recovering from the recorded file", e)
                TranscriptionResult.Error(TranscriptionFailure.Unknown)
            }
        } else {
            TranscriptionResult.Error(TranscriptionFailure.NotSupported)
        }
    liveTranscriptionStarted = false

    // File transcription is the recovery path when a live session could not start or
    // terminated with an error. A healthy live session already produced the transcript.
    val needsFileRecovery = !liveSessionWasHealthy || stopResult is TranscriptionResult.Error
    if (needsFileRecovery && service.supportsFileTranscription && filePath != null) {
        scope.launch {
            val result = service.transcribeAudioFile(filePath)
            if (result is TranscriptionResult.Success) {
                scheduleTranscriptPersistence(result, noteId, transcriptSession)
            }
            if (sessionTargetNoteId == noteId) {
                if (result is TranscriptionResult.Success) transcriptionFlow.value = result.text
                structuredTranscriptionFlow.value = result
            }
        }
    }
}

/**
 * Writes a transcription result to the repository under its captured
 * session note ID, so the polished text survives the
 * editor view model and is visible to any later viewer that loads the
 * note. Skipped when no note id was supplied to [startRecording] (e.g.
 * Wear OS, tests).
 *
 * Status is COMPLETED once refinement finishes (or when refinement
 * isn't running because the Whisper model isn't on device); it's
 * IN_PROGRESS while utterances are still being rewritten so any UI
 * observing the note can show the right state.
 */
internal suspend fun AndroidAudioRecordingManager.persistRefinedTranscript(
    result: TranscriptionResult.Success,
    noteId: Uuid,
    transcriptSession: RecordingTranscriptSession,
) {
    if (!transcriptWriter.persist(result, transcriptSession)) {
        Napier.e("Transcript persistence exhausted retries for $noteId")
        structuredTranscriptionFlow.value = TranscriptionResult.Error(TranscriptionFailure.PersistenceError)
    }
}

internal fun AndroidAudioRecordingManager.scheduleTranscriptPersistence(
    result: TranscriptionResult.Success,
    noteId: Uuid? = sessionTargetNoteId,
    transcriptSession: RecordingTranscriptSession? = recordingTranscriptSession,
) {
    if ((result.text.isBlank() && !result.isFinal) || noteId == null || transcriptSession == null) return
    // The active note is saved after recording stops; completed older sessions can
    // already persist while a new recording runs.
    if (recordingStateFlow.value && noteId == sessionTargetNoteId) return
    synchronized(transcriptPersistenceLock) {
        transcriptPersistenceJobs.remove(noteId)?.cancel()
        val job =
            scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                persistRefinedTranscript(result, noteId, transcriptSession)
            }
        transcriptPersistenceJobs[noteId] = job
        job.invokeOnCompletion {
            synchronized(transcriptPersistenceLock) {
                if (transcriptPersistenceJobs[noteId] === job) transcriptPersistenceJobs.remove(noteId)
            }
        }
        job.start()
    }
}

internal suspend fun AndroidAudioRecordingManager.beginTranscriptSession(
    noteId: Uuid?,
    path: String,
): RecordingTranscriptSession? =
    noteId?.let {
        try {
            transcriptWriter.begin(it, path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Napier.e("Could not capture recording transcript ownership; file recovery remains available", e)
            null
        }
    }
