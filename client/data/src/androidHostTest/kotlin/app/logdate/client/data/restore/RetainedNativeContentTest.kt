package app.logdate.client.data.restore

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RetainedNativeContentTest {
    @Test
    fun `a partial delivery of originals or recovery keys requires local-data consent`() {
        val root = Files.createTempDirectory("native-content-probe").toFile()
        try {
            val database = root.resolve("databases/logdate")
            assertFalse(hasRetainedNativeContent(root, database, preferencesOf()))
            for (name in listOf(
                "media",
                "user_media",
                "audio_notes",
                "camera-captures",
                "db_recovery",
                "identity_recovery",
                "identity_recovery_device",
                "datastore",
            )) {
                val retained = root.resolve(name)
                retained.mkdir()
                assertTrue(hasRetainedNativeContent(root, database, preferencesOf()), name)
                retained.delete()
            }
            assertTrue(hasRetainedNativeContent(root, database, preferencesOf(stringPreferencesKey("editor_drafts") to "[]")))
        } finally {
            root.deleteRecursively()
        }
    }
}
