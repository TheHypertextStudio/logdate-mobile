package app.logdate.ui.audio

import app.logdate.client.awareness.daylight.DaylightClassifier
import app.logdate.client.awareness.daylight.DaylightPeriod
import app.logdate.ui.audio.analysis.SegmentDetector
import app.logdate.ui.audio.color.PaletteGenerator
import app.logdate.ui.audio.extraction.AmplitudeExtractor
import app.logdate.ui.audio.model.AudioPalette
import app.logdate.ui.audio.model.AudioSegment
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.time.Instant

internal fun waveformStorageKey(
    audioUri: String,
    durationMs: Long,
): String = "waveform-v2:${audioUri.length}:$audioUri:$durationMs"

/**
 * Processed audio context containing all derived data for visualization.
 *
 * @param amplitudes Normalized waveform amplitudes (0.0 to 1.0)
 * @param segments Detected audio segments for haptic feedback
 * @param daylightPeriod Time-of-day classification
 * @param palette Contextual color palette based on daylight period
 */
data class AudioContext(
    val amplitudes: List<Float>,
    val segments: List<AudioSegment>,
    val daylightPeriod: DaylightPeriod,
    val palette: AudioPalette,
)

/**
 * Processes audio files to extract context for visualization.
 *
 * Orchestrates:
 * - Waveform extraction and caching
 * - Segment detection
 * - Daylight classification
 * - Palette generation
 *
 * Results are cached using [WaveformStorage] to avoid re-processing
 * on subsequent loads.
 */
