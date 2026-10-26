package app.logdate.client.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.logdate.client.datastore.UserSession
import app.logdate.client.sync.cloud.BackupUploadResult
import app.logdate.feature.core.export.CloudBackupWorker
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class CloudBackupReuseProgressTest : CloudArchiveWorkerFixture() {
    @Test
    fun `replacement request reuses an unfinished encrypted archive`() =
        runTest {
            val exporter = v2Exporter("{\"schemaVersion\":\"2.0\"}")
            val cloud = FakeCloudBackupDataSource(Result.failure(IOException("offline")))
            val session = FakeSessionStorage(UserSession("access", "refresh", "account"))

            fun worker() =
                CloudBackupWorker(
                    context,
                    workerParameters(),
                    exporter,
                    cloud,
                    session,
                    FakeDeviceIdProvider(),
                    verifiedIdentity,
                    archiveCipher,
                )
            assertTrue(worker().doWork() is androidx.work.ListenableWorker.Result.Retry)
            val retainedDigest = cloud.uploadedFileDigest
            cloud.uploadResult = Result.success(BackupUploadResult("backup", 1L, 1L))
            assertTrue(worker().doWork() is androidx.work.ListenableWorker.Result.Success)
            verify(exactly = 1) { exporter.export(any(), any()) }
            assertEquals(retainedDigest, cloud.uploadedFileDigest)
            assertFalse(File(context.noBackupFilesDir, "cloud-backup-pending.zip").exists())
        }

    @Test
    fun `worker reports real stages and leaves upload progress indeterminate`() =
        runTest {
            val cloud = FakeCloudBackupDataSource(Result.success(BackupUploadResult("backup", 1L, 1L)))
            val worker =
                CloudBackupWorker(
                    context,
                    workerParameters(),
                    v2Exporter("{}"),
                    cloud,
                    FakeSessionStorage(UserSession("access", "refresh", "account")),
                    FakeDeviceIdProvider(),
                    verifiedIdentity,
                    archiveCipher,
                )
            assertTrue(worker.doWork() is androidx.work.ListenableWorker.Result.Success)
            val stages = archiveProgress.map { it.getString(CloudBackupWorker.PROGRESS_STAGE) }
            assertTrue("EXPORTING" in stages && "ENCRYPTING" in stages && "UPLOADING" in stages)
            val upload = archiveProgress.last { it.getString(CloudBackupWorker.PROGRESS_STAGE) == "UPLOADING" }
            assertTrue(upload.getFloat(CloudBackupWorker.PROGRESS_FRACTION, Float.NaN).isNaN())
        }
}
