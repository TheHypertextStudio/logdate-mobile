@file:OptIn(
    kotlinx.cinterop.BetaInteropApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package app.logdate.client.media.audio.tagging

import app.logdate.client.media.audio.download.ModelDownloadStatus
import app.logdate.client.media.storage.MediaFileResolver
import app.logdate.client.media.storage.fileUrl
import io.github.aakira.napier.Napier
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.valueForKey
import platform.SoundAnalysis.SNAudioFileAnalyzer
import platform.SoundAnalysis.SNClassificationResult
import platform.SoundAnalysis.SNClassifierIdentifierVersion1
import platform.SoundAnalysis.SNClassifySoundRequest
import platform.SoundAnalysis.SNRequestProtocol
import platform.SoundAnalysis.SNResultProtocol
import platform.SoundAnalysis.SNResultsObservingProtocol
import platform.darwin.NSObject

/**
 * iOS implementation of [AudioTaggingService] backed by Apple's on-device
 * Sound Analysis framework (iOS 15+). Detects ambient sounds — birds,
 * traffic, music, rain — in a recorded audio file without any model download.
 *
 * The built-in Version 1 classifier covers ~300 AudioSet sound categories
 * at a similar breadth to the CED model used on Android and desktop.
 * "Speech" detections are filtered out so the chips reflect environmental
 * context rather than the user's voice.
 *
 * Individual [SNClassification] items are accessed via KVC (valueForKey) because
 * the KN-generated protocol type is not guaranteed to be stable across toolchain
 * versions; "confidence" and "identifier" are stable public API keys.
 */
internal class IosSoundAnalysisTaggingService(
    private val mediaFiles: MediaFileResolver,
) : AudioTaggingService {
    override val modelDownloadStatus: StateFlow<ModelDownloadStatus> =
        MutableStateFlow<ModelDownloadStatus>(ModelDownloadStatus.ManagedBySystem).asStateFlow()

    override val isAvailable: Boolean = true

    override suspend fun warmUp(): Boolean = true

    override fun tagAudio(audioUri: String): Flow<AudioTaggingResult> =
        flow {
            val url = mediaFiles.fileUrl(audioUri)
            if (url == null) {
                emit(AudioTaggingResult.Error("Not a local audio file: $audioUri"))
                return@flow
            }
            val result =
                withContext(Dispatchers.Default) {
                    analyzeFile(url)
                }
            emit(result)
        }.flowOn(Dispatchers.Default)

    private fun analyzeFile(url: NSURL): AudioTaggingResult {
        val cumulative = mutableMapOf<String, DetectedSound>()
        var analysisError: String? = null

        memScoped {
            val errorPtr = alloc<ObjCObjectVar<NSError?>>()

            val request =
                SNClassifySoundRequest(
                    classifierIdentifier = SNClassifierIdentifierVersion1,
                    error = errorPtr.ptr,
                )
            if (errorPtr.value != null) {
                return AudioTaggingResult.Error(
                    "Failed to create sound classifier: ${errorPtr.value?.localizedDescription}",
                )
            }

            val analyzer = SNAudioFileAnalyzer(uRL = url, error = errorPtr.ptr)
            if (errorPtr.value != null) {
                return AudioTaggingResult.Error(
                    "Failed to open audio for analysis: ${errorPtr.value?.localizedDescription}",
                )
            }

            val observer =
                object : NSObject(), SNResultsObservingProtocol {
                    override fun request(
                        request: SNRequestProtocol,
                        didProduceResult: SNResultProtocol,
                    ) {
                        val classification = didProduceResult as? SNClassificationResult ?: return
                        // SNClassification is an ObjC protocol; elements are accessed via
                        // KVC so we don't depend on the unstable KN-generated protocol name.
                        for (item in classification.classifications) {
                            val c = item as? NSObject ?: continue
                            val confidence =
                                (c.valueForKey("confidence") as? NSNumber)
                                    ?.doubleValue
                                    ?.toFloat()
                                    ?: continue
                            if (confidence < MIN_CONFIDENCE) continue
                            val label = c.valueForKey("identifier") as? String ?: continue
                            if (label.equals("speech", ignoreCase = true)) continue
                            mergeDetection(cumulative, label.toDisplayLabel(), confidence)
                        }
                    }

                    override fun request(
                        request: SNRequestProtocol,
                        didFailWithError: NSError,
                    ) {
                        analysisError = didFailWithError.localizedDescription
                        Napier.e("iOS sound analysis error: ${didFailWithError.localizedDescription}")
                    }

                    override fun requestDidComplete(request: SNRequestProtocol) {
                        Napier.d("iOS sound analysis complete")
                    }
                }

            if (!analyzer.addRequest(request, withObserver = observer, error = errorPtr.ptr)) {
                return AudioTaggingResult.Error(
                    "Failed to attach observer: ${errorPtr.value?.localizedDescription}",
                )
            }

            // analyze() is synchronous — blocks until all windows are processed.
            // Always called inside withContext(Dispatchers.Default) by the caller.
            analyzer.analyze()
        }

        if (analysisError != null) {
            return AudioTaggingResult.Error("Sound analysis failed: $analysisError")
        }

        return AudioTaggingResult.Success(
            sounds = cumulative.values.sortedByDescending { it.confidence },
            isFinal = true,
        )
    }

    private fun mergeDetection(
        cumulative: MutableMap<String, DetectedSound>,
        name: String,
        confidence: Float,
    ) {
        val existing = cumulative[name]
        if (existing == null) {
            cumulative[name] =
                DetectedSound(name = name, confidence = confidence, startMs = 0L, durationMs = 0L)
        } else {
            cumulative[name] = existing.copy(confidence = maxOf(existing.confidence, confidence))
        }
    }

    override fun release() = Unit

    companion object {
        private const val MIN_CONFIDENCE = 0.3f
    }
}

/**
 * Converts an AudioSet-style snake_case identifier (e.g. "bird_song") into a
 * human-readable label ("Bird Song").
 */
private fun String.toDisplayLabel(): String =
    replace('_', ' ')
        .split(' ')
        .joinToString(" ") { word -> word.replaceFirstChar { it.uppercaseChar() } }