class AudioContextProcessor(
    private val amplitudeExtractor: AmplitudeExtractor,
    private val waveformStorage: WaveformStorage,
    private val segmentDetector: SegmentDetector = SegmentDetector(),
    private val daylightClassifier: DaylightClassifier = DaylightClassifier(),
    private val paletteGenerator: PaletteGenerator = PaletteGenerator(),
    private val coroutineContext: CoroutineContext = Dispatchers.Default,
) {
    private data class MediaKey(
        val uri: String,
        val durationMs: Long,
    )

    private val completed = MutableStateFlow<Map<MediaKey, List<Float>>>(emptyMap())

    /** Last complete samples for a mounted recording, available before asynchronous cache IO. */
    fun cachedAmplitudes(
        audioUri: String,
        durationMs: Long,
    ): List<Float>? = completed.value[MediaKey(audioUri, durationMs)]

    private fun retain(
        audioUri: String,
        durationMs: Long,
        amplitudes: List<Float>,
    ) {
        if (amplitudes.isEmpty()) return
        val key = MediaKey(audioUri, durationMs)
        completed.update { previous ->
            val recent =
                previous
                    .filterKeys { it != key }
                    .entries
                    .toList()
                    .takeLast(63)
                    .associate { it.toPair() }
            recent + (key to amplitudes.toList())
        }
    }

    private suspend fun cacheAmplitudes(
        audioUri: String,
        durationMs: Long,
        amplitudes: List<Float>,
    ) {
        retain(audioUri, durationMs, amplitudes)
        try {
            waveformStorage.save(waveformStorageKey(audioUri, durationMs), amplitudes)
            Napier.d { "Cached waveform for $audioUri" }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Napier.w(error) { "Failed to cache waveform for $audioUri" }
        }
    }

    private suspend fun loadCached(
        audioUri: String,
        durationMs: Long,
    ): List<Float>? =
        try {
            val key = waveformStorageKey(audioUri, durationMs)
            val cached =
                waveformStorage
                    .load(key)
                    ?.takeIf { it.isNotEmpty() && it.all { level -> level.isFinite() && level in 0f..1f } }
            if (cached != null) {
                cached
            } else {
                val legacy =
                    waveformStorage
                        .load(audioUri)
                        ?.takeIf { it.isNotEmpty() && it.all { level -> level.isFinite() && level in 0f..1f } }
                if (legacy != null) {
                    try {
                        waveformStorage.save(key, legacy)
                        waveformStorage.delete(audioUri)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Napier.w(error) { "Keeping readable waveform samples despite cache migration failure" }
                    }
                }
                legacy
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Napier.w(error) { "Ignoring a damaged waveform cache; original audio is unchanged" }
            null
        }

    /**
     * Processes an audio file and returns its context for visualization.
     *
     * If waveform data is cached, it will be loaded from storage.
     * Otherwise, the audio file is decoded and amplitudes are extracted
     * and cached for future use.
     *
     * @param audioUri URI of the audio file
     * @param durationMs Duration of the audio in milliseconds
     * @param createdAt When the audio was recorded
     * @param latitude Latitude where recorded (null if unavailable)
     * @param longitude Longitude where recorded (null if unavailable)
     * @return AudioContext with all derived data for visualization
     */
    suspend fun process(
        audioUri: String,
        durationMs: Long,
        createdAt: Instant,
        latitude: Double?,
        longitude: Double?,
    ): AudioContext =
        withContext(coroutineContext) {
            Napier.d { "Processing audio context for $audioUri" }

            val amplitudes = loadOrExtractAmplitudes(audioUri, durationMs)
            val segments =
                if (amplitudes.isNotEmpty() && durationMs > 0) {
                    segmentDetector.detectSegments(amplitudes, durationMs)
                } else {
                    emptyList()
                }
            val daylightPeriod = classifyDaylightPeriod(createdAt, latitude, longitude)
            val palette = paletteGenerator.generate(daylightPeriod)

            Napier.d { "Audio context processed: ${amplitudes.size} amplitudes, ${segments.size} segments, $daylightPeriod" }

            AudioContext(
                amplitudes = amplitudes,
                segments = segments,
                daylightPeriod = daylightPeriod,
                palette = palette,
            )
        }

    /**
     * Processes an audio file progressively, emitting context updates as the waveform
     * decodes. The first emission carries the daylight period and palette plus any
     * cached amplitudes (or empty if uncached) so the UI can paint immediately; later
     * emissions refresh the amplitudes and segments as decoding fills in.
     *
     * On cache hit, only one emission is sent — the cached waveform is already complete.
     * On cache miss, the underlying extractor's progressive flow drives multiple emissions,
     * and the final amplitudes are written back to [WaveformStorage].
     */
    fun processProgressively(
        audioUri: String,
        durationMs: Long,
        createdAt: Instant,
        latitude: Double?,
        longitude: Double?,
    ): Flow<AudioContext> =
        flow {
            Napier.d { "Processing audio context progressively for $audioUri" }

            val daylightPeriod = classifyDaylightPeriod(createdAt, latitude, longitude)
            val palette = paletteGenerator.generate(daylightPeriod)

            val cached = cachedAmplitudes(audioUri, durationMs) ?: loadCached(audioUri, durationMs)
            if (cached != null) {
                retain(audioUri, durationMs, cached)
                Napier.d { "Loaded cached waveform for $audioUri" }
                emit(
                    AudioContext(
                        amplitudes = cached,
                        segments =
                            if (cached.isNotEmpty() && durationMs > 0) {
                                segmentDetector.detectSegments(cached, durationMs)
                            } else {
                                emptyList()
                            },
                        daylightPeriod = daylightPeriod,
                        palette = palette,
                    ),
                )
                return@flow
            }

            // First emission: empty waveform, but palette and segments-from-empty are set so
            // the player paints immediately while decoding runs.
            emit(
                AudioContext(
                    amplitudes = emptyList(),
                    segments = emptyList(),
                    daylightPeriod = daylightPeriod,
                    palette = palette,
                ),
            )

            if (isRemoteAudio(audioUri)) return@flow
            var lastSnapshot: List<Float> = emptyList()
            amplitudeExtractor
                .extractAmplitudesProgressively(audioUri)
                .collect { snapshot ->
                    lastSnapshot = snapshot
                    emit(
                        AudioContext(
                            amplitudes = snapshot,
                            segments =
                                if (snapshot.isNotEmpty() && durationMs > 0) {
                                    segmentDetector.detectSegments(snapshot, durationMs)
                                } else {
                                    emptyList()
                                },
                            daylightPeriod = daylightPeriod,
                            palette = palette,
                        ),
                    )
                }

            if (lastSnapshot.isNotEmpty()) {
                cacheAmplitudes(audioUri, durationMs, lastSnapshot)
            }
        }.flowOn(coroutineContext)

    /**
     * Loads cached amplitudes or extracts them from the audio file.
     */
    private suspend fun loadOrExtractAmplitudes(
        audioUri: String,
        durationMs: Long,
    ): List<Float> {
        (cachedAmplitudes(audioUri, durationMs) ?: loadCached(audioUri, durationMs))?.let { cached ->
            retain(audioUri, durationMs, cached)
            Napier.d { "Loaded cached waveform for $audioUri" }
            return cached
        }

        if (isRemoteAudio(audioUri)) return emptyList()
        Napier.d { "Extracting amplitudes from $audioUri" }
        val amplitudes = amplitudeExtractor.extractAmplitudes(audioUri)

        if (amplitudes.isNotEmpty()) {
            cacheAmplitudes(audioUri, durationMs, amplitudes)
        }

        return amplitudes
    }

    /**
     * Builds an initial context for immediate display while waveform extraction runs.
     *
     * Palette and daylight period are derived from the recording timestamp and location —
     * fast operations that don't require audio decoding. Amplitudes and segments are empty
     * and will be populated once [process] completes in the background.
     */
    fun buildInitialContext(
        createdAt: Instant,
        latitude: Double?,
        longitude: Double?,
    ): AudioContext {
        val daylightPeriod = classifyDaylightPeriod(createdAt, latitude, longitude)
        return AudioContext(
            amplitudes = emptyList(),
            segments = emptyList(),
            daylightPeriod = daylightPeriod,
            palette = paletteGenerator.generate(daylightPeriod),
        )
    }

    private fun isRemoteAudio(uri: String): Boolean =
        uri.startsWith("https://", ignoreCase = true) || uri.startsWith("http://", ignoreCase = true)

    private fun classifyDaylightPeriod(
        createdAt: Instant,
        latitude: Double?,
        longitude: Double?,
    ): DaylightPeriod =
        if (latitude != null && longitude != null) {
            daylightClassifier.classify(createdAt, latitude, longitude)
        } else {
            daylightClassifier.classifyWithoutLocation(createdAt)
        }

    /**
     * Clears cached waveform data for an audio file.
     *
     * Call this when the audio file is deleted.
     */
    suspend fun clearCache(audioUri: String) {
        val knownKeys = completed.value.keys.filter { it.uri == audioUri }
        completed.update { rows -> rows.filterKeys { it.uri != audioUri } }
        try {
            waveformStorage.delete(audioUri)
            knownKeys.forEach { waveformStorage.delete(waveformStorageKey(it.uri, it.durationMs)) }
            Napier.d { "Cleared waveform cache for $audioUri" }
        } catch (e: Exception) {
            Napier.w(e) { "Failed to clear waveform cache for $audioUri" }
        }
    }
}
