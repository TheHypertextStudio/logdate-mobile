package app.logdate.client.e2e

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkerParameters
import app.logdate.client.datastore.OriginBoundSession
import app.logdate.client.datastore.SessionStorage
import app.logdate.client.datastore.UserSession
import app.logdate.client.device.crypto.IdentityKeyManager
import app.logdate.client.device.identity.DeviceIdProvider
import app.logdate.client.domain.export.archive.ArchiveContainer
import app.logdate.client.domain.export.archive.ArchiveCounts
import app.logdate.client.domain.export.archive.ArchiveExportProgress
import app.logdate.client.domain.export.archive.ArchiveExportSummary
import app.logdate.client.domain.export.archive.ArchivePath
import app.logdate.client.domain.export.archive.ArchiveScope
import app.logdate.client.domain.export.archive.ExportArchiveUseCase
import app.logdate.client.sync.cloud.BackupFile
import app.logdate.client.sync.cloud.BackupMetadata
import app.logdate.client.sync.cloud.BackupUploadFileRequest
import app.logdate.client.sync.cloud.BackupUploadResult
import app.logdate.client.sync.cloud.CloudBackupDataSource
import app.logdate.feature.core.export.CloudArchiveCipher
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.io.files.Path
import okio.buffer
import java.io.File
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertFalse
import kotlin.uuid.Uuid

abstract class CloudArchiveWorkerFixture {
    protected val context = ApplicationProvider.getApplicationContext<Context>()
    protected val archiveCipher = CloudArchiveCipher { ByteArray(32) { 7 } }
    protected val verifiedIdentity =
        mockk<IdentityKeyManager> {
            coEvery { isRecoveryPhraseVerified() } returns true
            coEvery { hasIdentityKey() } returns true
            coEvery { canUseForAccount(any()) } returns true
            coEvery { getIdentityKey() } returns ByteArray(32) { 7 }
        }

    protected val archiveProgress = mutableListOf<androidx.work.Data>()

    @org.junit.Before
    @org.junit.After
    fun clearOwnedBackupTestArtifacts() {
        listOf(".zip", ".scope", ".scope.part", ".plain.part", ".encrypted.part").forEach { suffix ->
            File(context.noBackupFilesDir, "cloud-backup-pending$suffix").delete()
        }
    }

    protected fun workerParameters(): WorkerParameters =
        mockk<WorkerParameters>(relaxed = true).also { params ->
            every { params.id } returns UUID.randomUUID()
            every { params.progressUpdater.updateProgress(any(), any(), any()) } answers {
                archiveProgress += thirdArg<androidx.work.Data>()
                androidx.work.impl.utils.futures.SettableFuture
                    .create<Void>()
                    .apply { set(null) }
            }
        }

    protected fun v2Exporter(
        manifest: String,
        scope: ArchiveScope = ArchiveScope(complete = true),
    ): ExportArchiveUseCase =
        mockk<ExportArchiveUseCase>().also { useCase ->
            coEvery { useCase.sourceFingerprint(any()) } returns "test-content:$manifest"
            every { useCase.export(any(), any()) } answers {
                val container = invocation.args[1] as ArchiveContainer
                flow {
                    emit(ArchiveExportProgress.Starting)
                    container.write("README.txt", "LogDate export")
                    container.write("manifest.json", manifest)
                    container.write("SHA256SUMS", "checksums")
                    emit(
                        ArchiveExportProgress.Completed(
                            ArchiveExportSummary(
                                ArchiveCounts(0, 0, 0, 0, 0, 0, hasProfile = false),
                                scope,
                                sourceFingerprint = "test-content:$manifest",
                            ),
                        ),
                    )
                }
            }
        }

    protected fun createLargeArchive(
        destination: File,
        payloadSize: Long,
    ) {
        ZipOutputStream(destination.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("{\"schemaVersion\":\"2.0\"}".encodeToByteArray())
            zip.closeEntry()
            zip.setLevel(Deflater.NO_COMPRESSION)
            zip.putNextEntry(ZipEntry("payload.bin"))
            val buffer = ByteArray(64 * 1024) { index -> (index * 31).toByte() }
            var remaining = payloadSize
            while (remaining > 0) {
                val count = minOf(buffer.size.toLong(), remaining).toInt()
                zip.write(buffer, 0, count)
                remaining -= count
            }
            zip.closeEntry()
        }
    }

    protected fun restoreBackupMetadata(id: String) =
        BackupMetadata(
            id = id,
            deviceId = "device",
            manifest = CloudArchiveCipher.MANIFEST,
            createdAt = 20L,
            sizeBytes = 5L,
            downloadUrl = "https://unused",
        )

    protected fun assertRestoreArtifactsRemoved(workId: UUID) {
        listOf(
            File(context.noBackupFilesDir, "cloud-restore-$workId.encrypted.part"),
            File(context.noBackupFilesDir, "cloud-restore-$workId.plain.part"),
            File(context.noBackupFilesDir, "cloud-restore-$workId.zip"),
        ).forEach { file -> assertFalse(file.exists(), "Expected ${file.name} to be removed") }
    }

    protected fun File.sha256(): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    protected fun ArchiveContainer.write(
        path: String,
        text: String,
    ) {
        entry(ArchivePath.of(path)) { sink ->
            sink.buffer().apply { writeUtf8(text) }.flush()
        }
    }

