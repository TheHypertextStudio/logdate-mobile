package app.logdate.feature.core.export

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.WorkerParameters
import app.logdate.client.datastore.OriginBoundSession
import app.logdate.client.datastore.SessionStorage
import app.logdate.client.device.crypto.IdentityKeyManager
import app.logdate.client.device.crypto.IdentityKeyNotFoundException
import app.logdate.client.device.identity.DeviceIdProvider
import app.logdate.client.domain.export.archive.ArchiveExportOptions
import app.logdate.client.domain.export.archive.ArchiveExportProgress
import app.logdate.client.domain.export.archive.ArchiveOmissionReason
import app.logdate.client.domain.export.archive.ExportArchiveUseCase
import app.logdate.client.sync.SyncReadinessGate
import app.logdate.client.sync.cloud.BackupUploadFileRequest
import app.logdate.client.sync.cloud.CloudBackupDataSource
import app.logdate.feature.core.backup.CloudBackupTokenRefresher
import app.logdate.feature.core.backup.pruneAbandonedCloudArchives
import app.logdate.feature.core.settings.ui.ArchiveWorkState
import app.logdate.feature.core.settings.ui.CloudArchiveAttempt
import app.logdate.feature.core.settings.ui.CloudArchiveStage
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.coroutines.cancellation.CancellationException

