package app.logdate.client.image

import android.content.Context
import app.logdate.client.networking.DataUsagePolicy
import app.logdate.ui.media.addLocalMedia
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import org.koin.core.Koin

private const val IMAGE_CACHE_DIR = "image_cache"
private const val IMAGE_CACHE_MAX_BYTES = 250L * 1024 * 1024 // 250 MB
private const val IMAGE_CROSSFADE_MS = 200

/**
 * Creates the default Coil [ImageLoader] configured for LogDate.
 */
fun createLogDateImageLoader(
    context: Context,
    koin: Koin,
): ImageLoader =
    ImageLoader
        .Builder(context)
        // Cross-fade every image as it loads. Without this, thumbnails
        // pop in abruptly when the placeholder color is replaced; with
        // it, the gallery and timeline feel smooth on scroll.
        .crossfade(IMAGE_CROSSFADE_MS)
        .components {
            // Stored media strings name files by LogDate reference or by a path that may come
            // from another install; resolve them before Coil's own file handling sees them.
            addLocalMedia(koin.get())
            add(VideoFrameDecoder.Factory())
            add(DataSaverImageInterceptor(koin.get<DataUsagePolicy>()))
        }.memoryCache {
            MemoryCache
                .Builder()
                .maxSizePercent(context, percent = 0.25)
                .build()
        }.diskCache {
            DiskCache
                .Builder()
                .directory(context.cacheDir.resolve(IMAGE_CACHE_DIR))
                .maxSizeBytes(IMAGE_CACHE_MAX_BYTES)
                .build()
        }.build()
