package app.logdate.client.media.audio

import app.logdate.client.media.audio.tagging.AudioTaggingResult
import app.logdate.client.repository.audio.AudioTag
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

internal suspend fun AndroidAudioRecordingManager.stopSessionLocked(): String? {
    if (!recordingStateFlow.value && externallyRecordedPath == null && !serviceEnded) {
        Napier.w("Attempted to stop recording while not recording")
        return null
    }
    // A stop that lands while the transcription session is still spinning up (start and
    // stop tapped in immediate succession) must wait for that setup to settle before
    // tearing it down, or the AudioRecord it is about to open would never get closed.
    try {
        transcriptionStartJob?.join()
    } catch (e: CancellationException) {
        withContext(NonCancellable) { preserveCancelledRecording() }
        throw e
    }
    transcriptionStartJob = null
    serviceStateJob?.cancel()
    serviceStateJob = null
    routeSyncJob?.cancel()
    routeSyncJob = null

    val filePath =
        try {
            finalizeServiceRecording()?.also { persistRecordingDraft(it) }
        } catch (e: Exception) {
            Napier.e("Error finalizing the recording", e)
            null
        } finally {
            serviceController.shutdown()
            serviceEnded = false
            recordingStateFlow.value = false
            recordingPausedFlow.value = false
            audioRouteRepository.updateRecordingInputDevice(null, false)
        }
    externallyRecordedPath = filePath
    if (filePath == null) recordingTarget = null

    val noteId = sessionTargetNoteId
    try {
        finishTranscription(filePath, noteId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Napier.e("Transcription failed to stop cleanly; the recording is kept", e)
    }
    if (filePath != null && noteId != null) {
        runAmbientTagging(filePath, noteId)
    }
    return filePath
}

internal suspend fun AndroidAudioRecordingManager.persistRecordingDraft(path: String) {
    if (sessionTargetNoteId == null || recordingDraftSaved) return
    val session = draftSession ?: return
    if (!finalizedFileAvailable(path)) {
        Napier.w("Recording publication is pending; retaining its draft for recovery")
        return
    }
    withContext(NonCancellable) {
        try {
            val savedDraft = session.complete(path, durationFlow.value, quietSaveTriggered)
            if (savedDraft != null) {
                recordingDraftSaved = true
                val source = CrashSafeRecording.inFlightFile(java.io.File(path))
                if (source.exists() && !source.delete()) Napier.w("Saved recording source could not be removed")
                if (quietSaveTriggered) onQuietSave(savedDraft)
            }
        } catch (e: Exception) {
            Napier.e("Recording draft could not be saved; source audio retained for recovery", e)
        }
    }
}

internal suspend fun AndroidAudioRecordingManager.preserveCancelledRecording() {
    transcriptionStartJob?.cancel()
    serviceStateJob?.cancel()
    serviceStateJob = null
    routeSyncJob?.cancel()
    routeSyncJob = null
    try {
        externallyRecordedPath = finalizeServiceRecording()
    } catch (e: Exception) {
        Napier.e("Error preserving a cancelled recording", e)
    } finally {
        serviceController.shutdown()
        recordingStateFlow.value = false
        recordingPausedFlow.value = false
        audioRouteRepository.updateRecordingInputDevice(null, false)
        if (externallyRecordedPath == null) recordingTarget = null
    }
    transcriptionStartJob?.join()
    transcriptionStartJob = null
    try {
        if (transcriptionService?.supportsLiveTranscription == true) {
            transcriptionService?.stopLiveTranscription()
        }
    } catch (e: Exception) {
        Napier.e("Error releasing transcription after a cancelled stop", e)
    }
    liveTranscriptionStarted = false
}

/**
 * Asks the bound service to finalize the file. When the service already ended the
 * session on its own, the path it reported then is used instead.
 */
internal fun AndroidAudioRecordingManager.finalizeServiceRecording(): String? {
    val stopped = serviceController.stopRecordingNow()
    if (stopped != null) return stopped
    val snapshot = serviceController.serviceState.value
    val reported = snapshot?.recordedFilePath ?: externallyRecordedPath
    if (reported == null) {
        Napier.e("Recording produced no file: ${snapshot?.failureMessage ?: "service not bound"}")
    }
    return reported
}

/**
 * Streams the recorded file through the on-device ambient sound tagger
 * and persists each cumulative result to the audio tag repository under
 * [noteId]. The tagger emits progressively as windows complete, so the
 * note's tag set in the database fills in over the seconds after the
 * user stops recording. Ambient tagging remains outside the critical path,
 * but unavailable and failed results are logged for diagnosis.
 */
internal fun AndroidAudioRecordingManager.runAmbientTagging(
    audioPath: String,
    noteId: Uuid,
) {
    scope.launch {
        try {
            audioTaggingService.tagAudio(audioPath).collect { result ->
                when (result) {
                    is AudioTaggingResult.Success -> {
                        val domainTags =
                            result.sounds.map { sound ->
                                AudioTag(
                                    noteId = noteId,
                                    soundName = sound.name,
                                    confidence = sound.confidence,
                                    startMs = sound.startMs,
                                    durationMs = sound.durationMs,
                                )
                            }
                        audioTagRepository.replaceTagsForNote(noteId, domainTags)
                    }

                    is AudioTaggingResult.Error -> {
                        Napier.w("Audio tagging failed for $noteId: ${result.message}")
                    }

                    AudioTaggingResult.Unavailable -> {
                        Napier.d("Audio tagging unavailable for $noteId because its optional model is not installed")
                    }
                }
            }
        } catch (e: Exception) {
            Napier.e("Audio tagging coroutine failed for $noteId", e)
        }
    }
}
