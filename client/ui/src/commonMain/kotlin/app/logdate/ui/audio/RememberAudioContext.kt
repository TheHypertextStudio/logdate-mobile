package app.logdate.ui.audio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlin.time.Instant

/** Derived audio belongs to its media revision, independently of a card's presentation. */
@Composable
fun rememberAudioContext(
    audioUri: String,
    durationMs: Long,
    recordedAt: Instant,
    latitude: Double? = null,
    longitude: Double? = null,
    processor: AudioContextProcessor? = LocalAudioContextProcessor.current,
): AudioContext? {
    if (processor == null) return null
    return key(processor, audioUri, durationMs) {
        val initial =
            processor.cachedAmplitudes(audioUri, durationMs)?.let { samples ->
                processor.buildInitialContext(recordedAt, latitude, longitude).copy(amplitudes = samples)
            }
        val context by produceState(initial, processor, audioUri, durationMs, recordedAt, latitude, longitude) {
            try {
                processor.processProgressively(audioUri, durationMs, recordedAt, latitude, longitude).collect { value = it }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Napier.w(error) { "Unable to load the recording waveform" }
            }
        }
        context
    }
}
