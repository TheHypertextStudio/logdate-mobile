package app.logdate.ui.media

import app.logdate.client.media.storage.MediaCollection
import app.logdate.client.media.storage.MediaDirectories
import app.logdate.client.media.storage.MediaFileResolver
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LocalMediaImageLoadingTest {
    private val directories =
        object : MediaDirectories {
            override fun directory(collection: MediaCollection): String = "/install/new/${collection.id}"

            override fun pathInCurrentInstall(path: String): String? = path.replace("/install/old/", "/install/new/").takeIf { it != path }
        }
    private val options = Options(PlatformContext.INSTANCE)
    private val loader = ImageLoader.Builder(PlatformContext.INSTANCE).build()

    @Test
    fun `local media strings are mapped without touching the filesystem`() {
        val mapper = LocalMediaImageMapper()

        assertEquals(LocalMediaSource("logdate-media://library/a.jpg"), mapper.map("logdate-media://library/a.jpg", options))
        assertEquals(LocalMediaSource("file:///install/old/library/a.jpg"), mapper.map("file:///install/old/library/a.jpg", options))
        assertEquals(LocalMediaSource("/install/new/library/a.jpg"), mapper.map("/install/new/library/a.jpg", options))
    }

    @Test
    fun `other strings are left to the image loader`() {
        val mapper = LocalMediaImageMapper()

        assertNull(mapper.map("https://cloud.logdate.app/media/abc", options))
        assertNull(mapper.map("content://media/external/images/media/1", options))
        assertNull(mapper.map("ph://ABC/L0/001", options))
    }

    @Test
    fun `the fetcher opens the file a media reference names`() =
        runTest {
            val fetcher = fetcherFor("logdate-media://library/IMG%200001.jpg", existing = emptySet())

            assertEquals("/install/new/library/IMG 0001.jpg".toPath(), fetchedFile(fetcher))
        }

    @Test
    fun `the fetcher opens a file from an earlier install at its place in this one`() =
        runTest {
            val fetcher = fetcherFor("file:///install/old/library/a.jpg", existing = setOf("/install/new/library/a.jpg"))

            assertEquals("/install/new/library/a.jpg".toPath(), fetchedFile(fetcher))
        }

    @Test
    fun `local videos expose their mime type to thumbnail decoders`() =
        runTest {
            val mp4 = fetcherFor("logdate-media://library/capture.mp4", existing = emptySet()).fetch() as SourceFetchResult
            val mov = fetcherFor("file:///install/old/library/clip.MOV", existing = emptySet()).fetch() as SourceFetchResult
            assertEquals("video/mp4", mp4.mimeType)
            assertEquals("video/quicktime", mov.mimeType)
        }

    @Test
    fun `memory cache keys are the stored strings`() {
        assertEquals("logdate-media://library/a.jpg", LocalMediaKeyer().key(LocalMediaSource("logdate-media://library/a.jpg"), options))
    }

    private fun fetcherFor(
        reference: String,
        existing: Set<String>,
    ) = assertNotNull(
        LocalMediaFetcher.Factory(MediaFileResolver(directories) { it in existing }).create(LocalMediaSource(reference), options, loader),
    )

    private suspend fun fetchedFile(fetcher: Fetcher) = (fetcher.fetch() as SourceFetchResult).source.file()
}
