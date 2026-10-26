package app.logdate.client.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.logdate.client.datastore.UserSession
import app.logdate.client.sync.cloud.BackupFile
import app.logdate.client.sync.cloud.BackupMetadata
import app.logdate.client.sync.cloud.BackupUploadResult
import app.logdate.client.sync.cloud.CloudApiException
import app.logdate.feature.core.export.CloudArchiveCipher
import app.logdate.feature.core.export.CloudBackupWorker
import app.logdate.feature.core.restore.CloudRestoreWorker
import io.mockk.every
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class CloudRestoreWorkerTest : CloudArchiveWorkerFixture() {
    @Test
    fun `downloads and authenticates a one gibibyte archive through the Android restore worker`() =
        runTest {
            val payloadSize = 1L shl 30
            assertTrue(Runtime.getRuntime().maxMemory() < payloadSize)
            val source = File(context.cacheDir, "cloud-restore-1g-source.zip")
            val encrypted = File(context.cacheDir, "cloud-restore-1g-source.encrypted")
            createLargeArchive(source, payloadSize)
            val expectedSize = source.length()
            val expectedDigest = source.sha256()
            archiveCipher.encrypt(source, encrypted)
            source.delete()

            val backup =
                BackupMetadata(
                    id = "large-backup",
                    deviceId = "device",
                    manifest = CloudArchiveCipher.MANIFEST,
                    createdAt = 20L,
                    sizeBytes = encrypted.length(),
                    downloadUrl = "https://unused",
                )
            val cloud =
                FakeCloudBackupDataSource(Result.success(BackupUploadResult("unused", 1L, 1L))).apply {
                    backups = listOf(backup)
                    downloadedFile = encrypted
                }
            val params = workerParameters()
            every { params.id } returns UUID.randomUUID()
            var restored: File? = null
            val worker =
                CloudRestoreWorker(
                    context,
                    params,
                    cloud,
                    FakeSessionStorage(UserSession("access", "refresh", "account")),
                    archiveCipher,
                ) { archive -> restored = archive }

            try {
                assertTrue(worker.doWork() is androidx.work.ListenableWorker.Result.Success)
                assertEquals(1, cloud.fileDownloadCalls)
                assertEquals(expectedSize, requireNotNull(restored).length())
                assertEquals(expectedDigest, requireNotNull(restored).sha256())
                ZipFile(requireNotNull(restored)).use { zip ->
                    assertEquals(payloadSize, zip.getEntry("payload.bin")?.size)
                    assertEquals(
                        "{\"schemaVersion\":\"2.0\"}",
                        zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().use { it.readText() },
                    )
                }
            } finally {
                source.delete()
                encrypted.delete()
                restored?.delete()
            }
        }

    @Test
    fun `retries a restore after a mid-download no-space failure and removes partial files`() =
        runTest {
            val workId = UUID.randomUUID()
            val backup = restoreBackupMetadata("no-space")
            val cloud =
                FakeCloudBackupDataSource(Result.success(BackupUploadResult("unused", 1L, 1L))).apply {
                    backups = listOf(backup)
                    fileDownloadOverride = { destination ->
                        destination.writeBytes(byteArrayOf(0x4c, 0x44, 0x43, 0x42, 0x32))
                        Result.failure(IOException("No space left on device"))
                    }
                }
            val params = workerParameters()
            every { params.id } returns workId
            var handoffCalled = false
            val worker =
                CloudRestoreWorker(
                    context,
                    params,
                    cloud,
                    FakeSessionStorage(UserSession("access", "refresh", "account")),
                    archiveCipher,
                ) { handoffCalled = true }

            assertTrue(worker.doWork() is androidx.work.ListenableWorker.Result.Retry)
            assertEquals(1, cloud.fileDownloadCalls)
            assertFalse(handoffCalled)
            assertRestoreArtifactsRemoved(workId)
        }

    @Test
    fun `propagates restore download cancellation and removes partial files`() =
        runTest {
            val workId = UUID.randomUUID()
            val backup = restoreBackupMetadata("cancelled-download")
            val cloud =
                FakeCloudBackupDataSource(Result.success(BackupUploadResult("unused", 1L, 1L))).apply {
                    backups = listOf(backup)
                    fileDownloadOverride = { destination ->
                        destination.writeBytes(byteArrayOf(0x4c, 0x44, 0x43, 0x42, 0x32))
                        throw CancellationException("download cancelled after a partial write")
                    }
                }
            val params = workerParameters()
            every { params.id } returns workId
            var handoffCalled = false
            val worker =
                CloudRestoreWorker(
                    context,
                    params,
                    cloud,
                    FakeSessionStorage(UserSession("access", "refresh", "account")),
                    archiveCipher,
                ) { handoffCalled = true }

            assertFailsWith<CancellationException> { worker.doWork() }
            assertEquals(1, cloud.fileDownloadCalls)
            assertFalse(handoffCalled)
            assertRestoreArtifactsRemoved(workId)
        }

    @Test
    fun `expired restore tokens refresh for listing and streamed download`() =
        runTest {
            val source =
                File(context.cacheDir, "cloud-refresh-restore-source.zip").apply {
                    writeBytes(byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 1, 2, 3))
                }
            val encryptedArchive = archiveCipher.encrypt(source)
            source.delete()
            val backup = BackupMetadata("backup", "device", CloudArchiveCipher.MANIFEST, 20L, 64L, "https://unused")
            val cloud =
                FakeCloudBackupDataSource(Result.success(BackupUploadResult("unused", 1L, 1L))).apply {
                    backups = listOf(backup)
                    downloaded = BackupFile("device", CloudArchiveCipher.MANIFEST, encryptedArchive)
                    listResults += Result.failure(CloudApiException("UNAUTHORIZED", "expired", 401))
                    listResults += Result.success(backups)
                    fileDownloadResults += Result.failure(CloudApiException("UNAUTHORIZED", "expired", 401))
                    fileDownloadResults += Result.success(backup)
                    refreshResults += Result.success("access-after-list-refresh")
                    refreshResults += Result.success("access-after-download-refresh")
                }
            val session = FakeSessionStorage(UserSession("access", "refresh", "account"))
            var enqueued: File? = null
            val params = workerParameters()
            every { params.id } returns UUID.randomUUID()
            val worker =
                CloudRestoreWorker(
                    context,
                    params,
                    cloud,
                    session,
                    archiveCipher,
                ) { archive -> enqueued = archive }

            assertTrue(worker.doWork() is androidx.work.ListenableWorker.Result.Success)
            assertEquals(listOf("access", "access-after-list-refresh"), cloud.listAccessTokens)
            assertEquals(listOf("access-after-list-refresh", "access-after-download-refresh"), cloud.downloadAccessTokens)
            assertEquals(2, cloud.refreshCalls)
            assertEquals("access-after-download-refresh", session.getSession()?.accessToken)
            assertTrue(enqueued?.readBytes()?.contentEquals(byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 1, 2, 3)) == true)
            requireNotNull(enqueued).delete()
        }

    @Test
    fun `token refresh does not overwrite a session after account changes`() =
        runTest {
            val cloud =
                FakeCloudBackupDataSource(Result.failure(IllegalStateException("offline"))).apply {
                    uploadResults += Result.failure(CloudApiException("UNAUTHORIZED", "expired", 401))
                    refreshResults += Result.success("must-not-be-saved")
                }
            val session = FakeSessionStorage(UserSession("access", "refresh", "account"))
            cloud.onRefresh = { session.changeSession(UserSession("other-access", "other-refresh", "other-account")) }
            val params = workerParameters()
            every { params.id } returns UUID.randomUUID()
            val worker =
                CloudBackupWorker(
                    context,
                    params,
                    v2Exporter("{\"schemaVersion\":\"2.0\"}"),
                    cloud,
                    session,
                    FakeDeviceIdProvider(),
                    verifiedIdentity,
                    archiveCipher,
                )

            assertTrue(worker.doWork() is androidx.work.ListenableWorker.Result.Retry)
            assertEquals(listOf("access"), cloud.uploadAccessTokens)
            assertEquals("other-access", session.getSession()?.accessToken)
            assertEquals("other-account", session.getSession()?.accountId)
        }

    @Test
    fun `cloud restore downloads newest backup and enqueues normal restore`() =
        runTest {
            val plainArchive =
                File(context.cacheDir, "cloud-restore-source.zip").apply {
                    writeBytes(byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 1, 2, 3))
                }
            val encryptedArchive = archiveCipher.encrypt(plainArchive)
            plainArchive.delete()
            val newest =
                BackupMetadata(
                    id = "newest",
                    deviceId = "device",
                    manifest = CloudArchiveCipher.MANIFEST,
                    createdAt = 20L,
                    sizeBytes = 3L,
                    downloadUrl = "https://unused",
                )
            val cloud =
                FakeCloudBackupDataSource(Result.success(BackupUploadResult("unused", 1L, 1L))).apply {
                    backups =
                        listOf(
                            newest.copy(createdAt = 10L),
                            newest,
                        )
                    downloaded = BackupFile("device", CloudArchiveCipher.MANIFEST, encryptedArchive)
                }
            var enqueued: File? = null
            val params = workerParameters()
            every { params.id } returns UUID.randomUUID()
            val worker =
                CloudRestoreWorker(
                    context,
                    params,
                    cloud,
                    FakeSessionStorage(UserSession("access", "refresh", "account")),
                    archiveCipher,
                ) { archive -> enqueued = archive }

            assertTrue(worker.doWork() is androidx.work.ListenableWorker.Result.Success)
            assertTrue(cloud.downloadedBackupId == "newest")
            assertEquals(1, cloud.fileDownloadCalls)
            val expected = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 1, 2, 3)
            assertTrue(enqueued?.readBytes()?.contentEquals(expected) == true)
            enqueued.delete()
        }

    @Test
    fun `cloud restore retries and removes decrypted archive when handoff fails`() =
        runTest {
            val plainArchive =
                File(context.cacheDir, "cloud-restore-failed-handoff-source.zip").apply {
                    writeBytes(byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 1, 2, 3))
                }
            val encryptedArchive = archiveCipher.encrypt(plainArchive)
            plainArchive.delete()
            val backup =
                BackupMetadata(
                    id = "latest",
                    deviceId = "device",
                    manifest = CloudArchiveCipher.MANIFEST,
                    createdAt = 20L,
                    sizeBytes = 3L,
                    downloadUrl = "https://unused",
                )
            val cloud =
                FakeCloudBackupDataSource(Result.success(BackupUploadResult("unused", 1L, 1L))).apply {
                    backups = listOf(backup)
                    downloaded = BackupFile("device", CloudArchiveCipher.MANIFEST, encryptedArchive)
                }
            val params = workerParameters()
            every { params.id } returns UUID.randomUUID()
            var decryptedArchive: File? = null
            val worker =
                CloudRestoreWorker(
                    context,
                    params,
                    cloud,
                    FakeSessionStorage(UserSession("access", "refresh", "account")),
                    archiveCipher,
                ) { archive ->
                    decryptedArchive = archive
                    error("WorkManager enqueue failed")
                }

            assertTrue(worker.doWork() is androidx.work.ListenableWorker.Result.Retry)
            assertFalse(decryptedArchive?.exists() == true)
        }

    @Test
    fun `cloud restore streams and preserves legacy server encrypted zip`() =
        runTest {
            val legacyZip = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4, 5)
            val backup = BackupMetadata("legacy", "device", "legacy-manifest", 20L, legacyZip.size.toLong(), "https://unused")
            val cloud =
                FakeCloudBackupDataSource(Result.success(BackupUploadResult("unused", 1L, 1L))).apply {
                    backups = listOf(backup)
                    downloaded = BackupFile("device", "legacy-manifest", legacyZip)
                }
            var enqueued: File? = null
            val params = workerParameters()
            every { params.id } returns UUID.randomUUID()
            val worker =
                CloudRestoreWorker(
                    context,
                    params,
                    cloud,
                    FakeSessionStorage(UserSession("access", "refresh", "account")),
                    archiveCipher,
                ) { archive -> enqueued = archive }

            assertTrue(worker.doWork() is androidx.work.ListenableWorker.Result.Success)
            assertEquals(1, cloud.fileDownloadCalls)
            assertTrue(enqueued?.readBytes()?.contentEquals(legacyZip) == true)
            requireNotNull(enqueued).delete()
        }
}
