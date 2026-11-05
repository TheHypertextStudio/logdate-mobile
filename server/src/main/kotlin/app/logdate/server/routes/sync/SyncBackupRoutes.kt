@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.routes.sync

import app.logdate.server.auth.TokenService
import app.logdate.server.crypto.EncryptionService
import app.logdate.server.database.toJavaUUID
import app.logdate.server.entitlements.EntitlementEnforcer
import app.logdate.server.entitlements.QuotaCheck
import app.logdate.server.identity.adoption.IdentityWriteRefusedException
import app.logdate.server.logdate.LegacyObjectUploads
import app.logdate.server.logdate.LegacyObjectWriteFailure
import app.logdate.server.logdate.LogDateBackup
import app.logdate.server.logdate.LogDateBackupRepository
import app.logdate.server.logdate.LogDateBlobFileWriteRequest
import app.logdate.server.logdate.LogDateBlobNamespace
import app.logdate.server.logdate.LogDateBlobStorage
import app.logdate.server.logdate.LogDateBlobWriteRequest
import app.logdate.server.logdate.copyBackupFile
import app.logdate.server.logdate.deleteLegacyObject
import app.logdate.server.ratelimit.SlidingWindowRateLimiter
import app.logdate.server.responses.error
import app.logdate.server.routes.LogDateAccountAuth
import app.logdate.server.routes.docs.SyncStorageDocs
import app.logdate.server.sync.SyncMetricsRegistry
import app.logdate.server.transfer.TransferLimits
import app.logdate.server.transfer.TransferSlots
import app.logdate.shared.model.sync.BackupInfoResponse
import app.logdate.shared.model.sync.BackupListResponse
import app.logdate.shared.model.sync.BackupUploadResponse
import io.github.aakira.napier.Napier
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * `/api/v1/backups` endpoints — encrypted device backup upload, listing, binary download, delete.
 *
 * Same gate pattern as media: per-user rate limit first, then quota check.
 */