/** Builds a private export archive and uploads it to LogDate Cloud when authenticated. */
class CloudBackupWorker(
    private val context: Context,
    params: WorkerParameters,
    private val exportArchiveUseCase: ExportArchiveUseCase,
    private val cloudBackupDataSource: CloudBackupDataSource,
    private val sessionStorage: SessionStorage,
    private val deviceIdProvider: DeviceIdProvider,
    private val identityKeyManager: IdentityKeyManager,
    private val cloudArchiveCipher: CloudArchiveCipher,
    private val readinessGate: SyncReadinessGate = SyncReadinessGate(),
) : CoroutineWorker(context, params) {
    private var activeAttempt: CloudArchiveAttempt? = null
    private var uploadedBackupId: String? = null
    private var uploadedDeviceId: String? = null

    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            try {
                backupMutex.withLock {
                    RandomAccessFile(File(context.noBackupFilesDir, "cloud-backup.lock"), "rw").use { lockFile ->
                        lockFile.channel.lock().use { runRecordedAttempt() }
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                readinessGate.pause(error)
                Napier.w("Cloud archive backup could not finish")
                retryUnlessExhausted()
            }
        }

    private suspend fun runRecordedAttempt(): Result {
        val scope =
            sessionStorage.getOriginBoundSession()
                ?: return if (sessionStorage.getSession() == null) Result.success() else retryUnlessExhausted()
        val store = CloudBackupAttemptStore(context.noBackupFilesDir)
        val attempt = store.begin(scope, id.toString())
        activeAttempt = attempt
        uploadedBackupId = null
        uploadedDeviceId = null
        val result =
            try {
                performBackup(scope)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                readinessGate.pause(error)
                Napier.w("Cloud archive backup could not finish")
                retryUnlessExhausted()
            }
        val state =
            when (result) {
                is Result.Success -> ArchiveWorkState.SUCCEEDED
                is Result.Failure -> ArchiveWorkState.FAILED
                else -> ArchiveWorkState.RETRYING
            }
        val terminal = attempt.copy(state = state, backupId = uploadedBackupId, deviceId = uploadedDeviceId)
        store.write(scope, terminal)
        val output = attemptData(scope, terminal).build()
        return when (result) {
            is Result.Success -> Result.success(output)
            is Result.Failure -> Result.failure(output)
            else -> result
        }
    }

    private suspend fun performBackup(scope: OriginBoundSession): Result {
        val gate = readinessGate
        if (!gate.allowProbe(manual = inputData.getBoolean(MANUAL_REQUEST, false))) return retryUnlessExhausted()
        if (!identityKeyManager.hasIdentityKey() || !identityKeyManager.canUseForAccount(scope.session.accountId)) {
            gate.pause(IdentityKeyNotFoundException("Encryption identity is unavailable for this account"))
            return retryUnlessExhausted()
        }
        progress(scope, CloudArchiveStage.CHECKING)
        pruneAbandonedCloudArchives(context)
        val cache = CloudBackupArchiveCache(context.noBackupFilesDir)
        try {
            val identityFingerprint = identityFingerprint()
            val currentFingerprint = if (cache.hasCandidate()) exportArchiveUseCase.sourceFingerprint() else null
            val candidate = currentFingerprint?.let { cacheIdentity(scope, identityFingerprint, it) }
            val reusable = candidate != null && runCatching { cache.reusable(candidate) }.getOrDefault(false)
            if (!reusable) {
                cache.discard()
                val exported = exportArchive(cache.plaintext, scope)
                if (exported !is ArchiveExportProgress.Completed) return retryUnlessExhausted()
                if (exported.summary.scope.omitted
                        .any { it.reason == ArchiveOmissionReason.UNREADABLE }
                ) {
                    return Result.failure()
                }
                val fingerprint = exported.summary.sourceFingerprint ?: return Result.failure()
                validateArchive(cache.plaintext)
                progress(scope, CloudArchiveStage.ENCRYPTING)
                ensureScopeAndIdentity(scope, identityFingerprint)
                cloudArchiveCipher.encrypt(cache.plaintext, cache.encryptedPart)
                ensureScopeAndIdentity(scope, identityFingerprint)
                cache.finalize(cacheIdentity(scope, identityFingerprint, fingerprint))
            }
            ensureScopeAndIdentity(scope, identityFingerprint)
            gate.ready()
            progress(scope, CloudArchiveStage.UPLOADING)
            return uploadArchive(cache, scope)
        } finally {
            cache.cleanupParts()
        }
    }

    private suspend fun exportArchive(
        archive: File,
        scope: OriginBoundSession,
    ): ArchiveExportProgress? =
        FileOutputStream(archive).use { output ->
            ZipOutputStream(output.buffered()).use { zip ->
                exportArchiveUseCase
                    .export(ArchiveExportOptions(), ZipStreamArchiveContainer(zip))
                    .onEach { value ->
                        when (value) {
                            is ArchiveExportProgress.InProgress -> progress(scope, CloudArchiveStage.EXPORTING, value.fraction)
                            ArchiveExportProgress.Starting -> progress(scope, CloudArchiveStage.EXPORTING)
                            else -> Unit
                        }
                    }.firstOrNull { it is ArchiveExportProgress.Completed || it is ArchiveExportProgress.Failed }
            }
        }

    private suspend fun uploadArchive(
        cache: CloudBackupArchiveCache,
        scope: OriginBoundSession,
    ): Result {
        val request =
            BackupUploadFileRequest(
                deviceId = deviceIdProvider.getDeviceId().value.toString(),
                manifest = CloudArchiveCipher.MANIFEST,
                sourcePath = Path(cache.archive.absolutePath),
                sizeBytes = cache.archive.length(),
            )
        val uploaded =
            CloudBackupTokenRefresher(sessionStorage, cloudBackupDataSource)
                .execute(scope) { token ->
                    cloudBackupDataSource.uploadBackupFile(token, request)
                }.result
        if (uploaded.isFailure) {
            uploaded.exceptionOrNull()?.let { readinessGate.pause(it) }
            return retryUnlessExhausted()
        }
        uploadedBackupId = uploaded.getOrNull()?.id
        uploadedDeviceId = request.deviceId
        cache.discard()
        return Result.success()
    }

    private suspend fun progress(
        scope: OriginBoundSession,
        stage: CloudArchiveStage,
        fraction: Float? = null,
    ) {
        val data =
            attemptData(scope, activeAttempt)
                .putString(PROGRESS_STAGE, stage.name)
        if (fraction != null && fraction.isFinite()) data.putFloat(PROGRESS_FRACTION, fraction.coerceIn(0f, 1f))
        setProgress(data.build())
    }

    private fun attemptData(
        scope: OriginBoundSession,
        attempt: CloudArchiveAttempt?,
    ): Data.Builder =
        Data
            .Builder()
            .putString(PROGRESS_ACCOUNT, scope.session.accountId)
            .putString(PROGRESS_ORIGIN, scope.origin)
            .putLong(ATTEMPT_ORDER, attempt?.order ?: 0L)
            .putString(ATTEMPT_BACKUP_ID, attempt?.backupId)
            .putString(ATTEMPT_DEVICE_ID, attempt?.deviceId)

    private suspend fun identityFingerprint(): String =
        MessageDigest.getInstance("SHA-256").digest(identityKeyManager.getIdentityKey()).hexDigest()

    private suspend fun ensureScopeAndIdentity(
        scope: OriginBoundSession,
        identity: String,
    ) {
        check(sessionStorage.getOriginBoundSession() == scope) { "Cloud account changed during backup" }
        check(identityKeyManager.canUseForAccount(scope.session.accountId) && identityFingerprint() == identity) {
            "Encryption identity changed during backup"
        }
    }

    private fun cacheIdentity(
        scope: OriginBoundSession,
        identity: String,
        source: String,
    ) = CloudArchiveCacheIdentity(scope.origin, scope.session.accountId, identity, "${CloudArchiveCipher.MANIFEST}:all-v2", source)

    private fun validateArchive(archive: File) {
        ZipFile(archive).use { requireNotNull(it.getEntry("manifest.json")) { "Archive manifest is missing" } }
    }

    private fun retryUnlessExhausted(): Result = if (cloudBackupShouldRetry(runAttemptCount)) Result.retry() else Result.failure()

    companion object {
        const val WORK_NAME = "logdate:cloud:backup"
        const val MANUAL_REQUEST = "cloud_archive_manual"
        const val PROGRESS_STAGE = "cloud_archive_stage"
        const val PROGRESS_FRACTION = "cloud_archive_fraction"
        const val PROGRESS_ACCOUNT = "cloud_archive_account"
        const val PROGRESS_ORIGIN = "cloud_archive_origin"
        const val REQUEST_TIME_TAG = "cloud_archive_requested_at:"
        const val ATTEMPT_ORDER = "cloud_archive_attempt_order"
        const val ATTEMPT_BACKUP_ID = "cloud_archive_backup_id"
        const val ATTEMPT_DEVICE_ID = "cloud_archive_device_id"
        private val backupMutex = Mutex()
        val NETWORK_CONSTRAINTS: Constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    }
}
