package app.logdate.client.image

import coil3.ImageLoader
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Lets at most [permits] decodes from [delegate] run at once; the rest wait without holding a thread.
 *
 * Coil limits its own bitmap decoding, but a video frame is read by `MediaMetadataRetriever`, which
 * blocks its thread for as long as the frame takes to seek and decode and has no limit of its own.
 * A screen full of video tiles would otherwise start one retriever per tile.
 */
internal class BoundedDecoderFactory(
    private val delegate: Decoder.Factory,
    private val permits: Semaphore,
) : Decoder.Factory {
    override fun create(
        result: SourceFetchResult,
        options: Options,
        imageLoader: ImageLoader,
    ): Decoder? {
        val decoder = delegate.create(result, options, imageLoader) ?: return null
        return Decoder { permits.withPermit { decoder.decode() } }
    }
}
