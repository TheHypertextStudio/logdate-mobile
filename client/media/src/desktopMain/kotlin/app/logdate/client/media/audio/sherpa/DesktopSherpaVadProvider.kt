package app.logdate.client.media.audio.sherpa

import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeechSegment
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Desktop counterpart of `SherpaOnnxVadProvider`. Wraps the JVM Sherpa-ONNX
 * Silero VAD with the same chunking-into-512-sample-windows pattern the
 * Android implementation uses, so the rest of the desktop transcription
 * pipeline can drive the VAD identically to Android.
 *
 * The JVM Sherpa API is similar to Android's but uses Java builders rather
 * than Kotlin data classes for config — that's the only material
 * difference visible from this file.
 */
internal class DesktopSherpaVadProvider(
    private val modelManager: DesktopSherpaModelManager,
) {
    private var vad: Vad? = null
    private val initMutex = Mutex()

    suspend fun ensureInitialized(): Boolean =
        initMutex.withLock {
            if (vad != null) return@withLock true

            val modelPath = modelManager.getVadModelPath() ?: return@withLock false
            val vadModelFile = java.io.File(modelPath)

            val config =
                VadModelConfig
                    .builder()
                    .setSileroVadModelConfig(
                        SileroVadModelConfig
                            .builder()
                            .setModel(vadModelFile.absolutePath)
                            .setThreshold(0.5f)
                            .setMinSilenceDuration(0.5f)
                            .setMinSpeechDuration(0.25f)
                            .setWindowSize(WINDOW_SIZE)
                            .setMaxSpeechDuration(30.0f)
                            .build(),
                    ).setSampleRate(SAMPLE_RATE)
                    .setNumThreads(1)
                    .setProvider("cpu")
                    .build()

            vad =
                withContext(Dispatchers.IO) {
                    Vad(config)
                }
            Napier.d("Desktop Silero VAD loaded from ${vadModelFile.absolutePath}")
            true
        }

    private fun requireVad(): Vad = vad ?: throw IllegalStateException("VAD not initialized; call ensureInitialized() first")

    /**
     * Feeds raw PCM samples in 512-sample windows to match Silero's chunk
     * size. Trailing samples shorter than [WINDOW_SIZE] are buffered until
     * enough data arrives or [flush] is called.
     */
    fun acceptWaveform(samples: FloatArray) {
        if (samples.isEmpty()) return
        val v = requireVad()

        var offset = 0
        if (pendingBufferLen > 0) {
            val needed = WINDOW_SIZE - pendingBufferLen
            val available = samples.size.coerceAtMost(needed)
            System.arraycopy(samples, 0, pendingBuffer, pendingBufferLen, available)
            pendingBufferLen += available
            offset += available
            if (pendingBufferLen == WINDOW_SIZE) {
                v.acceptWaveform(pendingBuffer)
                pendingBufferLen = 0
            }
        }

        while (offset + WINDOW_SIZE <= samples.size) {
            val window = FloatArray(WINDOW_SIZE)
            System.arraycopy(samples, offset, window, 0, WINDOW_SIZE)
            v.acceptWaveform(window)
            offset += WINDOW_SIZE
        }

        val remainder = samples.size - offset
        if (remainder > 0) {
            System.arraycopy(samples, offset, pendingBuffer, 0, remainder)
            pendingBufferLen = remainder
        }
    }

    fun flush() {
        val v = vad ?: return
        if (pendingBufferLen > 0) {
            for (i in pendingBufferLen until WINDOW_SIZE) {
                pendingBuffer[i] = 0f
            }
            v.acceptWaveform(pendingBuffer)
            pendingBufferLen = 0
        }
        v.flush()
    }

    fun isEmpty(): Boolean = requireVad().empty()

    fun front(): SpeechSegment = requireVad().front()

    fun pop() = requireVad().pop()

    fun reset() {
        pendingBufferLen = 0
        vad?.reset()
    }

    fun release() {
        try {
            vad?.release()
        } catch (e: Exception) {
            Napier.e("Error releasing desktop VAD", e)
        }
        vad = null
        pendingBufferLen = 0
    }

    private val pendingBuffer = FloatArray(WINDOW_SIZE)
    private var pendingBufferLen = 0

    companion object {
        const val SAMPLE_RATE = 16_000
        const val WINDOW_SIZE = 512

        const val VAD_MODEL_FILE_NAME = DesktopSherpaModelManager.VAD_MODEL_FILE_NAME
    }
}
