@file:OptIn(
    kotlinx.cinterop.BetaInteropApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package app.logdate.ui.audio

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.posix.memcpy

/**
 * iOS implementation of WaveformStorage using the app cache directory.
 */
class IosWaveformStorage(
    private val cacheRootPath: String = defaultCachePath(),
) : WaveformStorage {
    private val fileManager = NSFileManager.defaultManager

    override suspend fun save(
        audioUri: String,
        amplitudes: List<Float>,
    ) = withContext(Dispatchers.Default) {
        ensureCacheDir()
        val filePath = waveformFilePath(audioUri)
        val data = amplitudesToBytes(amplitudes).toNSData()
        data.writeToFile(filePath, true)
        Unit
    }

    override suspend fun load(audioUri: String): List<Float>? =
        withContext(Dispatchers.Default) {
            val filePath = waveformFilePath(audioUri)
            val data = fileManager.contentsAtPath(filePath) ?: return@withContext null
            bytesToAmplitudes(data.toByteArray())
        }

    override suspend fun exists(audioUri: String): Boolean =
        withContext(Dispatchers.Default) {
            fileManager.fileExistsAtPath(waveformFilePath(audioUri))
        }

    override suspend fun delete(audioUri: String) =
        withContext(Dispatchers.Default) {
            fileManager.removeItemAtPath(waveformFilePath(audioUri), error = null)
            Unit
        }

    private fun waveformFilePath(audioUri: String): String {
        val hash = waveformCacheKey(audioUri)
        return "$cacheRootPath/$hash.waveform"
    }

    private fun ensureCacheDir() {
        fileManager.createDirectoryAtPath(
            cacheRootPath,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
    }

    private fun amplitudesToBytes(amplitudes: List<Float>): ByteArray {
        val bytes = ByteArray(amplitudes.size * 4)
        var offset = 0
        amplitudes.forEach { value ->
            val bits = value.toBits()
            bytes[offset++] = (bits and 0xFF).toByte()
            bytes[offset++] = ((bits ushr 8) and 0xFF).toByte()
            bytes[offset++] = ((bits ushr 16) and 0xFF).toByte()
            bytes[offset++] = ((bits ushr 24) and 0xFF).toByte()
        }
        return bytes
    }

    private fun bytesToAmplitudes(bytes: ByteArray): List<Float> {
        val count = bytes.size / 4
        val amplitudes = ArrayList<Float>(count)
        var offset = 0
        repeat(count) {
            val bits =
                (bytes[offset].toInt() and 0xFF) or
                    ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                    ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                    ((bytes[offset + 3].toInt() and 0xFF) shl 24)
            amplitudes.add(Float.fromBits(bits))
            offset += 4
        }
        return amplitudes
    }

    private fun ByteArray.toNSData(): NSData =
        usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
        }

    private fun NSData.toByteArray(): ByteArray {
        val length = this.length.toInt()
        if (length == 0) {
            return ByteArray(0)
        }
        val buffer = ByteArray(length)
        buffer.usePinned { pinned ->
            memcpy(pinned.addressOf(0), bytes, length.toULong())
        }
        return buffer
    }
}

private fun defaultCachePath(): String {
    val fileManager = NSFileManager.defaultManager
    val url: NSURL? =
        fileManager.URLForDirectory(
            directory = NSCachesDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = true,
            error = null,
        )
    val basePath = requireNotNull(url?.path)
    return "$basePath/waveforms"
}
