package app.logdate.server.routes.sync

import app.logdate.server.crypto.EncryptionService
import app.logdate.server.entitlements.EntitlementEnforcer
import app.logdate.server.entitlements.QuotaCheck
import app.logdate.server.logdate.LogDateBackup
import app.logdate.server.logdate.LogDateBackupRepository
import app.logdate.server.logdate.LogDateBlobFileWriteRequest
import app.logdate.server.logdate.LogDateBlobNamespace
import app.logdate.server.logdate.LogDateBlobStorage
import app.logdate.server.responses.error
import app.logdate.shared.model.sync.BackupUploadResponse
import io.github.aakira.napier.Napier
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * Encrypts a received backup, stores it, and records it for [userId], then answers the request:
 * `201` with the new backup, or the error that stopped it. Returns whether the backup was stored.
 * The caller still owns [received]'s file and deletes it.
 */
internal suspend fun ApplicationCall.storeReceivedBackup(
    userId: UUID,
    received: ParsedBackupMultipartFile,
    storage: LogDateBlobStorage?,
    encryptionService: EncryptionService,
    backupRepository: LogDateBackupRepository,
    entitlementEnforcer: EntitlementEnforcer?,
    checkActive: () -> Unit,
): Boolean {
    if (entitlementEnforcer != null) {
        val quota = entitlementEnforcer.checkBackupUpload(userId, received.sizeBytes)
        if (quota is QuotaCheck.Denied) {
            respondQuotaExceeded(this, quota)
            return false
        }
    }

    if (storage == null) {
        respond(HttpStatusCode.ServiceUnavailable, error("BACKUP_STORAGE_UNAVAILABLE", "Backup storage not configured"))
        return false
    }

    val backupId = UUID.randomUUID()
    val storagePath = encryptAndStoreBackup(userId, backupId, received, storage, encryptionService, checkActive) ?: return false
    val record =
        recordBackup(
            backupRepository,
            storage,
            LogDateBackup(
                id = backupId,
                userId = userId,
                deviceId = received.deviceId,
                manifest = received.manifest,
                storagePath = storagePath,
                createdAt = System.currentTimeMillis(),
                sizeBytes = received.sizeBytes,
            ),
        ) ?: return false

    response.headers.append(HttpHeaders.Location, "/api/v1/backups/${record.id}")
    respond(
        HttpStatusCode.Created,
        BackupUploadResponse(id = record.id.toString(), createdAt = record.createdAt, sizeBytes = record.sizeBytes),
    )
    return true
}

/** Encrypts [received] and writes it to storage, returning its storage path, or answers `500` and returns null. */
private suspend fun ApplicationCall.encryptAndStoreBackup(
    userId: UUID,
    backupId: UUID,
    received: ParsedBackupMultipartFile,
    storage: LogDateBlobStorage,
    encryptionService: EncryptionService,
    checkActive: () -> Unit,
): String? {
    val encryptedFile = createPrivateBackupTempFile("logdate-backup-encrypted-", ".bin")
    try {
        try {
            encryptionService.processBackupUpload(received.path, encryptedFile, checkActive)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Napier.e("Failed to encrypt backup")
            respond(HttpStatusCode.InternalServerError, error("BACKUP_ENCRYPT_FAILED", "Failed to encrypt backup"))
            return null
        }
        return try {
            storage.putBlobFile(
                LogDateBlobFileWriteRequest(
                    ownerId = userId,
                    namespace = LogDateBlobNamespace.BACKUP,
                    blobId = backupId.toString(),
                    fileName = null,
                    contentType = "application/octet-stream",
                    path = encryptedFile,
                    checkActive = checkActive,
                ),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Napier.e("Failed to store backup blob")
            respond(HttpStatusCode.InternalServerError, error("BACKUP_STORAGE_FAILED", "Failed to store backup"))
            null
        }
    } finally {
        deletePrivateBackupTempFile(encryptedFile)
    }
}

/** Records [backup], deleting its blob if that fails; answers `500` and returns null on failure. */
private suspend fun ApplicationCall.recordBackup(
    backupRepository: LogDateBackupRepository,
    storage: LogDateBlobStorage,
    backup: LogDateBackup,
): LogDateBackup? =
    try {
        backupRepository.createBackup(backup.userId, backup)
    } catch (cancellation: CancellationException) {
        runCatching { storage.deleteBlob(backup.storagePath) }
        throw cancellation
    } catch (failure: Exception) {
        if (!runCatching { storage.deleteBlob(backup.storagePath) }.getOrDefault(false)) {
            Napier.w("Failed to roll back backup blob after metadata write failure")
        }
        Napier.e("Failed to persist backup metadata")
        respond(HttpStatusCode.InternalServerError, error("BACKUP_METADATA_WRITE_FAILED", "Failed to store backup metadata"))
        null
    }
