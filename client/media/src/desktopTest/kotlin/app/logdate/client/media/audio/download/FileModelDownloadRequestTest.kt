package app.logdate.client.media.audio.download

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileModelDownloadRequestTest {
    @Test
    fun requestedDownloadSurvivesStoreRecreationUntilCompletion() {
        val root = Files.createTempDirectory("download-request").toFile()
        try {
            val path = root.resolve("models/requested").path
            val first = FileModelDownloadRequest(path)
            assertFalse(first.isRequested())
            assertFalse(root.resolve("models").exists())
            first.setRequested(true)
            val reopened = FileModelDownloadRequest(path)
            assertTrue(reopened.isRequested())
            reopened.setRequested(false)
            assertFalse(FileModelDownloadRequest(path).isRequested())
        } finally {
            root.deleteRecursively()
        }
    }
}
