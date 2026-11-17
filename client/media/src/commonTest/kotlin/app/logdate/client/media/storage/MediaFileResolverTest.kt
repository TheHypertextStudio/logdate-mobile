package app.logdate.client.media.storage

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MediaFileResolverTest {
    private val directories = FakeInstallDirectories(installRoot = "/install/new")

    @Test
    fun `owned references resolve inside their collection directory`() {
        val resolver = resolverWithFiles()

        assertEquals(
            "/install/new/media/objects/sha256/9f/9f86d081.jpg",
            resolver.filePath("logdate-media://library/objects/sha256/9f/9f86d081.jpg"),
        )
        assertEquals(
            "/install/new/audio_notes/recording 42.m4a",
            resolver.filePath("logdate-media://recordings/recording%2042.m4a"),
        )
    }

    @Test
    fun `local files in this install resolve as written`() {
        val resolver = resolverWithFiles("/install/new/media/a.jpg")

        assertEquals("/install/new/media/a.jpg", resolver.filePath("file:///install/new/media/a.jpg"))
        assertEquals("/install/new/media/a.jpg", resolver.filePath("/install/new/media/a.jpg"))
    }

    @Test
    fun `local files from an earlier install resolve to this install when the file is here`() {
        val resolver = resolverWithFiles("/install/new/media/a.jpg")

        assertEquals("/install/new/media/a.jpg", resolver.filePath("file:///install/old/media/a.jpg"))
    }

    @Test
    fun `local files from an earlier install that are not here resolve as written`() {
        val resolver = resolverWithFiles()

        assertEquals("/install/old/media/a.jpg", resolver.filePath("file:///install/old/media/a.jpg"))
    }

    @Test
    fun `an unencoded name that looks escaped resolves to the file that exists`() {
        val resolver = resolverWithFiles("/install/new/media/a%20b.jpg")

        assertEquals("/install/new/media/a%20b.jpg", resolver.filePath("file:///install/new/media/a%20b.jpg"))
    }

    @Test
    fun `external references name no local file`() {
        val resolver = resolverWithFiles()

        assertNull(resolver.filePath("ph://ABC/L0/001"))
        assertNull(resolver.filePath("content://media/external/images/media/1"))
        assertNull(resolver.filePath("https://cloud.logdate.app/media/abc"))
    }

    @Test
    fun `files inside a collection are stored as owned references`() {
        val resolver = resolverWithFiles("/install/new/media/IMG 0001.jpg", "/install/new/audio_notes/r.m4a")

        assertEquals(
            "logdate-media://library/IMG%200001.jpg",
            resolver.storedReference("file:///install/new/media/IMG%200001.jpg"),
        )
        assertEquals(
            "logdate-media://library/IMG%200001.jpg",
            resolver.storedReference("file:///install/new/media/IMG 0001.jpg"),
        )
        assertEquals("logdate-media://recordings/r.m4a", resolver.storedReference("/install/new/audio_notes/r.m4a"))
    }

    @Test
    fun `files from an earlier install are stored as owned references when they are here`() {
        val resolver = resolverWithFiles("/install/new/media/a.jpg")

        assertEquals("logdate-media://library/a.jpg", resolver.storedReference("file:///install/old/media/a.jpg"))
    }

    @Test
    fun `files that are missing keep the reference they had`() {
        val resolver = resolverWithFiles()

        assertEquals("file:///install/old/media/a.jpg", resolver.storedReference("file:///install/old/media/a.jpg"))
        assertEquals("file:///install/new/media/a.jpg", resolver.storedReference("file:///install/new/media/a.jpg"))
    }

    @Test
    fun `files outside every collection keep the reference they had`() {
        val resolver = resolverWithFiles("/install/new/Caches/a.jpg", "/install/new/media")

        assertEquals("file:///install/new/Caches/a.jpg", resolver.storedReference("file:///install/new/Caches/a.jpg"))
        assertEquals("/install/new/media", resolver.storedReference("/install/new/media"))
    }

    @Test
    fun `aliases of a collection directory are recognized`() {
        val resolver = resolverWithFiles("/install/new/media/a.jpg")

        assertEquals("logdate-media://library/a.jpg", resolver.storedReference("/alias/install/new/media/a.jpg"))
    }

    @Test
    fun `owned references are stored in their canonical spelling`() {
        val resolver = resolverWithFiles()

        assertEquals("logdate-media://library/a%20b.jpg", resolver.storedReference("LOGDATE-MEDIA://Library/a%20b.jpg"))
    }

    @Test
    fun `external references are stored unchanged`() {
        val resolver = resolverWithFiles()

        assertEquals("ph://ABC/L0/001", resolver.storedReference("ph://ABC/L0/001"))
        assertEquals("https://cloud.logdate.app/media/abc", resolver.storedReference("https://cloud.logdate.app/media/abc"))
    }

    @Test
    fun `a file on this device reports how many bytes it takes`() {
        val file = Path(SystemTemporaryDirectory, "media-file-size-${Random.nextLong()}.bin")
        SystemFileSystem.sink(file).buffered().use { it.write(ByteArray(1_234)) }
        try {
            val resolver = MediaFileResolver(directories)

            assertEquals(1_234, resolver.fileSize(file.toString()))
        } finally {
            SystemFileSystem.delete(file)
        }
    }

    @Test
    fun `a file that is not on this device has no size`() {
        val resolver = MediaFileResolver(directories)

        assertNull(resolver.fileSize("logdate-media://library/objects/sha256/00/missing.jpg"))
        assertNull(resolver.fileSize("https://cloud.logdate.app/media/abc"))
    }

    private fun resolverWithFiles(vararg existing: String): MediaFileResolver {
        val files = existing.toSet()
        return MediaFileResolver(directories) { directories.canonicalPath(it) in files }
    }
}

/**
 * Directories for an install rooted at [installRoot]. Paths under `/install/<anything>` belong to
 * some install of the app; paths under `/alias` are another spelling of the same location.
 */
private class FakeInstallDirectories(
    private val installRoot: String,
) : MediaDirectories {
    override fun directory(collection: MediaCollection): String =
        when (collection) {
            MediaCollection.Library -> "$installRoot/media"
            MediaCollection.Recordings -> "$installRoot/audio_notes"
        }

    override fun canonicalPath(path: String): String = path.removePrefix("/alias")

    override fun pathInCurrentInstall(path: String): String? {
        if (!path.startsWith("/install/")) return null
        val withinInstall = path.removePrefix("/install/").substringAfter('/', missingDelimiterValue = "")
        if (withinInstall.isEmpty()) return null
        return "$installRoot/$withinInstall"
    }
}
