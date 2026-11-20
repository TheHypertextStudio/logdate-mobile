package app.logdate.server.routes.sync

import app.logdate.server.auth.TokenService
import app.logdate.server.crypto.EncryptionService
import app.logdate.server.entitlements.EntitlementEnforcer
import app.logdate.server.entitlements.QuotaCheck
import app.logdate.server.logdate.LogDateBackupRepository
import app.logdate.server.logdate.LogDateBlobNamespace
import app.logdate.server.logdate.LogDateBlobStorage
import app.logdate.server.logdate.LogDateBlobStreamRequest
import app.logdate.server.logdate.copyBackupFile
import app.logdate.server.ratelimit.RateLimitPolicy
import app.logdate.server.ratelimit.SlidingWindowRateLimiter
import app.logdate.server.responses.error
import app.logdate.server.routes.docs.BackupPartUploadDocs
import app.logdate.server.sync.SyncMetricsRegistry
import app.logdate.server.transfer.TransferSlots
import app.logdate.shared.model.sync.BackupPartUploadCompleteRequest
import app.logdate.shared.model.sync.BackupPartUploadStartRequest
import app.logdate.shared.model.sync.BackupPartUploadStartResponse
import io.github.aakira.napier.Napier
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktoropenapi.put
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/** Each part but the last is exactly this long: well under the 100 MB a proxy in front may allow. */
internal const val BACKUP_PART_BYTES: Long = 32L * 1024 * 1024

/** The same ceiling as a backup sent in one request. */
internal const val MAX_PARTED_BACKUP_BYTES: Long = 2L * 1024 * 1024 * 1024

/** Enough for one backup of the largest size to send every part twice in an hour. */
internal val BACKUP_PART_RATE_LIMIT =
    RateLimitPolicy(maxRequests = 2 * (MAX_PARTED_BACKUP_BYTES / BACKUP_PART_BYTES).toInt(), windowSeconds = 60 * 60)

private const val PART_READ_BYTES = 64 * 1024

/** How many parts a backup of [sizeBytes] is sent in. */
internal fun backupPartCount(sizeBytes: Long): Int = ((sizeBytes + BACKUP_PART_BYTES - 1) / BACKUP_PART_BYTES).toInt()

/** The blob each part is staged under; derived again at completion, so the client never names a path. */
internal fun backupPartBlobId(
    uploadId: UUID,
    index: Int,
): String = "upload-$uploadId-part-%05d".format(index)

/**
 * `/api/v1/backups/uploads`: a backup sent as numbered parts instead of one request body, for
 * deployments behind a proxy that refuses large requests.
 *
 * Starting an upload checks the quota and hands out an ID; nothing is stored. Each part is streamed
 * into blob storage as its own object, so parts may arrive at any instance and in any order, and a
 * part sent again replaces the earlier copy. Completing reads the parts back in order into the same
 * pipeline as a one-request upload, then deletes them.
 */