    protected class FakeSessionStorage(
        private var session: UserSession?,
    ) : SessionStorage {
        override fun getSession() = session

        override fun getOriginBoundSession() = session?.let { OriginBoundSession("https://sync.example", it) }

        override fun getSessionFlow() = MutableStateFlow(session)

        override suspend fun hasValidSession() = session != null

        override suspend fun saveSession(session: UserSession) {
            this.session = session
        }

        override suspend fun clearSession() {
            session = null
        }

        override suspend fun replaceSessionIfCurrent(
            expected: OriginBoundSession,
            updated: UserSession,
        ): Boolean {
            if (getOriginBoundSession() != expected || updated.accountId != expected.session.accountId) return false
            session = updated
            return true
        }

        fun changeSession(updated: UserSession) {
            session = updated
        }
    }

    protected class FakeDeviceIdProvider : DeviceIdProvider {
        protected val id = MutableStateFlow(Uuid.parse("00000000-0000-0000-0000-000000000001"))

        override fun getDeviceId() = id

        override suspend fun refreshDeviceId() = Unit
    }

    protected class FakeCloudBackupDataSource(
        var uploadResult: Result<BackupUploadResult>,
    ) : CloudBackupDataSource {
        val uploadResults = mutableListOf<Result<BackupUploadResult>>()
        val listResults = mutableListOf<Result<List<BackupMetadata>>>()
        val fileDownloadResults = mutableListOf<Result<BackupMetadata>>()
        val refreshResults = mutableListOf<Result<String>>()
        val uploadAccessTokens = mutableListOf<String>()
        val uploadedFileDigests = mutableListOf<String>()
        val listAccessTokens = mutableListOf<String>()
        val downloadAccessTokens = mutableListOf<String>()
        var refreshCalls: Int = 0
        var onRefresh: (() -> Unit)? = null
        var uploadCalls: Int = 0
        var fileUploadCalls: Int = 0
        var uploadedFileHeader: String? = null
        var uploadedFileSize: Long = 0L
        var uploadedFileDigest: String? = null
        var uploadedFileManifest: String? = null
        var uploadedFileBytes: ByteArray? = null
        var retainUploadedFileBytes: Boolean = true
        var backups: List<BackupMetadata> = emptyList()
        var downloaded: BackupFile? = null
        var downloadedBackupId: String? = null
        var uploadedBackup: BackupFile? = null
        var fileDownloadCalls: Int = 0
        var downloadedFile: File? = null
        var fileDownloadOverride: (suspend (File) -> Result<BackupMetadata>)? = null
        var fileUploadInterruption: (suspend (File) -> Unit)? = null

        override suspend fun refreshAccessToken(refreshToken: String): Result<String> {
            refreshCalls++
            onRefresh?.invoke()
            return if (refreshResults.isNotEmpty()) refreshResults.removeAt(0) else Result.failure(UnsupportedOperationException())
        }

        override suspend fun uploadBackupFile(
            accessToken: String,
            backup: BackupUploadFileRequest,
        ): Result<BackupUploadResult> {
            fileUploadCalls++
            uploadAccessTokens += accessToken
            val source = File(backup.sourcePath.toString())
            fileUploadInterruption?.let { interruption ->
                fileUploadInterruption = null
                interruption(source)
            }
            uploadedFileSize = source.length()
            uploadedFileHeader = source.inputStream().use { it.readNBytes(5).decodeToString() }
            uploadedFileDigest =
                source.inputStream().use { input ->
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                    digest.digest().joinToString("") { byte ->
                        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
                    }
                }
            uploadedFileDigests += requireNotNull(uploadedFileDigest)
            uploadedFileManifest = backup.manifest
            uploadedFileBytes = if (retainUploadedFileBytes) source.readBytes() else null
            return if (uploadResults.isNotEmpty()) uploadResults.removeAt(0) else uploadResult
        }

        override suspend fun uploadBackup(
            accessToken: String,
            backup: BackupFile,
        ): Result<BackupUploadResult> {
            uploadCalls++
            uploadedBackup = backup
            return uploadResult
        }

        override suspend fun listBackups(accessToken: String): Result<List<BackupMetadata>> {
            listAccessTokens += accessToken
            return if (listResults.isNotEmpty()) listResults.removeAt(0) else Result.success(backups)
        }

        override suspend fun downloadBackup(
            accessToken: String,
            backupId: String,
        ): Result<BackupFile> {
            downloadedBackupId = backupId
            return downloaded?.let(Result.Companion::success)
                ?: Result.failure(UnsupportedOperationException())
        }

        override suspend fun downloadBackupToFile(
            accessToken: String,
            backupId: String,
            destination: Path,
        ): Result<BackupMetadata> {
            fileDownloadCalls++
            downloadAccessTokens += accessToken
            downloadedBackupId = backupId
            val scriptedResult = if (fileDownloadResults.isNotEmpty()) fileDownloadResults.removeAt(0) else null
            if (scriptedResult?.isFailure == true) return scriptedResult
            val destinationFile = File(destination.toString()).apply { parentFile?.mkdirs() }
            fileDownloadOverride?.let { override ->
                fileDownloadOverride = null
                return override(destinationFile)
            }
            val sourceFile = downloadedFile
            if (sourceFile != null) {
                sourceFile.inputStream().buffered().use { input ->
                    destinationFile.outputStream().buffered().use { output -> input.copyTo(output, 64 * 1024) }
                }
            } else {
                val backup = downloaded ?: return Result.failure(UnsupportedOperationException())
                destinationFile.writeBytes(backup.data)
            }
            return scriptedResult ?: backups
                .firstOrNull { it.id == backupId }
                ?.let(Result.Companion::success)
                ?: Result.failure(IllegalStateException("Unknown backup"))
        }

        override suspend fun deleteBackup(
            accessToken: String,
            backupId: String,
        ): Result<Unit> = Result.success(Unit)
    }
}
