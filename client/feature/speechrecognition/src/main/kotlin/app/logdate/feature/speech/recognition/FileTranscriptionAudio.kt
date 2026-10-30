package app.logdate.feature.speech.recognition

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow

/** Streams bounded PCM chunks in the caller's job so worker cancellation releases decoding. */
internal suspend fun feedFileTranscriptionAudio(
    frames: Flow<FloatArray>,
    accept: (FloatArray) -> Unit,
): Boolean {
    var hasSamples = false
    frames.collect { samples ->
        currentCoroutineContext().ensureActive()
        var offset = 0
        while (offset < samples.size) {
            currentCoroutineContext().ensureActive()
            val end = (offset + 2048).coerceAtMost(samples.size)
            accept(samples.copyOfRange(offset, end))
            hasSamples = true
            offset = end
        }
    }
    return hasSamples
}
