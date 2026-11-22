package app.logdate.wear.sync.v2.removal

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchRemovalFilesTest {
    @Test
    fun `erases legacy media and recovery phrase alongside current watch files`() {
        val root = Files.createTempDirectory("watch-removal-files").toFile()
        try {
            val files = root.resolve("files").apply { mkdirs() }
            val cache = root.resolve("cache").apply { mkdirs() }
            val retained =
                listOf(
                    "audio_notes/capture.m4a",
                    "media/photo.jpg",
                    "synced_audio/playback.m4a",
                    "user_media/legacy.jpg",
                    "identity_recovery/phrase_backup",
                    "identity_recovery_device/account_key_backup",
                ).map { name ->
                    files.resolve(name).apply {
                        parentFile.mkdirs()
                        writeText("private")
                    }
                }
            val cached =
                cache.resolve("watch-window-bodies/body").apply {
                    parentFile.mkdirs()
                    writeText("private")
                }
            val databaseRecovery =
                files.resolve("db_recovery/passphrase_backup").apply {
                    parentFile.mkdirs()
                    writeText("database key")
                }

            clearWatchFiles(files, cache)

            retained.forEach { assertFalse(it.exists(), "Still retained: ${it.path}") }
            assertFalse(cached.exists())
            assertTrue(databaseRecovery.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
