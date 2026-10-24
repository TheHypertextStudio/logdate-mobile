@file:OptIn(
    kotlinx.cinterop.BetaInteropApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package app.logdate.client.media.audio.transcription

import app.logdate.client.media.audio.download.ModelDownloadStatus
import app.logdate.client.media.storage.MediaFileResolver
import app.logdate.client.media.storage.fileUrl
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Speech.SFSpeechRecognizer
import platform.Speech.SFSpeechURLRecognitionRequest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * iOS implementation of [TranscriptionService] backed by Apple's on-device
 * Speech Recognition framework. No model download is required — the system
 * provides a high-quality on-device model for most languages out of the box.
 *
 * Live transcription requires [AVAudioEngine] buffer tapping, which conflicts
 * with the current [AVAudioRecorder]-based capture setup. File transcription
 * is the primary path: after a recording stops the saved file is sent through
 * [SFSpeechURLRecognitionRequest] with on-device recognition forced so nothing
 * leaves the device.
 *
 * Authorization: iOS shows the permission dialog on the first recognition
 * attempt. We don't pre-request it — if the user denies, the recognition task
 * returns an error which surfaces as a [TranscriptionResult.Error].
 */
internal class IosTranscriptionService(
    private val mediaFiles: MediaFileResolver,
) : TranscriptionService {
    private val _transcriptionFlow = MutableSharedFlow<TranscriptionResult>(replay = 1)
    private val unsupportedLiveResult = TranscriptionResult.Error(TranscriptionFailure.NotSupported)

    // SFSpeechRecognizer is stateful on the main thread; lazily created so it
    // initialises on the correct thread when first accessed.
    private val recognizer: SFSpeechRecognizer? by lazy { SFSpeechRecognizer() }

    override fun getTranscriptionFlow(): SharedFlow<TranscriptionResult> = _transcriptionFlow.asSharedFlow()

    override suspend fun startLiveTranscription(): TranscriptionStartResult {
        // Live buffer-based transcription requires AVAudioEngine tapping,
        // which conflicts with AVAudioRecorder. File transcription via
        // transcribeAudioFile() is the primary path on iOS.
        _transcriptionFlow.emit(unsupportedLiveResult)
        return TranscriptionStartResult.Failed(TranscriptionFailure.NotSupported)
    }

    override suspend fun stopLiveTranscription(): TranscriptionResult = unsupportedLiveResult

    override suspend fun transcribeAudioFile(audioUri: String): TranscriptionResult {
        val r = recognizer ?: return TranscriptionResult.Error(TranscriptionFailure.NotAvailable)

        // isAvailable() is an ObjC getter exposed as a function in Kotlin/Native.
        if (!r.isAvailable()) {
            Napier.w("iOS SFSpeechRecognizer not available — locale may be unsupported")
            return TranscriptionResult.Error(TranscriptionFailure.NotAvailable)
        }

        val url = mediaFiles.fileUrl(audioUri) ?: return TranscriptionResult.Error(TranscriptionFailure.AudioError)
        val request = SFSpeechURLRecognitionRequest(uRL = url)
        // Force on-device recognition — no audio leaves the device.
        request.requiresOnDeviceRecognition = true
        // We only need the final polished transcript, not partials.
        request.shouldReportPartialResults = false

        return try {
            val text = withContext(Dispatchers.Main) { recognize(r, request) }
            TranscriptionResult.Success(text = text, isFinal = true)
        } catch (e: Exception) {
            Napier.e("iOS file transcription failed for $audioUri", e)
            TranscriptionResult.Error(TranscriptionFailure.Unknown)
        }
    }

    private suspend fun recognize(
        recognizer: SFSpeechRecognizer,
        request: SFSpeechURLRecognitionRequest,
    ): String =
        suspendCancellableCoroutine { cont ->
            val task =
                recognizer.recognitionTaskWithRequest(request) { result, error ->
                    if (!cont.isActive) return@recognitionTaskWithRequest
                    when {
                        error != null -> {
                            cont.resumeWithException(Exception(error.localizedDescription))
                        }

                        // isFinal() is an ObjC getter exposed as a function in Kotlin/Native.
                        result?.isFinal() == true -> {
                            cont.resume(result.bestTranscription.formattedString)
                        }
                    }
                }
            cont.invokeOnCancellation { task.cancel() }
        }

    override suspend fun cancelTranscription() = Unit

    override fun getSupportedLanguages(): List<String> = listOf("en-US")

    override fun setLanguage(languageCode: String) = Unit

    // Live buffer transcription is not supported with the current AVAudioRecorder setup.
    override val supportsLiveTranscription: Boolean = false

    override val supportsFileTranscription: Boolean = true

    // The iOS system Speech Recognition model is always present on device —
    // there is nothing to download.
    override val isOfflineModelAvailable: Boolean = true

    override val offlineModelDownloadStatus: StateFlow<ModelDownloadStatus> =
        MutableStateFlow(ModelDownloadStatus.Completed).asStateFlow()

    override suspend fun resetTranscription() {
        _transcriptionFlow.emit(TranscriptionResult.InProgress)
    }

    override fun release() = Unit
}
