package app.logdate.feature.core.restore

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.logdate.client.datastore.OriginBoundSession
import app.logdate.client.datastore.SessionStorage
import app.logdate.client.sync.cloud.CloudBackupDataSource
import app.logdate.feature.core.backup.CloudBackupTokenRefresher
import app.logdate.feature.core.backup.cloudArchiveFile
import app.logdate.feature.core.backup.pruneAbandonedCloudArchives
import app.logdate.feature.core.export.CloudArchiveCipher
import com.google.common.util.concurrent.ListenableFuture
import io.github.aakira.napier.Napier
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.io.files.Path
import java.io.File
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Downloads the newest authenticated Cloud backup and hands it to the normal restore pipeline.
 *
 * The worker never mutates the local database itself. [RestoreWorker] remains the single archive
 * applier, which preserves its merge semantics, media import behavior, progress reporting, and
 * one-owner safeguards.
 */
class CloudRestoreWorker(
    private val context: Context,
    params: WorkerParameters,
    private val cloudBackupDataSource: CloudBackupDataSource,
    private val sessionStorage: SessionStorage,
    private val cloudArchiveCipher: CloudArchiveCipher,
    private val hasActiveRestore: (suspend (String, String, String) -> Boolean)? = null,
    private val enqueueRestore: (suspend (File) -> Unit)? = null,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val workState = NativeRestoreWorkState(context, inputData, sessionStorage)
        val files = CloudRestoreFiles(context, id)
        return try {
            restoreLatestArchive(workState, files)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            Napier.w("CloudRestoreWorker: restore handoff failed")
            Result.retry()
        } finally {
            files.cleanup()
        }
    }

    private suspend fun restoreLatestArchive(
        workState: NativeRestoreWorkState,
        files: CloudRestoreFiles,
    ): Result {
        when (workState.validation()) {
            NativeRestoreWorkState.Validation.CURRENT -> {
                Unit
            }

            NativeRestoreWorkState.Validation.STALE -> {
                resolveExistingArchiveOwnership(files)
                return Result.success()
            }

            NativeRestoreWorkState.Validation.WAITING_FOR_SESSION -> {
                resolveExistingArchiveOwnership(files)
                return Result.retry()
            }
        }
        val sessionAvailable = sessionStorage.hasValidSession()
        val sessionBoundWork =
            inputData.getString(NativeRestoreWorkState.OWNER_KEY) != null ||
                inputData.getString(NativeRestoreWorkState.ORIGIN_KEY) != null
        var expectedSession =
            sessionStorage.getOriginBoundSession()
                ?: return if (sessionAvailable || sessionBoundWork) Result.retry() else Result.success()
        val deliveryId = inputData.getString(NativeRestoreWorkState.DELIVERY_KEY)
        if (deliveryId != null && hasActiveRestoreForDelivery(deliveryId, expectedSession.session.accountId, expectedSession.origin)) {
            files.preserveArchive = true
            return Result.success()
        }
        files.archiveOwnershipResolved = true
        pruneAbandonedCloudArchives(context)
        val tokenRefresher = CloudBackupTokenRefresher(sessionStorage, cloudBackupDataSource)
        val listResult = tokenRefresher.execute(expectedSession) { accessToken -> cloudBackupDataSource.listBackups(accessToken) }
        expectedSession = listResult.session
        val backup =
            listResult.result
                .getOrElse {
                    Napier.w("CloudRestoreWorker: unable to list backups")
                    return Result.retry()
                }.maxByOrNull { it.createdAt }
        if (backup == null) return acknowledgeEmptyBackupList(expectedSession)

        files.clearBeforeDownload()
        if (sessionStorage.getOriginBoundSession() != expectedSession) return Result.retry()
        expectedSession =
            downloadAndDecrypt(tokenRefresher, expectedSession, backup.id, files.encrypted, files.plaintextPart, files.archive)
                ?: return Result.retry()
        return handoffArchive(workState, files, expectedSession)
    }

    private suspend fun acknowledgeEmptyBackupList(session: OriginBoundSession): Result =
        if (NativeRestoreWorkState(context, inputData, sessionStorage).complete(session)) {
            Result.success()
        } else {
            Result.retry()
        }

    private suspend fun handoffArchive(
        workState: NativeRestoreWorkState,
        files: CloudRestoreFiles,
        session: OriginBoundSession,
    ): Result {
        try {
            if (sessionStorage.getOriginBoundSession() != session) return Result.retry()
            when (workState.validation()) {
                NativeRestoreWorkState.Validation.CURRENT -> Unit
                NativeRestoreWorkState.Validation.STALE -> return Result.success()
                NativeRestoreWorkState.Validation.WAITING_FOR_SESSION -> return Result.retry()
            }
            val custom = enqueueRestore
            if (custom != null) custom(files.archive) else enqueueArchive(files.archive, session)
            files.preserveArchive = true
        } catch (cancellation: CancellationException) {
            files.preserveArchive = true
            throw cancellation
        }
        return Result.success()
    }

    private suspend fun enqueueArchive(
        archive: File,
        session: OriginBoundSession,
    ) {
        val data =
            NativeRestoreWorkState
                .bind(
                    Data
                        .Builder()
                        .putString(RestoreWorker.SOURCE_URI_KEY, Uri.fromFile(archive).toString())
                        .putBoolean(RestoreWorker.DELETE_SOURCE_AFTER_RESTORE_KEY, true)
                        .putBoolean(RestoreWorker.INCLUDE_DRAFTS_KEY, true)
                        .putBoolean(RestoreWorker.INCLUDE_MEDIA_KEY, true)
                        .putBoolean(RestoreWorker.PRESERVE_POPULATED_PROFILE_KEY, true),
                    inputData.getString(NativeRestoreWorkState.DELIVERY_KEY),
                    session,
                ).build()
        val requestBuilder =
            OneTimeWorkRequestBuilder<RestoreWorker>()
                .setInputData(data)
                .setConstraints(archiveRecoveryConstraints)
                .addTag(WORK_TAG)
        inputData.getString(NativeRestoreWorkState.DELIVERY_KEY)?.let { deliveryId ->
            requestBuilder.addTag(CloudRestoreScheduler.deliveryWorkTag(deliveryId, session))
        }
        val request = requestBuilder.build()
        val enqueueResult =
            WorkManager.getInstance(context).enqueueUniqueWork(RestoreWorker.WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        enqueueResult.result.awaitResult()
    }

    private suspend fun resolveExistingArchiveOwnership(files: CloudRestoreFiles) {
        if (!files.archive.isFile) return
        val deliveryId = inputData.getString(NativeRestoreWorkState.DELIVERY_KEY) ?: return
        val owner = inputData.getString(NativeRestoreWorkState.OWNER_KEY) ?: return
        val origin = inputData.getString(NativeRestoreWorkState.ORIGIN_KEY) ?: return
        if (hasActiveRestoreForDelivery(deliveryId, owner, origin)) {
            files.preserveArchive = true
        } else {
            files.archiveOwnershipResolved = true
        }
    }

    private suspend fun hasActiveRestoreForDelivery(
        deliveryId: String,
        accountId: String,
        origin: String,
    ): Boolean {
        val injectedCheck = hasActiveRestore
        if (injectedCheck != null) return injectedCheck(deliveryId, accountId, origin)
        return WorkManager
            .getInstance(context)
            .getWorkInfosByTag(CloudRestoreScheduler.deliveryWorkTag(deliveryId, accountId, origin))
            .awaitResult()
            .any { it.id != id && !it.state.isFinished }
    }

    /**
     * Downloads [backupId] and decrypts it into [archive].
     *
     * Returns the session the download completed under, or null when the work should retry.
     */
    private suspend fun downloadAndDecrypt(
        tokenRefresher: CloudBackupTokenRefresher,
        expectedSession: OriginBoundSession,
        backupId: String,
        encrypted: File,
        plaintextPart: File,
        archive: File,
    ): OriginBoundSession? {
        val downloadResult =
            tokenRefresher.execute(expectedSession) { accessToken ->
                encrypted.delete()
                cloudBackupDataSource.downloadBackupToFile(accessToken, backupId, Path(encrypted.absolutePath))
            }
        val downloadedMetadata =
            downloadResult.result.getOrElse {
                Napier.w("CloudRestoreWorker: unable to download backup")
                return null
            }
        check(downloadedMetadata.id == backupId) { "Downloaded backup metadata did not match request" }
        check(encrypted.isFile && encrypted.length() > 0L) { "Downloaded archive is empty" }
        if (sessionStorage.getOriginBoundSession() != downloadResult.session) return null
        cloudArchiveCipher.decrypt(encrypted, plaintextPart)
        check(plaintextPart.renameTo(archive)) { "Unable to finalize restored archive" }
        return downloadResult.session
    }

    companion object {
        const val WORK_NAME = "logdate:cloud:restore"
        const val WORK_TAG = "logdate:cloud:restore"
    }
}

private class CloudRestoreFiles(
    context: Context,
    workerId: UUID,
) {
    private val directory = context.noBackupFilesDir
    val encrypted = File(directory, "cloud-restore-$workerId.encrypted.part")
    val plaintextPart = File(directory, "cloud-restore-$workerId.plain.part")
    val archive = cloudArchiveFile(context, "cloud-restore", workerId)
    var preserveArchive = false
    var archiveOwnershipResolved = false

    fun clearBeforeDownload() {
        encrypted.delete()
        plaintextPart.delete()
        archive.delete()
    }

    fun cleanup() {
        encrypted.delete()
        plaintextPart.delete()
        if (!preserveArchive && archiveOwnershipResolved) archive.delete()
    }
}

private suspend fun <T> ListenableFuture<T>.awaitResult(): T =
    suspendCancellableCoroutine { continuation ->
        addListener(
            {
                runCatching { get() }
                    .onSuccess { value -> continuation.resume(value) }
                    .onFailure { error -> continuation.resumeWithException(error) }
            },
            Executor { command -> command.run() },
        )
        continuation.invokeOnCancellation { cancel(true) }
    }
