package app.logdate.client.media.audio.sherpa

import app.logdate.client.media.audio.download.ModelDownloadController
import app.logdate.client.media.audio.download.ModelDownloadStatus
import app.logdate.client.media.audio.tagging.AudioTaggingResult
import app.logdate.client.media.audio.tagging.AudioTaggingService
import app.logdate.client.media.audio.tagging.DetectedSound
import com.k2fsa.sherpa.onnx.AudioEvent
import com.k2fsa.sherpa.onnx.AudioTagging
import com.k2fsa.sherpa.onnx.AudioTaggingConfig
import com.k2fsa.sherpa.onnx.AudioTaggingModelConfig
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * Desktop counterpart of `SherpaOnnxAudioTaggingService`. Tags ambient
 * sounds in a recorded WAV file using Sherpa-ONNX CED via the JVM bindings.
 *
 * Same five-second cumulative window scan as the Android implementation,
 * filtering out the Speech class so the chips reflect environmental sound
 * (birds, rain, traffic, music) rather than the speech the transcription
 * service already covers.
 */
internal class DesktopSherpaAudioTaggingService(
    private val modelManager: DesktopSherpaModelManager = DesktopSherpaModelManager(),
    private val wavDecoder: DesktopWavDecoder = DesktopWavDecoder(),
) : AudioTaggingService {
    private var tagging: AudioTagging? = null
    private val initMutex = Mutex()

    override val isAvailable: Boolean
        get() = modelManager.isAudioTaggingModelReady()

    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val modelDownload =
        ModelDownloadController(
            request = modelManager.taggingDownloadRequest(),
            scope = downloadScope,
            isReady = { modelManager.checkAudioTaggingModelReady() },
            download = { modelManager.downloadAudioTaggingModel() },
        )

    override val modelDownloadStatus: StateFlow<ModelDownloadStatus> = modelDownload.status

    override fun checkModelAvailability() = modelDownload.checkAvailability()

    override fun startModelDownload() = modelDownload.startDownload()

    override suspend fun warmUp(): Boolean = ensureInitialized()

    private suspend fun ensureInitialized(): Boolean =
        initMutex.withLock {
            if (tagging != null) return@withLock true
            val modelPath = modelManager.getAudioTaggingModelPath() ?: return@withLock false

            Napier.d("Desktop CED audio tagging model path: $modelPath")
            val config =
                AudioTaggingConfig
                    .builder()
                    .setModel(
                        AudioTaggingModelConfig
                            .builder()
                            .setCED("$modelPath/${DesktopSherpaModelManager.TAGGING_MODEL_FILE_NAME}")
                            .setNumThreads(1)
                            .setProvider("cpu")
                            .build(),
                    ).setLabels("$modelPath/${DesktopSherpaModelManager.TAGGING_LABELS_FILE_NAME}")
                    .setTopK(TOP_K)
                    .build()

            tagging =
                withContext(Dispatchers.IO) {
                    AudioTagging(config)
                }
            Napier.d("Desktop Sherpa-ONNX audio tagging loaded")
            true
        }

    override fun tagAudio(audioUri: String): Flow<AudioTaggingResult> =
        flow {
            if (!ensureInitialized()) {
                emit(AudioTaggingResult.Unavailable)
                return@flow
            }
            val tagger =
                tagging ?: run {
                    emit(AudioTaggingResult.Unavailable)
                    return@flow
                }

            val samples = wavDecoder.decodeToMono16kHz(audioUri)
            if (samples == null || samples.isEmpty()) {
                emit(AudioTaggingResult.Error("Could not decode audio at $audioUri"))
                return@flow
            }

            try {
                val cumulative = mutableMapOf<String, DetectedSound>()
                val windowSamples = WINDOW_SECONDS * SAMPLE_RATE
                val strideSamples = (WINDOW_SECONDS - WINDOW_OVERLAP_SECONDS) * SAMPLE_RATE
                var startSample = 0

                while (startSample < samples.size) {
                    if (!currentCoroutineContext().isActive) return@flow
                    val endSample = min(startSample + windowSamples, samples.size)
                    val windowSize = endSample - startSample
                    if (windowSize < MIN_WINDOW_SAMPLES) break

                    val window = samples.copyOfRange(startSample, endSample)
                    val events = tagWindow(tagger, window)
                    val windowStartMs = (startSample.toLong() * 1000) / SAMPLE_RATE
                    val windowDurationMs = (windowSize.toLong() * 1000) / SAMPLE_RATE

                    for (event in events) {
                        if (event.prob < MIN_CONFIDENCE) continue
                        if (event.name.equals("Speech", ignoreCase = true)) continue
                        mergeDetection(cumulative, event.name, event.prob, windowStartMs, windowDurationMs)
                    }

                    val isFinalWindow = endSample == samples.size
                    emit(
                        AudioTaggingResult.Success(
                            sounds = cumulative.values.sortedByDescending { it.confidence },
                            isFinal = isFinalWindow,
                        ),
                    )

                    if (isFinalWindow) break
                    startSample += strideSamples
                }
            } catch (e: Exception) {
                Napier.e("Desktop audio tagging failed for $audioUri", e)
                emit(AudioTaggingResult.Error("Audio tagging failed", e))
            }
        }.flowOn(Dispatchers.Default)

    private fun tagWindow(
        tagger: AudioTagging,
        window: FloatArray,
    ): Array<AudioEvent> {
        val stream = tagger.createStream()
        try {
            stream.acceptWaveform(window, SAMPLE_RATE)
            return tagger.compute(stream, TOP_K)
        } finally {
            stream.release()
        }
    }

    private fun mergeDetection(
        cumulative: MutableMap<String, DetectedSound>,
        name: String,
        confidence: Float,
        windowStartMs: Long,
        windowDurationMs: Long,
    ) {
        val existing = cumulative[name]
        if (existing == null) {
            cumulative[name] =
                DetectedSound(
                    name = name,
                    confidence = confidence,
                    startMs = windowStartMs,
                    durationMs = windowDurationMs,
                )
        } else {
            val newEnd = windowStartMs + windowDurationMs
            val mergedEnd = maxOf(existing.startMs + existing.durationMs, newEnd)
            cumulative[name] =
                existing.copy(
                    confidence = maxOf(existing.confidence, confidence),
                    durationMs = mergedEnd - existing.startMs,
                )
        }
    }

    override fun release() {
        try {
            tagging?.release()
        } catch (e: Exception) {
            Napier.e("Error releasing desktop audio tagging", e)
        }
        tagging = null
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val WINDOW_SECONDS = 5
        private const val WINDOW_OVERLAP_SECONDS = 1
        private const val MIN_WINDOW_SAMPLES = 16_000
        private const val MIN_CONFIDENCE = 0.3f
        private const val TOP_K = 5
    }
}