internal fun Route.syncBackupPartRoutes(
    tokenService: TokenService?,
    mediaStorage: LogDateBlobStorage?,
    metrics: SyncMetricsRegistry,
    encryptionService: EncryptionService,
    backupRepository: LogDateBackupRepository,
    entitlementEnforcer: EntitlementEnforcer?,
    rateLimiter: SlidingWindowRateLimiter?,
    transfers: TransferSlots,
) {
    route("/uploads") {
        startBackupPartUploadRoute(tokenService, mediaStorage, entitlementEnforcer, rateLimiter)
        uploadBackupPartRoute(tokenService, mediaStorage, metrics, rateLimiter, transfers)
        completeBackupPartUploadRoute(
            tokenService,
            mediaStorage,
            metrics,
            transfers,
            BackupStoreDependencies(encryptionService, backupRepository, entitlementEnforcer),
        )
        delete("/{uploadId}", BackupPartUploadDocs.abandonBackupPartUpload) {
            val userId = extractUserId(call, tokenService) ?: return@delete
            val uploadId = call.parseUploadId() ?: return@delete
            val storage = mediaStorage ?: return@delete call.respondBackupStorageUnavailable()
            withContext(Dispatchers.IO) { deleteBackupParts(storage, userId, uploadId, backupPartCount(MAX_PARTED_BACKUP_BYTES)) }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/** What storing a finished backup needs besides blob storage. */
private class BackupStoreDependencies(
    val encryptionService: EncryptionService,
    val backupRepository: LogDateBackupRepository,
    val entitlementEnforcer: EntitlementEnforcer?,
)

private fun Route.startBackupPartUploadRoute(
    tokenService: TokenService?,
    mediaStorage: LogDateBlobStorage?,
    entitlementEnforcer: EntitlementEnforcer?,
    rateLimiter: SlidingWindowRateLimiter?,
) {
    post(BackupPartUploadDocs.startBackupPartUpload) {
        val userId = extractUserId(call, tokenService) ?: return@post
        if (rateLimiter != null && !rateLimiter.allow("backup-upload:$userId", BACKUP_UPLOAD_RATE_LIMIT)) {
            return@post respondRateLimited(call, rateLimiter, "backup-upload:$userId", BACKUP_UPLOAD_RATE_LIMIT)
        }
        val request =
            runCatching { call.receive<BackupPartUploadStartRequest>() }.getOrNull()
                ?: return@post call.respondPartInvalid("Body must be JSON with sizeBytes")
        if (request.sizeBytes !in 1..MAX_PARTED_BACKUP_BYTES) {
            return@post call.respondPartInvalid("sizeBytes must be between 1 and $MAX_PARTED_BACKUP_BYTES")
        }
        if (mediaStorage == null) return@post call.respondBackupStorageUnavailable()
        if (entitlementEnforcer != null) {
            val quota = entitlementEnforcer.checkBackupUpload(userId, request.sizeBytes)
            if (quota is QuotaCheck.Denied) return@post respondQuotaExceeded(call, quota)
        }
        call.respond(
            HttpStatusCode.OK,
            BackupPartUploadStartResponse(
                uploadId = UUID.randomUUID().toString(),
                partSizeBytes = BACKUP_PART_BYTES,
                partCount = backupPartCount(request.sizeBytes),
            ),
        )
    }
}

private fun Route.uploadBackupPartRoute(
    tokenService: TokenService?,
    mediaStorage: LogDateBlobStorage?,
    metrics: SyncMetricsRegistry,
    rateLimiter: SlidingWindowRateLimiter?,
    transfers: TransferSlots,
) {
    put("/{uploadId}/parts/{index}", BackupPartUploadDocs.uploadBackupPart) {
        val start = System.currentTimeMillis()
        var bytes = 0L
        try {
            val userId = extractUserId(call, tokenService) ?: return@put
            val uploadId = call.parseUploadId() ?: return@put
            val index =
                call.parameters["index"]?.toIntOrNull()?.takeIf { it in 0 until backupPartCount(MAX_PARTED_BACKUP_BYTES) }
                    ?: return@put call.respondPartInvalid("index must be a part number from 0")
            val declared =
                call.request.headers[HttpHeaders.ContentLength]
                    ?.toLongOrNull()
                    ?.takeIf { it in 1..BACKUP_PART_BYTES }
                    ?: return@put call.respondPartInvalid("Content-Length must be between 1 and $BACKUP_PART_BYTES")
            val storage = mediaStorage ?: return@put call.respondBackupStorageUnavailable()
            val slot = transfers.tryStreamedUpload() ?: return@put call.respondTransferBusy(transfers.limits)
            try {
                if (rateLimiter != null && !rateLimiter.allow("backup-part:$userId", BACKUP_PART_RATE_LIMIT)) {
                    return@put respondRateLimited(call, rateLimiter, "backup-part:$userId", BACKUP_PART_RATE_LIMIT)
                }
                val blob =
                    LogDateBlobStreamRequest(
                        ownerId = userId,
                        namespace = LogDateBlobNamespace.BACKUP,
                        blobId = backupPartBlobId(uploadId, index),
                        contentType = "application/octet-stream",
                    )
                if (call.streamBackupPart(storage, blob, declared, transfers.limits.idleTimeout)) bytes = declared
            } finally {
                slot.release()
            }
        } finally {
            metrics.recordOperation("sync.backup.part", System.currentTimeMillis() - start, bytes > 0, bytes)
        }
    }
}

/** Streams the body into [blob] and answers the request. Returns whether the part was stored. */
private suspend fun ApplicationCall.streamBackupPart(
    storage: LogDateBlobStorage,
    blob: LogDateBlobStreamRequest,
    declared: Long,
    idleTimeout: kotlin.time.Duration,
): Boolean {
    val writer = withContext(Dispatchers.IO) { storage.openBlobWriter(blob) }
    val received =
        try {
            copyBodyExactly(writer.output, declared, idleTimeout)
        } catch (failure: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { runCatching { writer.abort() } }
            throw failure
        }
    if (received != BodyCopy.COMPLETE) {
        withContext(NonCancellable + Dispatchers.IO) { runCatching { writer.abort() } }
        if (received == BodyCopy.STALLED) {
            respond(HttpStatusCode.RequestTimeout, error("UPLOAD_STALLED", "The upload sent nothing for too long; send it again"))
        } else {
            respondPartInvalid("The body must be exactly as long as its Content-Length")
        }
        return false
    }
    withContext(Dispatchers.IO) { writer.commit() }
    respond(HttpStatusCode.NoContent)
    return true
}

private fun Route.completeBackupPartUploadRoute(
    tokenService: TokenService?,
    mediaStorage: LogDateBlobStorage?,
    metrics: SyncMetricsRegistry,
    transfers: TransferSlots,
    store: BackupStoreDependencies,
) {
    post("/{uploadId}/complete", BackupPartUploadDocs.completeBackupPartUpload) {
        val requestContext = currentCoroutineContext()
        val checkActive = { requestContext.ensureActive() }
        val start = System.currentTimeMillis()
        var success = false
        var bytes = 0L
        try {
            val userId = extractUserId(call, tokenService) ?: return@post
            val uploadId = call.parseUploadId() ?: return@post
            val request = call.receiveCompleteRequest() ?: return@post
            val storage = mediaStorage ?: return@post call.respondBackupStorageUnavailable()
            val slot = transfers.tryBuffered() ?: return@post call.respondTransferBusy(transfers.limits)
            try {
                bytes = request.sizeBytes
                success = call.storeBackupParts(storage, store, userId, uploadId, request, checkActive)
            } finally {
                slot.release()
            }
        } finally {
            metrics.recordOperation("sync.backup.upload", System.currentTimeMillis() - start, success, bytes)
        }
    }
}

/** Reads and checks the completion body, answering `400` and returning null when it is unusable. */
private suspend fun ApplicationCall.receiveCompleteRequest(): BackupPartUploadCompleteRequest? {
    val request = runCatching { receive<BackupPartUploadCompleteRequest>() }.getOrNull()
    if (request == null) {
        respondPartInvalid("Body must be JSON with deviceId, manifest and sizeBytes")
        return null
    }
    val deviceId = request.deviceId.trim()
    val problem =
        when {
            deviceId.isEmpty() || deviceId.length > 256 -> {
                "deviceId must be 1 to 256 characters"
            }

            request.manifest.isBlank() || request.manifest.encodeToByteArray().size > 256 * 1024 -> {
                "manifest must be present and at most 256 KiB"
            }

            request.sizeBytes !in 1..MAX_PARTED_BACKUP_BYTES -> {
                "sizeBytes must be between 1 and $MAX_PARTED_BACKUP_BYTES"
            }

            else -> {
                null
            }
        }
    if (problem != null) {
        respondPartInvalid(problem)
        return null
    }
    return request.copy(deviceId = deviceId)
}

/** Joins the parts, stores the backup and answers the request; deletes the parts once stored. */
private suspend fun ApplicationCall.storeBackupParts(
    storage: LogDateBlobStorage,
    store: BackupStoreDependencies,
    userId: UUID,
    uploadId: UUID,
    request: BackupPartUploadCompleteRequest,
    checkActive: () -> Unit,
): Boolean {
    val assembled =
        when (val result = assembleBackupParts(storage, userId, uploadId, request.sizeBytes, checkActive)) {
            is PartAssembly.Missing -> {
                respond(
                    HttpStatusCode.Conflict,
                    error("BACKUP_PART_MISSING", "Part ${result.index} has not arrived or has the wrong length; send it again"),
                )
                return false
            }

            is PartAssembly.Assembled -> {
                result.path
            }
        }
    val stored =
        try {
            storeReceivedBackup(
                userId = userId,
                received = ParsedBackupMultipartFile(request.deviceId, request.manifest, assembled, request.sizeBytes),
                storage = storage,
                encryptionService = store.encryptionService,
                backupRepository = store.backupRepository,
                entitlementEnforcer = store.entitlementEnforcer,
                checkActive = checkActive,
            )
        } finally {
            deletePrivateBackupTempFile(assembled)
        }
    if (stored) {
        withContext(NonCancellable + Dispatchers.IO) {
            deleteBackupParts(storage, userId, uploadId, backupPartCount(request.sizeBytes))
        }
    }
    return stored
}

private enum class BodyCopy { COMPLETE, WRONG_LENGTH, STALLED }

/** Streams the request body into [output], expecting exactly [declared] bytes and giving up after [idleTimeout] of silence. */
private suspend fun ApplicationCall.copyBodyExactly(
    output: java.io.OutputStream,
    declared: Long,
    idleTimeout: kotlin.time.Duration,
): BodyCopy {
    val channel = receiveChannel()
    val buffer = ByteArray(PART_READ_BYTES)
    var total = 0L
    while (true) {
        val read = withTimeoutOrNull(idleTimeout) { channel.readAvailable(buffer, 0, buffer.size) } ?: return BodyCopy.STALLED
        if (read < 0) break
        if (read == 0) continue
        total += read
        if (total > declared) return BodyCopy.WRONG_LENGTH
        withContext(Dispatchers.IO) { output.write(buffer, 0, read) }
    }
    return if (total == declared) BodyCopy.COMPLETE else BodyCopy.WRONG_LENGTH
}

private sealed interface PartAssembly {
    data class Assembled(
        val path: java.nio.file.Path,
    ) : PartAssembly

    data class Missing(
        val index: Int,
    ) : PartAssembly
}

/** Reads every part back in order into one private temporary file, checking each part's length. */
private suspend fun assembleBackupParts(
    storage: LogDateBlobStorage,
    userId: UUID,
    uploadId: UUID,
    sizeBytes: Long,
    checkActive: () -> Unit,
): PartAssembly =
    withContext(Dispatchers.IO) {
        val partCount = backupPartCount(sizeBytes)
        val target = createPrivateBackupTempFile("logdate-backup-upload-", ".bin")
        var assembled = false
        try {
            Files.newOutputStream(target).buffered().use { output ->
                for (index in 0 until partCount) {
                    checkActive()
                    val expected = if (index < partCount - 1) BACKUP_PART_BYTES else sizeBytes - BACKUP_PART_BYTES * (partCount - 1)
                    val path = storage.storagePathFor(userId, LogDateBlobNamespace.BACKUP, backupPartBlobId(uploadId, index))
                    val stream = storage.openBlob(path) ?: return@withContext PartAssembly.Missing(index)
                    stream.use {
                        if (it.size != expected) return@withContext PartAssembly.Missing(index)
                        copyBackupFile(it.input, output) { checkActive() }
                    }
                }
            }
            assembled = true
            PartAssembly.Assembled(target)
        } finally {
            if (!assembled) deletePrivateBackupTempFile(target)
        }
    }

/** Deletes an upload's staged parts; a part that isn't there is already gone. */
private fun deleteBackupParts(
    storage: LogDateBlobStorage,
    userId: UUID,
    uploadId: UUID,
    partCount: Int,
) {
    for (index in 0 until partCount) {
        val path = storage.storagePathFor(userId, LogDateBlobNamespace.BACKUP, backupPartBlobId(uploadId, index))
        try {
            storage.deleteBlob(path)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Napier.w("Failed to delete a staged backup part")
        }
    }
}

private suspend fun ApplicationCall.parseUploadId(): UUID? {
    val raw = parameters["uploadId"].orEmpty()
    return runCatching { UUID.fromString(raw) }.getOrNull()?.takeIf { it.toString() == raw.lowercase() } ?: run {
        respond(HttpStatusCode.BadRequest, error("INVALID_ID", "Invalid uploadId"))
        null
    }
}

private suspend fun ApplicationCall.respondPartInvalid(message: String) =
    respond(HttpStatusCode.BadRequest, error("VALIDATION_ERROR", message))

private suspend fun ApplicationCall.respondBackupStorageUnavailable() =
    respond(HttpStatusCode.ServiceUnavailable, error("BACKUP_STORAGE_UNAVAILABLE", "Backup storage not configured"))
