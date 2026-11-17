package app.logdate.wear.data.storage

import android.content.Context
import android.os.StatFs
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Utility for checking available storage space on Wear OS.
 *
 * Used to validate if there's sufficient space for audio recordings
 * before starting a recording operation.
 */
class StorageSpaceChecker(
    private val context: Context,
) {
    /**
     * Gets the free space, in bytes, where recordings are stored: the app's private files directory.
     *
     * @return Available storage space in bytes, or 0 when it cannot be read so a recording is not
     *   started on a guess
     */
    suspend fun getAvailableStorageSpace(): Long =
        withContext(Dispatchers.IO) {
            try {
                getAvailableSpaceForPath(context.filesDir.absolutePath)
            } catch (e: Exception) {
                Napier.e("Error checking storage space", e)
                0L
            }
        }

    /**
     * Gets the available space for a specific path.
     *
     * @param path The directory path to check
     * @return Available space in bytes
     */
    private fun getAvailableSpaceForPath(path: String): Long =
        try {
            val stats = StatFs(path)
            val availableBlocks = stats.availableBlocksLong
            val blockSize = stats.blockSizeLong
            val availableBytes = availableBlocks * blockSize

            Napier.d("Available space at $path: ${formatSize(availableBytes)}")
            availableBytes
        } catch (e: Exception) {
            Napier.e("Failed to get space stats for $path", e)
            0L
        }

    /**
     * Formats a byte size into a human-readable string.
     * Used for logging and debugging.
     *
     * @param bytes Size in bytes
     * @return Formatted string (e.g. "1.5 MB")
     */
    private fun formatSize(bytes: Long): String =
        when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${String.format("%.2f", bytes / (1024.0 * 1024.0))} MB"
            else -> "${String.format("%.2f", bytes / (1024.0 * 1024.0 * 1024.0))} GB"
        }
}
