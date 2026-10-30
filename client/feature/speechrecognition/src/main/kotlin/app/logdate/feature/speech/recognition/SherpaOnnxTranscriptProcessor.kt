package app.logdate.feature.speech.recognition

import android.content.Context
import app.logdate.client.media.audio.transcription.TranscriptAccumulator
import app.logdate.client.media.audio.transcription.TranscriptionFailure
import app.logdate.client.media.audio.transcription.TranscriptionResult
import app.logdate.client.media.audio.transcription.TranscriptionSessionTerminalizer
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal class SherpaOnnxTranscriptProcessor(
    private val context: Context,
    private val recognizerProvider: SherpaOnnxRecognizerProvider,
    private val offlineRecognizerProvider: SherpaOnnxOfflineRecognizerProvider,
    private val terminalizer: TranscriptionSessionTerminalizer,
) {
    /**
     * The refinement pass. Walks the buffered VAD utterances in order, sending
     * each one through Whisper and replacing the corresponding portion of the
     * accumulator with the refined text. After every utterance, emits an
     * updated [TranscriptionResult.Success] so the UI can crossfade the change
     * in place — the user sees the transcript visibly correcting itself.
     */
    suspend fun refine(
        utterances: List<FloatArray>,
        streamingFallback: TranscriptionResult.Success,
    ) {
        try {
            withTimeout(REFINEMENT_TIMEOUT_MS) {
                // Make sure Whisper is actually loaded before we touch it
                if (!offlineRecognizerProvider.ensureInitialized()) {
                    Napier.w("Whisper not available for refinement; keeping streaming text")
                    terminalizer.complete(streamingFallback)
                    return@withTimeout
                }

                // Reset the accumulator so we can rebuild it utterance-by-utterance
                // with refined text. We do this AFTER the streaming Success was
                // emitted above, so the UI keeps showing the streaming text until
                // the first refined chunk arrives.
                val refinedAccumulator = TranscriptAccumulator()

                for (samples in utterances) {
                    currentCoroutineContext().ensureActive()

                    val result = offlineRecognizerProvider.transcribe(samples) ?: continue
                    if (result.text.isBlank()) continue

                    refinedAccumulator.addSegment(result.text)

                    terminalizer.progress(
                        TranscriptionResult.Success(
                            text = refinedAccumulator.build(),
                            timedTranscript = refinedAccumulator.buildTimedTranscript(),
                            isFinal = true,
                            isRefining = true,
                        ),
                    )
                }
                val refinedText = refinedAccumulator.build()
                terminalizer.complete(
                    if (refinedText.isBlank()) {
                        streamingFallback
                    } else {
                        TranscriptionResult.Success(
                            text = refinedText,
                            timedTranscript = refinedAccumulator.buildTimedTranscript(),
                            isFinal = true,
                            isRefining = false,
                        )
                    },
                )
            }
        } catch (e: TimeoutCancellationException) {
            Napier.e("Refinement timed out; keeping streaming text", e)
            terminalizer.complete(streamingFallback)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Napier.e("Refinement pass failed; keeping streaming text", e)
            terminalizer.complete(streamingFallback)
        }
    }

    suspend fun transcribeAudioFile(audioUri: String): TranscriptionResult =
        withContext(Dispatchers.Default) {
            val fileVad = SherpaOnnxVadProvider(context)
            try {
                fileVad.ensureInitialized()
                val hasSamples =
                    try {
                        feedFileTranscriptionAudio(AudioDecoder(context).decodeMono16kHz(audioUri), fileVad::acceptWaveform)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Napier.e("Could not decode recorded audio", error)
                        return@withContext TranscriptionResult.Error(TranscriptionFailure.AudioError)
                    }
                if (!hasSamples) return@withContext TranscriptionResult.Error(TranscriptionFailure.NoSpeechDetected)
                currentCoroutineContext().ensureActive()
                fileVad.flush()
                val utterances =
                    buildList {
                        while (!fileVad.isEmpty()) {
                            currentCoroutineContext().ensureActive()
                            add(fileVad.front().samples.copyOf())
                            fileVad.pop()
                        }
                    }
                if (utterances.isEmpty()) {
                    return@withContext TranscriptionResult.Error(TranscriptionFailure.NoSpeechDetected)
                }

                val text =
                    if (offlineRecognizerProvider.ensureInitialized()) {
                        utterances.mapNotNull {
                            currentCoroutineContext().ensureActive()
                            val result = offlineRecognizerProvider.transcribe(it)?.text?.trim()
                            currentCoroutineContext().ensureActive()
                            result
                        }
                    } else {
                        recognizerProvider.ensureInitialized()
                        utterances.mapNotNull { transcribeStreamingUtterance(it) }
                    }.filter(String::isNotBlank)
                        .joinToString(" ")

                if (text.isBlank()) {
                    TranscriptionResult.Error(TranscriptionFailure.NoSpeechDetected)
                } else {
                    TranscriptionResult.Success(text = text, isFinal = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Napier.e("On-device file transcription failed", e)
                TranscriptionResult.Error(TranscriptionFailure.Unknown)
            } finally {
                fileVad.release()
            }
        }

    private suspend fun transcribeStreamingUtterance(samples: FloatArray): String? {
        if (samples.isEmpty()) return null
        val localStream = recognizerProvider.createStream()
        return try {
            localStream.acceptWaveform(samples, SherpaOnnxRecognizerProvider.SAMPLE_RATE)
            localStream.inputFinished()
            while (recognizerProvider.isReady(localStream)) {
                currentCoroutineContext().ensureActive()
                recognizerProvider.decode(localStream)
            }
            currentCoroutineContext().ensureActive()
            recognizerProvider
                .getResult(localStream)
                .text
                .takeIf(String::isNotBlank)
                ?.let(recognizerProvider::addPunctuation)
        } finally {
            localStream.release()
        }
    }

    private companion object {
        const val REFINEMENT_TIMEOUT_MS = 5 * 60_000L
    }
}
