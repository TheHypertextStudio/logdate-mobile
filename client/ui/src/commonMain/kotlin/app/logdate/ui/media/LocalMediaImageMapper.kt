package app.logdate.ui.media

import app.logdate.client.media.storage.MediaFileResolver
import app.logdate.client.media.storage.MediaReference
import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.map.Mapper
import coil3.request.Options
import okio.Path.Companion.toPath

/**
 * A stored media string that names a local file, passed through Coil until the fetcher opens it.
 *
 * The string is resolved to a file only in [LocalMediaFetcher], on Coil's fetch dispatcher, so
 * scrolling a list never checks the filesystem on the main thread.
 */
data class LocalMediaSource(
    val reference: String,
)

/**
 * Sends stored media strings that name local files (`logdate-media://` references, `file:` URIs
 * and paths, including ones from an earlier install) to [LocalMediaFetcher]. Anything else
 * (remote URLs, content providers) is left to Coil.
 */
class LocalMediaImageMapper : Mapper<String, LocalMediaSource> {
    override fun map(
        data: String,
        options: Options,
    ): LocalMediaSource? = LocalMediaSource(data).takeUnless { MediaReference.parse(data) is MediaReference.External }
}

/** Opens the file [source] names, found through [MediaFileResolver] when the fetch runs. */
class LocalMediaFetcher(
    private val source: LocalMediaSource,
    private val options: Options,
    private val mediaFiles: MediaFileResolver,
) : Fetcher {
    override suspend fun fetch(): SourceFetchResult {
        val path = checkNotNull(mediaFiles.filePath(source.reference)) { "Not a local media file: ${source.reference}" }
        return SourceFetchResult(
            source = ImageSource(file = path.toPath(), fileSystem = options.fileSystem),
            mimeType =
                when (path.substringAfterLast('.', "").lowercase()) {
                    "mp4", "m4v" -> "video/mp4"
                    "mov" -> "video/quicktime"
                    "webm" -> "video/webm"
                    "3gp", "3gpp" -> "video/3gpp"
                    "mkv" -> "video/x-matroska"
                    else -> null
                },
            dataSource = DataSource.DISK,
        )
    }

    class Factory(
        private val mediaFiles: MediaFileResolver,
    ) : Fetcher.Factory<LocalMediaSource> {
        override fun create(
            data: LocalMediaSource,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher = LocalMediaFetcher(data, options, mediaFiles)
    }
}

/** Keys the memory cache by the stored string. */
class LocalMediaKeyer : Keyer<LocalMediaSource> {
    override fun key(
        data: LocalMediaSource,
        options: Options,
    ): String = data.reference
}

/** Teaches an image loader to open stored local media. */
fun ComponentRegistry.Builder.addLocalMedia(mediaFiles: MediaFileResolver): ComponentRegistry.Builder =
    add(LocalMediaImageMapper())
        .add(LocalMediaKeyer())
        .add(LocalMediaFetcher.Factory(mediaFiles))
