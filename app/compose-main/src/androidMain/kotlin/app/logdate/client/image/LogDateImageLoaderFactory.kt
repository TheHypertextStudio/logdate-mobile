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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import org.koin.core.Koin

private const val IMAGE_CACHE_DIR = "image_cache"
private const val IMAGE_CACHE_MAX_BYTES = 250L * 1024 * 1024 // 250 MB
private const val IMAGE_CROSSFADE_MS = 200

/** Threads image fetching and decoding may hold at once. */
internal const val IMAGE_WORK_PARALLELISM = 8

/**
 * Video frames read at once. Each one holds an image thread and a media decoder until it finishes, and a
 * frame read cannot be stopped once it starts, so this leaves room for a slow file without letting video
 * take every image thread.
 */
internal const val VIDEO_FRAME_PARALLELISM = 4

/**
 * Creates the default Coil [ImageLoader] configured for LogDate.
 */
fun createLogDateImageLoader(
    context: Context,
    koin: Koin,
): ImageLoader {
    // Coil fetches and decodes on Dispatchers.IO by default, the pool the database also queries on.
    // A screen of slow thumbnails could fill it and leave the timeline waiting for its notes, so
    // images get their own bounded share of threads.
    val imageWork = Dispatchers.IO.limitedParallelism(IMAGE_WORK_PARALLELISM)
    return ImageLoader
        .Builder(context)
        // Cross-fade every image as it loads. Without this, thumbnails
        // pop in abruptly when the placeholder color is replaced; with
        // it, the gallery and timeline feel smooth on scroll.
        .crossfade(IMAGE_CROSSFADE_MS)
        .fetcherCoroutineContext(imageWork)
        .decoderCoroutineContext(imageWork)
        .components {
            // Stored media strings name files by LogDate reference or by a path that may come
            // from another install; resolve them before Coil's own file handling sees them.
            addLocalMedia(koin.get())
            add(BoundedDecoderFactory(VideoFrameDecoder.Factory(), Semaphore(VIDEO_FRAME_PARALLELISM)))
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
}
