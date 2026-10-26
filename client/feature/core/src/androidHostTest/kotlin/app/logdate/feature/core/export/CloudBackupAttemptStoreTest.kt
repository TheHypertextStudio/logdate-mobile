package app.logdate.feature.core.export

import app.logdate.client.datastore.OriginBoundSession
import app.logdate.client.datastore.UserSession
import app.logdate.feature.core.settings.ui.ArchiveWorkState
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudBackupAttemptStoreTest {
    @Test
    fun `last attempt survives restart remains scoped and advances despite clock rollback`() {
        val root = System.getenv("DISK_JANITOR_ARTIFACT_ROOT")?.let { File(it).toPath() }
        val directory =
            if (root != null) {
                Files.createTempDirectory(root, "archive-attempt-test-").toFile()
            } else {
                Files.createTempDirectory("archive-attempt-test-").toFile()
            }
        try {
            val scope = OriginBoundSession("https://cloud.example", UserSession("access", "refresh", "account"))
            val store = CloudBackupAttemptStore(directory)
            val first = store.begin(scope, "failed").copy(state = ArchiveWorkState.FAILED, order = Long.MAX_VALUE / 2)
            store.write(scope, first)
            val next = CloudBackupAttemptStore(directory).begin(scope, "success")
            assertTrue(next.order > first.order)
            val completed = next.copy(state = ArchiveWorkState.SUCCEEDED, backupId = "backup", deviceId = "device")
            store.write(scope, completed)
            assertEquals(completed, CloudBackupAttemptStore(directory).read(scope))
            assertNull(store.read(scope.copy(origin = "https://other.example")))
            assertNull(store.read(scope.copy(session = scope.session.copy(accountId = "other"))))
        } finally {
            directory.deleteRecursively()
        }
    }
}
