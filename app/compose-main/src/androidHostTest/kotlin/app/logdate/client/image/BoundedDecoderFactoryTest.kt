package app.logdate.client.image

import coil3.ImageLoader
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BoundedDecoderFactoryTest {
    private val result = mockk<SourceFetchResult>()
    private val options = mockk<Options>()
    private val imageLoader = mockk<ImageLoader>()

    @Test
    fun `no more than the permitted video frames decode at once`() =
        runTest {
            val release = CompletableDeferred<Unit>()
            var running = 0
            var mostAtOnce = 0
            var finished = 0
            val slow =
                Decoder.Factory { _, _, _ ->
                    Decoder {
                        running++
                        mostAtOnce = maxOf(mostAtOnce, running)
                        release.await()
                        running--
                        finished++
                        null
                    }
                }
            val factory = BoundedDecoderFactory(slow, Semaphore(2))

            repeat(6) { launch { factory.create(result, options, imageLoader)!!.decode() } }
            runCurrent()
            assertEquals(2, running)

            release.complete(Unit)
            runCurrent()
            assertEquals(6, finished)
            assertEquals(2, mostAtOnce)
        }

    @Test
    fun `a source the delegate does not decode is left to the next decoder`() {
        val declines = Decoder.Factory { _, _, _ -> null }

        assertNull(BoundedDecoderFactory(declines, Semaphore(1)).create(result, options, imageLoader))
    }
}
