package app.logdate.feature.core.export

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CloudBackupArchiveCacheTest {
    private val identity = CloudArchiveCacheIdentity("https://cloud.example", "account", "identity", "all-v2", "source")

    private fun withCache(test: (File, CloudBackupArchiveCache) -> Unit) {
        val root = System.getenv("DISK_JANITOR_ARTIFACT_ROOT")?.let { File(it).toPath() }
        val directory =
            if (root != null) {
                Files.createTempDirectory(root, "archive-cache-test-").toFile()
            } else {
                Files.createTempDirectory("archive-cache-test-").toFile()
            }
        try {
            test(directory, CloudBackupArchiveCache(directory))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `a completed encrypted candidate survives worker replacement`() =
        withCache { directory, cache ->
            cache.encryptedPart.writeBytes(ByteArray(64) { it.toByte() })
            cache.finalize(identity)
            assertTrue(CloudBackupArchiveCache(directory).reusable(identity))
            assertFalse(cache.reusable(identity.copy(sourceFingerprint = "changed")))
            assertFalse(cache.reusable(identity.copy(identityFingerprint = "changed")))
            assertFalse(cache.reusable(identity.copy(accountId = "other")))
            assertFalse(cache.reusable(identity.copy(origin = "https://other.example")))
        }

    @Test
    fun `corrupt archive and truncated marker cannot authorize reuse`() =
        withCache { directory, cache ->
            cache.encryptedPart.writeBytes(ByteArray(64) { it.toByte() })
            cache.finalize(identity)
            cache.archive.appendBytes(byteArrayOf(1))
            assertFalse(cache.reusable(identity))
            File(directory, "cloud-backup-pending.scope").writeText("incomplete")
            assertFalse(cache.reusable(identity))
        }

    @Test
    fun `partial archives are never candidates and completed uploads are removed`() =
        withCache { _, cache ->
            cache.encryptedPart.writeBytes(ByteArray(64) { it.toByte() })
            assertFalse(cache.hasCandidate())
            cache.finalize(identity)
            cache.discard()
            assertFalse(cache.hasCandidate())
            assertFalse(cache.archive.exists())
            assertFalse(cache.encryptedPart.exists())
        }
}