internal fun Route.syncBackupRoutes(
    tokenService: TokenService?,
    mediaStorage: LogDateBlobStorage?,
    metrics: SyncMetricsRegistry,
    encryptionService: EncryptionService,
    backupRepository: LogDateBackupRepository,
    entitlementEnforcer: EntitlementEnforcer?,
    rateLimiter: SlidingWindowRateLimiter?,
    config: SyncRouteConfig,
    transfers: TransferSlots,
    objectUploads: LegacyObjectUploads,
    accountAuth: LogDateAccountAuth? = null,
) {
    val mediaAccessPolicy = config.mediaAccessPolicy
    route("/backups") {
        post(SyncStorageDocs.uploadBackup) {
            val requestContext = currentCoroutineContext()
            val checkActive = { requestContext.ensureActive() }
            val start = System.currentTimeMillis()
            var success = false
            var bytes = 0L
            try {
                val authority = call.backupWriteAuthority(tokenService, accountAuth) ?: return@post
                val userId = authority.accountId.toJavaUUID()
                // Backups pass through temporary files, which Cloud Run keeps in memory, so each instance
                // runs only as many as its buffered transfer slots allow. A refused one spends no budget.
                val slot = transfers.tryBuffered() ?: return@post call.respondTransferBusy(transfers.limits)
                try {
                    if (rateLimiter != null && !rateLimiter.allow("backup-upload:$userId", BACKUP_UPLOAD_RATE_LIMIT)) {
                        return@post respondRateLimited(call, rateLimiter, "backup-upload:$userId", BACKUP_UPLOAD_RATE_LIMIT)
                    }

                    val req = call.receiveBackupMultipartFile(transfers.limits.idleTimeout) ?: return@post
                    try {
                        bytes = req.sizeBytes

                        if (entitlementEnforcer != null) {
                            val quota = entitlementEnforcer.checkBackupUpload(userId, bytes)
                            if (quota is QuotaCheck.Denied) {
                                return@post respondQuotaExceeded(call, quota)
                            }
                        }

                        val storage =
                            mediaStorage ?: return@post call.respond(
                                HttpStatusCode.ServiceUnavailable,
                                error("BACKUP_STORAGE_UNAVAILABLE", "Backup storage not configured"),
                            )

                        val backupId = UUID.randomUUID()

                        val encryptedFile = createPrivateBackupTempFile("logdate-backup-encrypted-", ".bin")
                        try {
                            try {
                                encryptionService
                                    .processBackupUpload(
                                        req.path,
                                        encryptedFile,
                                        userId.toString(),
                                        backupId.toString(),
                                        checkActive,
                                    )
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (failure: Exception) {
                                Napier.e("Failed to encrypt backup")
                                return@post call.respond(
                                    HttpStatusCode.InternalServerError,
                                    error("BACKUP_ENCRYPT_FAILED", "Failed to encrypt backup"),
                                )
                            }

                            val write =
                                LogDateBlobFileWriteRequest(
                                    ownerId = userId,
                                    namespace = LogDateBlobNamespace.BACKUP,
                                    blobId = backupId.toString(),
                                    fileName = null,
                                    contentType = "application/octet-stream",
                                    path = encryptedFile,
                                    checkActive = checkActive,
                                )
                            var candidate: String? = null
                            val record =
                                try {
                                    withContext(Dispatchers.IO) {
                                        objectUploads.upload(
                                            owner = userId,
                                            namespace = LogDateBlobNamespace.BACKUP,
                                            epoch = authority.identityEpoch,
                                            plannedPath = {
                                                storage.plannedPath(
                                                    LogDateBlobWriteRequest(
                                                        ownerId = userId,
                                                        namespace = write.namespace,
                                                        blobId = write.blobId,
                                                        contentType = write.contentType,
                                                        bytes = ByteArray(0),
                                                    ),
                                                )
                                            },
                                            write = { storage.putBlobFile(write).also { candidate = it } },
                                            canonicalPath = LogDateBackup::storagePath,
                                            publish = { path, epoch ->
                                                backupRepository.createBackup(
                                                    userId,
                                                    LogDateBackup(
                                                        id = backupId,
                                                        userId = userId,
                                                        deviceId = req.deviceId,
                                                        manifest = req.manifest,
                                                        storagePath = path,
                                                        createdAt = System.currentTimeMillis(),
                                                        sizeBytes = bytes,
                                                    ),
                                                    epoch,
                                                )
                                            },
                                            deleteObject = { storage.deleteLegacyObject(it) },
                                        )
                                    }
                                } catch (cancellation: CancellationException) {
                                    val path = candidate
                                    if (!objectUploads.isDurable && path != null) {
                                        cleanupBackupObject(userId, path, backupRepository, storage)
                                    }
                                    throw cancellation
                                } catch (failure: IdentityWriteRefusedException) {
                                    return@post call.respondIdentityWriteRefusal(failure)
                                } catch (_: LegacyObjectWriteFailure) {
                                    Napier.e("Failed to store backup blob")
                                    return@post call.respond(
                                        HttpStatusCode.InternalServerError,
                                        error("BACKUP_STORAGE_FAILED", "Failed to store backup"),
                                    )
                                } catch (_: Exception) {
                                    val path = candidate
                                    if (!objectUploads.isDurable && path != null) {
                                        cleanupBackupObject(userId, path, backupRepository, storage)
                                    }
                                    Napier.e("Failed to persist backup metadata")
                                    return@post call.respond(
                                        HttpStatusCode.InternalServerError,
                                        error("BACKUP_METADATA_WRITE_FAILED", "Failed to store backup metadata"),
                                    )
                                }

                            call.response.headers.append(HttpHeaders.Location, "/api/v1/backups/${record.id}")
                            call.respond(
                                HttpStatusCode.Created,
                                BackupUploadResponse(
                                    id = record.id.toString(),
                                    createdAt = record.createdAt,
                                    sizeBytes = record.sizeBytes,
                                ),
                            )
                            success = true
                        } finally {
                            deletePrivateBackupTempFile(encryptedFile)
                        }
                    } finally {
                        deletePrivateBackupTempFile(req.path)
                    }
                } finally {
                    slot.release()
                }
            } finally {
                metrics.recordOperation("sync.backup.upload", System.currentTimeMillis() - start, success, bytes)
            }
        }

        get(SyncStorageDocs.listBackups) {
            val start = System.currentTimeMillis()
            var success = false
            try {
                val userId = extractUserId(call, tokenService) ?: return@get
                val backups = backupRepository.listBackups(userId)
                call.respond(
                    BackupListResponse(
                        backups.map {
                            BackupInfoResponse(
                                id = it.id.toString(),
                                deviceId = it.deviceId,
                                manifest = it.manifest,
                                createdAt = it.createdAt,
                                sizeBytes = it.sizeBytes,
                                downloadUrl = resolveBackupDownloadUrl(call, it, mediaStorage, mediaAccessPolicy),
                            )
                        },
                    ),
                )
                success = true
            } finally {
                metrics.recordOperation("sync.backup.list", System.currentTimeMillis() - start, success)
            }
        }

        get("/{backupId}", SyncStorageDocs.getBackup) {
            val start = System.currentTimeMillis()
            var success = false
            try {
                val userId = extractUserId(call, tokenService) ?: return@get
                val backupId = call.parseBackupId() ?: return@get

                val record =
                    backupRepository.getBackup(userId, backupId)
                        ?: return@get call.respond(HttpStatusCode.NotFound, error("NOT_FOUND", "Backup not found"))
                call.respond(
                    BackupInfoResponse(
                        id = record.id.toString(),
                        deviceId = record.deviceId,
                        manifest = record.manifest,
                        createdAt = record.createdAt,
                        sizeBytes = record.sizeBytes,
                        downloadUrl = resolveBackupDownloadUrl(call, record, mediaStorage, mediaAccessPolicy),
                    ),
                )
                success = true
            } finally {
                metrics.recordOperation("sync.backup.download", System.currentTimeMillis() - start, success)
            }
        }

        get("/{backupId}/binary", SyncStorageDocs.downloadBackup) {
            val requestContext = currentCoroutineContext()
            val checkActive = { requestContext.ensureActive() }
            val start = System.currentTimeMillis()
            var success = false
            try {
                val userId = extractUserId(call, tokenService) ?: return@get
                val backupId = call.parseBackupId() ?: return@get

                val record =
                    backupRepository.getBackup(userId, backupId)
                        ?: return@get call.respond(HttpStatusCode.NotFound, error("NOT_FOUND", "Backup not found"))

                val storage =
                    mediaStorage ?: return@get call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        error("BACKUP_STORAGE_UNAVAILABLE", "Backup storage not configured"),
                    )

                val slot = transfers.tryBuffered() ?: return@get call.respondTransferBusy(transfers.limits)
                var leased = false
                try {
                    val encryptedFile = createPrivateBackupTempFile("logdate-backup-download-", ".bin")
                    val decryptedFile = createPrivateBackupTempFile("logdate-backup-verified-", ".bin")
                    try {
                        val found =
                            try {
                                storage.getBlobFile(record.storagePath, encryptedFile, checkActive)
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (failure: Exception) {
                                Napier.e("Failed to read backup blob")
                                return@get call.respond(
                                    HttpStatusCode.InternalServerError,
                                    error("BACKUP_STORAGE_FAILED", "Failed to read backup"),
                                )
                            }
                        if (!found) {
                            return@get call.respond(HttpStatusCode.NotFound, error("NOT_FOUND", "Backup file not found"))
                        }
                        try {
                            encryptionService.processBackupDownload(
                                encryptedFile,
                                decryptedFile,
                                shouldDecrypt = true,
                                checkActive = checkActive,
                            )
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (failure: Exception) {
                            Napier.e("Failed to decrypt backup")
                            return@get call.respond(
                                HttpStatusCode.InternalServerError,
                                error("BACKUP_DECRYPT_FAILED", "Failed to decrypt backup"),
                            )
                        }
                        val responseLease = BackupResponseFileLease(decryptedFile, onClose = slot::release)
                        leased = true
                        call.respondOutputStream(ContentType.Application.OctetStream) {
                            try {
                                Files.newInputStream(decryptedFile).buffered().use { source ->
                                    copyBackupFile(source, this) {
                                        if (Thread.currentThread().isInterrupted) {
                                            throw CancellationException("Backup response interrupted")
                                        }
                                    }
                                }
                            } finally {
                                responseLease.close()
                            }
                        }
                        success = true
                    } finally {
                        deletePrivateBackupTempFile(encryptedFile)
                        if (!success) deletePrivateBackupTempFile(decryptedFile)
                    }
                } finally {
                    if (!leased) slot.release()
                }
            } finally {
                metrics.recordOperation("sync.backup.download", System.currentTimeMillis() - start, success)
            }
        }

        delete("/{backupId}", SyncStorageDocs.deleteBackup) {
            val start = System.currentTimeMillis()
            var success = false
            try {
                val authority = call.backupWriteAuthority(tokenService, accountAuth) ?: return@delete
                val userId = authority.accountId.toJavaUUID()
                val backupId = call.parseBackupId() ?: return@delete

                val record = backupRepository.getBackup(userId, backupId)
                if (record != null) {
                    val storage =
                        mediaStorage
                            ?: return@delete call.respond(
                                HttpStatusCode.ServiceUnavailable,
                                error("BACKUP_STORAGE_UNAVAILABLE", "Backup storage not configured"),
                            )
                    if (!withContext(Dispatchers.IO) {
                            backupRepository.deleteBackupWithObject(
                                userId,
                                backupId,
                                authority.identityEpoch,
                            ) { path -> storage.deleteLegacyObject(path) }
                        }
                    ) {
                        Napier.w("Failed to delete backup blob")
                        return@delete call.respond(
                            HttpStatusCode.InternalServerError,
                            error("BACKUP_DELETE_FAILED", "Failed to delete backup blob"),
                        )
                    }
                }

                call.respond(HttpStatusCode.NoContent)
                success = true
            } catch (failure: IdentityWriteRefusedException) {
                call.respondIdentityWriteRefusal(failure)
            } finally {
                metrics.recordOperation("sync.backup.delete", System.currentTimeMillis() - start, success)
            }
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.parseBackupId(): UUID? {
    val raw = requiredPathParam("backupId")
    return runCatching { UUID.fromString(raw) }.getOrNull() ?: run {
        respond(HttpStatusCode.BadRequest, error("INVALID_ID", "Invalid backupId"))
        null
    }
}

/** Answers `503 SERVER_BUSY`: this instance is running as many file transfers through temporary files as it can hold. */
private suspend fun ApplicationCall.respondTransferBusy(limits: TransferLimits) {
    response.header(HttpHeaders.RetryAfter, limits.retryAfterSeconds.toString())
    respond(
        HttpStatusCode.ServiceUnavailable,
        error("SERVER_BUSY", "The server is busy with other file transfers. Try again in ${limits.retryAfterSeconds} seconds."),
    )
}
