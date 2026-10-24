package app.logdate

import app.logdate.client.media.storage.MediaFileResolver
import app.logdate.ui.media.addLocalMedia
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.request.crossfade
import java.io.File
import java.util.Locale

/**
 * Builds the LogDate-configured Coil [ImageLoader] for Desktop (JVM).
 *
 * Matches the Android and iOS configurations: bounded memory and disk caches
 * plus a crossfade default. The disk cache lives under the standard
 * OS-specific user cache directory so the app integrates with the host's
 * "purgeable data" conventions.
 */
fun buildLogDateImageLoader(
    context: PlatformContext,
    mediaFiles: MediaFileResolver,
): ImageLoader =
    ImageLoader
        .Builder(context)
        .crossfade(IMAGE_CROSSFADE_MS)
        .components { addLocalMedia(mediaFiles) }
        .memoryCache {
            MemoryCache
                .Builder()
                .maxSizePercent(context, percent = MEMORY_CACHE_PERCENT)
                .build()
        }.diskCache {
            DiskCache
                .Builder()
                .directory(resolveDesktopCacheDir())
                .maxSizeBytes(IMAGE_CACHE_MAX_BYTES)
                .build()
        }.build()

/**
 * Resolves the OS-appropriate cache directory for LogDate's image cache.
 *
 * macOS: `~/Library/Caches/studio.hypertext.logdate/image_cache`.
 * Windows: `%LOCALAPPDATA%\studio.hypertext.logdate\image_cache`.
 * Linux / other: `~/.cache/studio.hypertext.logdate/image_cache`.
 */
private fun resolveDesktopCacheDir(): File {
    val os = System.getProperty("os.name").lowercase(Locale.ROOT)
    val home = System.getProperty("user.home")
    val base =
        when {
            os.contains("mac") -> {
                File(home, "Library/Caches/$APP_DIR_NAME")
            }

            os.contains("win") -> {
                val localAppData = System.getenv("LOCALAPPDATA")
                if (localAppData != null) {
                    File(localAppData, APP_DIR_NAME)
                } else {
                    File(home, "AppData/Local/$APP_DIR_NAME")
                }
            }

            else -> {
                val xdg = System.getenv("XDG_CACHE_HOME")
                if (xdg != null) File(xdg, APP_DIR_NAME) else File(home, ".cache/$APP_DIR_NAME")
            }
        }
    return File(base, IMAGE_CACHE_DIR).apply { mkdirs() }
}

private const val IMAGE_CROSSFADE_MS = 200
private const val MEMORY_CACHE_PERCENT = 0.25
private const val IMAGE_CACHE_DIR = "image_cache"
private const val IMAGE_CACHE_MAX_BYTES = 250L * 1024 * 1024 // 250 MB
private const val APP_DIR_NAME = "studio.hypertext.logdate"
