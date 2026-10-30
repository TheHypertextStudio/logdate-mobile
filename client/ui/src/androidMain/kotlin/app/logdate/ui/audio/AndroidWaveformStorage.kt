package app.logdate.ui.audio

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Android implementation of WaveformStorage using file-based persistence.
 *
 * Waveform files are stored in the app's cache directory under `waveforms/`.
 * Each file is named by the SHA-256 of the audio URI and contains packed floats.
 */
class AndroidWaveformStorage(
    private val context: Context,
) : WaveformStorage {
    private val waveformDir: File
        get() = File(context.cacheDir, "waveforms").also { it.mkdirs() }

    private fun getWaveformFile(audioUri: String): File {
        val hash = waveformCacheKey(audioUri)
        return File(waveformDir, "$hash.waveform")
    }

    override suspend fun save(
        audioUri: String,
        amplitudes: List<Float>,
    ) = withContext(Dispatchers.IO) {
        val file = getWaveformFile(audioUri)

        val buffer = ByteBuffer.allocate(amplitudes.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        amplitudes.forEach { buffer.putFloat(it) }
        file.writeBytes(buffer.array())
    }

    override suspend fun load(audioUri: String): List<Float>? =
        withContext(Dispatchers.IO) {
            val file = getWaveformFile(audioUri)
            if (!file.exists()) return@withContext null

            val bytes = file.readBytes()
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val amplitudes = mutableListOf<Float>()
            while (buffer.hasRemaining()) {
                amplitudes.add(buffer.float)
            }
            amplitudes
        }

    override suspend fun exists(audioUri: String): Boolean =
        withContext(Dispatchers.IO) {
            getWaveformFile(audioUri).exists()
        }

    override suspend fun delete(audioUri: String) =
        withContext(Dispatchers.IO) {
            getWaveformFile(audioUri).delete()
            Unit
        }
}
