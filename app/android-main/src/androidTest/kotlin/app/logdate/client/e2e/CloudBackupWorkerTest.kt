package app.logdate.client.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.logdate.client.datastore.UserSession
import app.logdate.client.device.crypto.IdentityKeyManager
import app.logdate.client.domain.export.archive.ArchiveCategory
import app.logdate.client.domain.export.archive.ArchiveContainer
import app.logdate.client.domain.export.archive.ArchiveCounts
import app.logdate.client.domain.export.archive.ArchiveExportProgress
import app.logdate.client.domain.export.archive.ArchiveExportSummary
import app.logdate.client.domain.export.archive.ArchiveOmission
import app.logdate.client.domain.export.archive.ArchiveOmissionReason
import app.logdate.client.domain.export.archive.ArchivePath
import app.logdate.client.domain.export.archive.ArchiveScope
import app.logdate.client.domain.export.archive.ExportArchiveUseCase
import app.logdate.client.sync.cloud.BackupUploadResult
import app.logdate.client.sync.cloud.CloudApiException
import app.logdate.feature.core.export.CloudArchiveCipher
import app.logdate.feature.core.export.CloudBackupWorker
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import okio.buffer
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class CloudBackupWorkerTest : CloudArchiveWorkerFixture() {
    @Test
    fun `uploads the sealed archive through the file streaming API`() =
        runTest {
            val cloud = FakeCloudBackupDataSource(Result.success(BackupUploadResult("backup", 1L, 1L)))
            val params = workerParameters()
            every { params.id } returns UUID.randomUUID()
            val worker =
                CloudBackupWorker(
                    context,
                    params,
                    v2Exporter("{\"schemaVersion\":\"2.0\"}"),
                    cloud,
                    FakeSessionStorage(UserSession("access", "refresh", "account")),
                    FakeDeviceIdProvider(),
                    verifiedIdentity,
                    archiveCipher,
                )

            assertTrue(worker.doWork() is androidx.work.ListenableWorker.Result.Success)
            assertEquals(1, cloud.fileUploadCalls)
            assertEquals(0, cloud.uploadCalls)
            assertEquals("LDCB2", cloud.uploadedFileHeader)
            assertTrue(cloud.uploadedFileSize > 17L)
            assertTrue(cloud.uploadedFileDigest?.isNotBlank() == true)
        }

    @Test
    fun `streams a one gibibyte archive through the Android backup worker`() =
        runTest {
            val payloadSize = 1L shl 30
            assertTrue(Runtime.getRuntime().maxMemory() < payloadSize)
            val exporter = mockk<ExportArchiveUseCase>()
            var exportCompleted = false
            every { exporter.export(any(), any()) } answers {
                val container = invocation.args[1] as ArchiveContainer
                flow {
                    container.write("manifest.json", "{\"schemaVersion\":\"2.0\"}")
                    container.entry(ArchivePath.of("payload.bin"), compress = false) { sink ->
                        val buffer = ByteArray(64 * 1024) { index -> (index * 31).toByte() }
                        val output = sink.buffer()
                        var remaining = payloadSize
                        while (remaining > 0) {
                            val count = minOf(buffer.size.toLong(), remaining).toInt()
                            output.write(buffer, 0, count)
                            remaining -= count
                        }
                        output.flush()
                    }
                    exportCompleted = true
                    emit(
                        ArchiveExportProgress.Completed(
                            ArchiveExportSummary(
                                ArchiveCounts(0, 0, 0, 0, 0, 0, hasProfile = false),
                                ArchiveScope(complete = true),
                                sourceFingerprint = "large-payload",
                            ),
                        ),
                    )
                }
            }
            val cloud =
                FakeCloudBackupDataSource(Result.success(BackupUploadResult("backup", 1L, 1L))).apply {
                    retainUploadedFileBytes = false
                }
            var cipherKeyRequests = 0
            val largeArchiveCipher =
                CloudArchiveCipher {
                    cipherKeyRequests++
                    ByteArray(32) { 7 }
                }
            val params = workerParameters()
            val workId = UUID.randomUUID()
            every { params.id } returns workId
            val worker =
                CloudBackupWorker(
                    context,
                    params,
                    exporter,
                    cloud,
                    FakeSessionStorage(UserSession("access", "refresh", "account")),
                    FakeDeviceIdProvider(),
                    verifiedIdentity,
                    largeArchiveCipher,
                )

            val result = worker.doWork()
            assertTrue(
                result is androidx.work.ListenableWorker.Result.Success,
                "workerResult=$result, exportCompleted=$exportCompleted, " +
                    "cipherKeyRequests=$cipherKeyRequests, fileUploadCalls=${cloud.fileUploadCalls}, " +
                    "uploadedSize=${cloud.uploadedFileSize}",
            )
            assertEquals(1, cloud.fileUploadCalls)
            assertEquals(0, cloud.uploadCalls)
            assertEquals(CloudArchiveCipher.MANIFEST, cloud.uploadedFileManifest)
            assertEquals("LDCB2", cloud.uploadedFileHeader)
            assertTrue(cloud.uploadedFileSize > payloadSize)
            assertTrue(cloud.uploadedFileDigest?.isNotBlank() == true)
            assertEquals(null, cloud.uploadedFileBytes)
            assertFalse(File(context.noBackupFilesDir, "cloud-backup-pending.zip").exists())
            assertFalse(File(context.noBackupFilesDir, "cloud-backup-pending.scope").exists())
        }

    @Test
    fun `keeps the sealed backup after a cancelled partial upload and reuses it on retry`() =
        runTest {
            val workId = UUID.randomUUID()
            var interruptedUploadBytes = 0
            val cloud =
                FakeCloudBackupDataSource(Result.success(BackupUploadResult("backup", 1L, 1L))).apply {
                    fileUploadInterruption = { source ->
                        source.inputStream().use { interruptedUploadBytes = it.readNBytes(16).size }
                        throw CancellationException("upload cancelled after a partial read")
                    }
                }
            val params = workerParameters()
            every { params.id } returns workId
            val worker =
                CloudBackupWorker(
                    context,
                    params,
                    v2Exporter("{\"schemaVersion\":\"2.0\"}"),
                    cloud,
                    FakeSessionStorage(UserSession("access", "refresh", "account")),
                    FakeDeviceIdProvider(),
                    verifiedIdentity,
                    archiveCipher,
                )

            assertFailsWith<CancellationException> { worker.doWork() }
            val archive = File(context.noBackupFilesDir, "cloud-backup-pending.zip")
            val digestBeforeRetry = archive.sha256()
            assertEquals(16, interruptedUploadBytes)
            assertTrue(archive.length() > interruptedUploadBytes, "the first upload must stop before EOF")
            assertTrue(archive.isFile)
            assertTrue(File(context.noBackupFilesDir, "cloud-backup-pending.scope").isFile)
            assertFalse(File(context.noBackupFilesDir, "cloud-backup-$workId.plain.part").exists())
            assertFalse(File(context.noBackupFilesDir, "cloud-backup-$workId.encrypted.part").exists())

            assertTrue(worker.doWork() is androidx.work.ListenableWorker.Result.Success)
            assertEquals(digestBeforeRetry, cloud.uploadedFileDigest)
            assertEquals(2, cloud.fileUploadCalls)
            assertFalse(archive.exists())
        }

    @Test
    fun `retry reuses the exact encrypted archive for the same work and account`() =
        runTest {
            val cloud = FakeCloudBackupDataSource(Result.failure(IllegalStateException("offline")))
            val params = workerParameters()
            val workId = UUID.randomUUID()
            every { params.id } returns workId
            val worker =
                CloudBackupWorker(
                    context,
                    params,
                    v2Exporter("{\"schemaVersion\":\"2.0\"}"),
                    cloud,
                    FakeSessionStorage(UserSession("access", "refresh", "account")),
                    FakeDeviceIdProvider(),
                    verifiedIdentity,
                    archiveCipher,
                )

            assertTrue(worker.doWork() is androidx.work.ListenableWorker.Result.Retry)
            val firstDigest = cloud.uploadedFileDigest
            assertTrue(File(context.noBackupFilesDir, "cloud-backup-pending.zip").exists())
            cloud.uploadResult = Result.success(BackupUploadResult("backup", 2L, 1L))

            assertTrue(worker.doWork() is androidx.work.ListenableWorker.Result.Success)
            assertEquals(firstDigest, cloud.uploadedFileDigest)
            assertEquals(2, cloud.fileUploadCalls)
            assertFalse(File(context.noBackupFilesDir, "cloud-backup-pending.zip").exists())
            assertFalse(File(context.noBackupFilesDir, "cloud-backup-pending.scope").exists())
        }

    @Test
    fun `expired upload token refreshes and retries the same encrypted file`() =
        runTest {
            val cloud =
                FakeCloudBackupDataSource(Result.failure(IllegalStateException("offline"))).apply {
                    uploadResults += Result.failure(CloudApiException("UNAUTHORIZED", "expired", 401))
                    uploadResults += Result.success(BackupUploadResult("backup", 1L, 1L))
                    refreshResults += Result.success("refreshed-access")
                }
            val session = FakeSessionStorage(UserSession("access", "refresh", "account"))
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

            assertTrue(worker.doWork() is androidx.work.ListenableWorker.Result.Success)
            assertEquals(listOf("access", "refreshed-access"), cloud.uploadAccessTokens)
            assertEquals(1, cloud.refreshCalls)
            assertEquals(2, cloud.uploadedFileDigests.size)
            assertEquals(cloud.uploadedFileDigests.first(), cloud.uploadedFileDigests.last())
            assertEquals("refreshed-access", session.getSession()?.accessToken)
        }

    @Test
    fun `uploads completed export and deletes private archive only after success`() =
        runTest {
            val manifest = "{\"format\":\"logdate-export\",\"schemaVersion\":\"2.0\"}"
            val useCase = v2Exporter(manifest)
            val cloud = FakeCloudBackupDataSource(Result.success(BackupUploadResult("backup", 1L, 1L)))
            val session = FakeSessionStorage(UserSession("access", "refresh", "account"))
            val deviceId = FakeDeviceIdProvider()
            val params = workerParameters()
            val workId = UUID.randomUUID()
            every { params.id } returns workId
            val worker =
                CloudBackupWorker(context, params, useCase, cloud, session, deviceId, verifiedIdentity, archiveCipher)

            worker.doWork()
            assertEquals(0, cloud.uploadCalls)
            assertEquals(1, cloud.fileUploadCalls)
            assertEquals(CloudArchiveCipher.MANIFEST, cloud.uploadedFileManifest)
            val uploaded = requireNotNull(cloud.uploadedFileBytes)
            assertEquals("LDCB2", uploaded.copyOfRange(0, 5).decodeToString())
            val sealed = File(context.cacheDir, "cloud-backup-cipher-test.encrypted").apply { writeBytes(uploaded) }
            val restored = File(context.cacheDir, "cloud-backup-cipher-test.zip")
            archiveCipher.decrypt(sealed, restored)
            val entries =
                ZipInputStream(ByteArrayInputStream(restored.readBytes())).use { zip ->
                    buildMap {
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            put(entry.name, zip.readBytes())
                        }
                    }
                }
            assertEquals(manifest, entries.getValue("manifest.json").decodeToString())
            assertFalse("metadata.json" in entries)
            sealed.delete()
            restored.delete()
            assertFalse(File(context.noBackupFilesDir, "cloud-backup-pending.zip").exists())
            assertFalse(File(context.noBackupFilesDir, "cloud-backup-pending.scope").exists())
        }

    @Test
    fun `retains only the sealed archive when upload fails and WorkManager retries`() =
        runTest {
            val useCase = v2Exporter("{\"schemaVersion\":\"2.0\"}")
            val cloud = FakeCloudBackupDataSource(Result.failure(IllegalStateException("offline")))
            val params = workerParameters()
            val workId = UUID.randomUUID()
            every { params.id } returns workId
            val worker =
                CloudBackupWorker(
                    context,
                    params,
                    useCase,
                    cloud,
                    FakeSessionStorage(UserSession("access", "refresh", "account")),
                    FakeDeviceIdProvider(),
                    verifiedIdentity,
                    archiveCipher,
                )

            assertTrue(worker.doWork() is androidx.work.ListenableWorker.Result.Retry)
            assertTrue(File(context.noBackupFilesDir, "cloud-backup-pending.zip").exists())
            assertTrue(
                context.noBackupFilesDir
                    .listFiles()
                    .orEmpty()
                    .none { it.name.endsWith(".plain.part") },
            )
            File(context.noBackupFilesDir, "cloud-backup-pending.zip").delete()
            File(context.noBackupFilesDir, "cloud-backup-pending.scope").delete()
        }

    @Test
    fun `does not upload archive that omitted unreadable media`() =
        runTest {
            val useCase =
                v2Exporter(
                    "{\"schemaVersion\":\"2.0\"}",
                    ArchiveScope(
                        complete = false,
                        omitted = listOf(ArchiveOmission(ArchiveCategory.MEDIA, ArchiveOmissionReason.UNREADABLE)),
                    ),
                )
            val cloud = FakeCloudBackupDataSource(Result.success(BackupUploadResult("unused", 1L, 1L)))
            val params = workerParameters()
            every { params.id } returns UUID.randomUUID()
            val worker =
                CloudBackupWorker(
                    context,
                    params,
                    useCase,
                    cloud,
                    FakeSessionStorage(UserSession("access", "refresh", "account")),
                    FakeDeviceIdProvider(),
                    verifiedIdentity,
                    archiveCipher,
                )

            assertTrue(worker.doWork() is androidx.work.ListenableWorker.Result.Failure)
            assertEquals(0, cloud.uploadCalls)
            assertEquals(0, cloud.fileUploadCalls)
            assertTrue(
                context.filesDir
                    .listFiles()
                    .orEmpty()
                    .none { it.name.startsWith("cloud-backup-") },
            )
        }

    @Test
    fun `skips without authenticated session`() =
        runTest {
            val cloud = FakeCloudBackupDataSource(Result.success(BackupUploadResult("unused", 1L, 1L)))
            val params = workerParameters()
            every { params.id } returns UUID.randomUUID()
            val worker =
                CloudBackupWorker(
                    context,
                    params,
                    mockk(relaxed = true),
                    cloud,
                    FakeSessionStorage(null),
                    FakeDeviceIdProvider(),
                    verifiedIdentity,
                    archiveCipher,
                )

            assertTrue(worker.doWork() is androidx.work.ListenableWorker.Result.Success)
            assertTrue(cloud.uploadCalls == 0)
        }

    @Test
    fun backupWithExistingIdentityDoesNotRequirePhraseVerification() =
        runTest {
            val cloud = FakeCloudBackupDataSource(Result.success(BackupUploadResult("unused", 1L, 1L)))
            val unverifiedIdentity =
                mockk<IdentityKeyManager> {
                    coEvery { isRecoveryPhraseVerified() } returns false
                    coEvery { hasIdentityKey() } returns true
                    coEvery { canUseForAccount(any()) } returns true
                    coEvery { getIdentityKey() } returns ByteArray(32) { 7 }
                }
            val params = workerParameters()
            every { params.id } returns UUID.randomUUID()
            val worker =
                CloudBackupWorker(
                    context,
                    params,
                    v2Exporter("{\"schemaVersion\":\"2.0\"}"),
                    cloud,
                    FakeSessionStorage(UserSession("access", "refresh", "account")),
                    FakeDeviceIdProvider(),
                    unverifiedIdentity,
                    archiveCipher,
                )

            assertTrue(worker.doWork() is androidx.work.ListenableWorker.Result.Success)
            assertEquals(0, cloud.uploadCalls)
            assertEquals(1, cloud.fileUploadCalls)
            assertEquals("LDCB2", cloud.uploadedFileHeader)
        }

    @Test
    fun backupWithoutIdentityWaitsForTrustedDevice() =
        runTest {
            val cloud = FakeCloudBackupDataSource(Result.success(BackupUploadResult("unused", 1L, 1L)))
            val missingIdentity =
                mockk<IdentityKeyManager> {
                    coEvery { isRecoveryPhraseVerified() } returns false
                    coEvery { hasIdentityKey() } returns false
                }
            val params = workerParameters()
            every { params.id } returns UUID.randomUUID()
            val worker =
                CloudBackupWorker(
                    context,
                    params,
                    mockk(relaxed = true),
                    cloud,
                    FakeSessionStorage(UserSession("access", "refresh", "account")),
                    FakeDeviceIdProvider(),
                    missingIdentity,
                    archiveCipher,
                )
            assertTrue(worker.doWork() is androidx.work.ListenableWorker.Result.Retry)
            assertEquals(0, cloud.fileUploadCalls)
            assertEquals(0, cloud.uploadCalls)
        }
}
