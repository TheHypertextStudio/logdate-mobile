package app.logdate.server.routes.sync

import app.logdate.server.auth.AccessTokenVerifier
import app.logdate.server.auth.AccountStorageModes
import app.logdate.server.crypto.EncryptionService
import app.logdate.server.entitlements.EntitlementEnforcer
import app.logdate.server.entitlements.QuotaCheck
import app.logdate.server.identity.adoption.IdentityWriteRefusedException
import app.logdate.server.logdate.LegacyObjectUploads
import app.logdate.server.logdate.LegacyObjectWriteFailure
import app.logdate.server.logdate.LogDateBlobNamespace
import app.logdate.server.logdate.LogDateBlobStorage
import app.logdate.server.logdate.LogDateBlobWriteRequest
import app.logdate.server.logdate.LogDateMedia
import app.logdate.server.logdate.LogDateMediaBlobRepository
import app.logdate.server.logdate.deleteLegacyObject
import app.logdate.server.ratelimit.SlidingWindowRateLimiter
import app.logdate.server.responses.error
import app.logdate.server.routes.docs.SyncStorageDocs
import app.logdate.server.sync.StaleWriteException
import app.logdate.server.sync.SyncMetricsRegistry
import app.logdate.shared.model.sync.DeviceId
import app.logdate.shared.model.sync.MediaMetadataResponse
import app.logdate.shared.model.sync.MediaUploadReceiptResponse
import app.logdate.shared.model.sync.MediaUploadResponse
import io.github.aakira.napier.Napier
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * `/api/v1/media` endpoints — multipart upload, metadata read, binary download, delete.
 *
 * Write path is gated twice before any work happens:
 *   1. [SlidingWindowRateLimiter] throttles by `media-upload:<accountId>`.
 *   2. [EntitlementEnforcer] rejects over-quota uploads with 402.
 */
internal fun Route.syncMediaRoutes(
    accessTokenVerifier: AccessTokenVerifier?,
    storageModes: AccountStorageModes?,
    mediaStorage: LogDateBlobStorage?,
    metrics: SyncMetricsRegistry,
    encryptionService: EncryptionService,
    mediaBlobRepository: LogDateMediaBlobRepository,
    entitlementEnforcer: EntitlementEnforcer?,
    rateLimiter: SlidingWindowRateLimiter?,
    config: SyncRouteConfig,
    objectUploads: LegacyObjectUploads,
) {
    val mediaAccessPolicy = config.mediaAccessPolicy
    route("/media") {
        get("/receipts/{intent}", SyncStorageDocs.getMediaUploadReceipt) {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            val userId = extractSyncUserId(call, accessTokenVerifier, storageModes) ?: return@get
            val intent = call.parameters["intent"].orEmpty()
            if (!isMediaUploadIntent(intent)) {
                return@get call.respond(HttpStatusCode.BadRequest, error("INVALID_UPLOAD_INTENT", "Invalid upload identity"))
            }
            val stored = mediaBlobRepository.getMedia(userId, receiptMediaId(userId, intent))
            val receipt =
                stored?.let {
                    MediaUploadResponse(
                        it.contentId,
                        it.mediaId,
                        resolveMediaDownloadUrl(call, it, mediaStorage, mediaAccessPolicy),
                        it.createdAt,
                        intent,
                    )
                }
            call.respond(MediaUploadReceiptResponse(receipt))
        }
        post(SyncStorageDocs.uploadMedia) {
            val start = System.currentTimeMillis()
            var success = false
            var bytes = 0L
            try {
                val userId = extractSyncUserId(call, accessTokenVerifier, storageModes) ?: return@post

                if (rateLimiter != null && !rateLimiter.allow("media-upload:$userId", MEDIA_UPLOAD_RATE_LIMIT)) {
                    return@post respondRateLimited(call, rateLimiter, "media-upload:$userId", MEDIA_UPLOAD_RATE_LIMIT)
                }

                val intent = call.request.headers["X-LogDate-Upload-Intent"]
                if (intent != null && !isMediaUploadIntent(intent)) {
                    return@post call.respond(HttpStatusCode.BadRequest, error("INVALID_UPLOAD_INTENT", "Invalid upload identity"))
                }
                // Receipts are immutable. A replay cannot replace a previously committed version.
                if (intent != null) {
                    mediaBlobRepository.getMedia(userId, receiptMediaId(userId, intent))?.let { stored ->
                        call.respond(
                            HttpStatusCode.Created,
                            MediaUploadResponse(
                                stored.contentId,
                                stored.mediaId,
                                resolveMediaDownloadUrl(call, stored, mediaStorage, mediaAccessPolicy),
                                stored.createdAt,
                                intent,
                            ),
                        )
                        success = true
                        return@post
                    }
                }
                val req = call.receiveMediaMultipartUpload() ?: return@post
                bytes = req.sizeBytes
                Napier.d("Media upload started")

                if (entitlementEnforcer != null) {
                    val quota = entitlementEnforcer.checkMediaUpload(userId, req.sizeBytes)
                    if (quota is QuotaCheck.Denied) {
                        return@post respondQuotaExceeded(call, quota)
                    }
                }
                // A random id made every retry a new upload: if the response was lost after the
                // blob was stored, the client retried and the server kept both copies, charging
                // the account twice and orphaning one. Deriving the id from what the upload is
                // means a retry upserts the same row instead.
                val mediaId =
                    intent?.let { receiptMediaId(userId, it) }
                        ?: UUID.nameUUIDFromBytes("$userId:${req.contentId}:${req.fileName}".encodeToByteArray()).toString()

                @OptIn(ExperimentalUuidApi::class)
                val storageId = "$mediaId-${Uuid.random()}"

                val previous =
                    try {
                        mediaBlobRepository.getMedia(userId, mediaId)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (cause: Exception) {
                        Napier.e("Media metadata lookup failed")
                        return@post call.respond(
                            HttpStatusCode.InternalServerError,
                            error("MEDIA_METADATA_READ_FAILED", "Failed to read existing media metadata"),
                        )
                    }

                val encryptedPayload =
                    runCatching {
                        encryptionService.processMediaUpload(
                            req.data,
                            userId.toString(),
                            mediaId,
                            req.contentId,
                        )
                    }.getOrElse { error ->
                        if (error is CancellationException) throw error
                        Napier.e("Media encryption failed")
                        return@post call.respond(
                            HttpStatusCode.InternalServerError,
                            error("MEDIA_ENCRYPT_FAILED", "Failed to encrypt media payload"),
                        )
                    }

                val media =
                    LogDateMedia(
                        mediaId = mediaId,
                        contentId = req.contentId,
                        userId = userId,
                        fileName = req.fileName,
                        mimeType = req.mimeType,
                        sizeBytes = req.sizeBytes,
                        data = if (mediaStorage == null) encryptedPayload.data else ByteArray(0),
                        storagePath = null,
                        createdAt = System.currentTimeMillis(),
                        version = 0L,
                        deviceId = DeviceId(req.deviceId),
                        encryptionVersion = 1,
                        encryptionKeyId = "default",
                        encryptionMode = "SERVER",
                    )
                var candidate: String? = null
                val stored =
                    try {
                        if (mediaStorage == null) {
                            mediaBlobRepository.upsertMedia(userId, media)
                        } else {
                            val write =
                                LogDateBlobWriteRequest(
                                    ownerId = userId,
                                    namespace = LogDateBlobNamespace.MEDIA,
                                    blobId = storageId,
                                    fileName = req.fileName,
                                    contentType = req.mimeType,
                                    bytes = encryptedPayload.data,
                                )
                            withContext(Dispatchers.IO) {
                                objectUploads.upload(
                                    owner = userId,
                                    namespace = LogDateBlobNamespace.MEDIA,
                                    plannedPath = { mediaStorage.plannedPath(write) },
                                    write = { mediaStorage.putBlob(write).also { candidate = it } },
                                    canonicalPath = LogDateMedia::storagePath,
                                    publish = { path, epoch ->
                                        mediaBlobRepository.upsertMedia(userId, media.copy(storagePath = path), epoch)
                                    },
                                    deleteObject = { mediaStorage.deleteLegacyObject(it) },
                                )
                            }
                        }
                    } catch (canceled: CancellationException) {
                        throw canceled
                    } catch (failure: IdentityWriteRefusedException) {
                        return@post call.respondIdentityWriteRefusal(failure)
                    } catch (failure: StaleWriteException) {
                        val path = candidate
                        if (!objectUploads.isDurable && path != null) {
                            cleanupMediaObject(userId, path, mediaBlobRepository, mediaStorage)
                        }
                        metrics.recordConflict()
                        return@post call.respond(
                            HttpStatusCode.Conflict,
                            error("DELETION_CONFLICT", "This media was deleted; keep the edit for review"),
                        )
                    } catch (_: LegacyObjectWriteFailure) {
                        Napier.e("Media storage upload failed")
                        return@post call.respond(
                            HttpStatusCode.InternalServerError,
                            error("MEDIA_UPLOAD_FAILED", "Failed to store media"),
                        )
                    } catch (_: Exception) {
                        Napier.e("Media metadata persistence failed")
                        return@post call.respond(
                            HttpStatusCode.InternalServerError,
                            error("MEDIA_METADATA_WRITE_FAILED", "Failed to store media metadata"),
                        )
                    }
                val storagePath = candidate
                if (!objectUploads.isDurable && intent != null && storagePath != null && stored.storagePath != storagePath) {
                    cleanupMediaObject(userId, storagePath, mediaBlobRepository, mediaStorage)
                }
                val previousPath = previous?.storagePath
                if (previousPath != null && previousPath != stored.storagePath) {
                    cleanupMediaObject(userId, previousPath, mediaBlobRepository, mediaStorage)
                }
                val downloadUrl = resolveMediaDownloadUrl(call, stored, mediaStorage, mediaAccessPolicy)
                val response =
                    MediaUploadResponse(
                        contentId = stored.contentId,
                        mediaId = stored.mediaId,
                        downloadUrl = downloadUrl,
                        uploadedAt = stored.createdAt,
                        uploadIntentId = intent,
                    )
                call.response.headers.append(HttpHeaders.Location, "/api/v1/media/${stored.mediaId}")
                call.respond(HttpStatusCode.Created, response)
                success = true
            } catch (_: StaleWriteException) {
                metrics.recordConflict()
                call.respond(HttpStatusCode.Conflict, error("DELETION_CONFLICT", "This item was deleted; keep the edit for review"))
            } finally {
                metrics.recordOperation(METRIC_MEDIA_UPLOAD, System.currentTimeMillis() - start, success, bytes)
            }
        }

        get("/{mediaId}", SyncStorageDocs.getMediaMetadata) {
            val start = System.currentTimeMillis()
            var success = false
            try {
                val userId = extractSyncUserId(call, accessTokenVerifier, storageModes) ?: return@get
                val mediaId = call.requiredPathParam("mediaId")
                val record =
                    mediaBlobRepository.getMedia(userId, mediaId)
                        ?: return@get call.respond(HttpStatusCode.NotFound, error("NOT_FOUND", "Media not found"))

                call.respond(
                    MediaMetadataResponse(
                        contentId = record.contentId,
                        mediaId = record.mediaId,
                        fileName = record.fileName,
                        mimeType = record.mimeType,
                        sizeBytes = record.sizeBytes,
                        downloadUrl = resolveMediaDownloadUrl(call, record, mediaStorage, mediaAccessPolicy),
                        uploadedAt = record.createdAt,
                    ),
                )
                success = true
            } finally {
                metrics.recordOperation(METRIC_MEDIA_DOWNLOAD, System.currentTimeMillis() - start, success)
            }
        }

        get("/{mediaId}/binary", SyncStorageDocs.downloadMedia) {
            val start = System.currentTimeMillis()
            var success = false
            var bytes = 0L
            try {
                val userId = extractSyncUserId(call, accessTokenVerifier, storageModes) ?: return@get
                val mediaId = call.requiredPathParam("mediaId")
                val record =
                    mediaBlobRepository.getMedia(userId, mediaId)
                        ?: return@get call.respond(HttpStatusCode.NotFound, error("NOT_FOUND", "Media not found"))

                val encryptedPayload =
                    if (record.storagePath != null) {
                        val storage =
                            mediaStorage
                                ?: return@get call.respond(
                                    HttpStatusCode.InternalServerError,
                                    error("MEDIA_STORAGE_UNAVAILABLE", "Media storage not configured"),
                                )
                        withContext(Dispatchers.IO) { storage.getBlob(record.storagePath) }
                            ?: return@get call.respond(
                                HttpStatusCode.NotFound,
                                error("NOT_FOUND", "Media not found"),
                            )
                    } else {
                        record.data
                    }

                val payload =
                    runCatching {
                        encryptionService.processMediaDownload(encryptedPayload, shouldDecrypt = true)
                    }.getOrElse { error ->
                        Napier.e("Media decryption failed")
                        return@get call.respond(
                            HttpStatusCode.InternalServerError,
                            error("MEDIA_DECRYPT_FAILED", "Failed to decrypt media payload"),
                        )
                    }
                bytes = payload.size.toLong()
                val contentType =
                    runCatching { ContentType.parse(record.mimeType) }.getOrElse { ContentType.Application.OctetStream }
                call.respondBytes(payload, contentType)
                success = true
            } finally {
                metrics.recordOperation(METRIC_MEDIA_DOWNLOAD, System.currentTimeMillis() - start, success, bytes)
            }
        }

        delete("/{mediaId}", SyncStorageDocs.deleteMedia) {
            val start = System.currentTimeMillis()
            var success = false
            try {
                val userId = extractSyncUserId(call, accessTokenVerifier, storageModes) ?: return@delete
                val mediaId = call.requiredPathParam("mediaId")
                val record = mediaBlobRepository.getMedia(userId, mediaId)
                if (record != null) {
                    val storagePath = record.storagePath
                    if (storagePath != null) {
                        mediaStorage
                            ?: return@delete call.respond(
                                HttpStatusCode.ServiceUnavailable,
                                error("MEDIA_STORAGE_UNAVAILABLE", "Media storage not configured"),
                            )
                    }
                }
                val deleted =
                    withContext(Dispatchers.IO) {
                        mediaBlobRepository.deleteMediaWithObject(userId, mediaId, System.currentTimeMillis()) { path ->
                            mediaStorage?.deleteLegacyObject(path) ?: false
                        }
                    }
                if (!deleted) {
                    Napier.w("Media deletion failed")
                    return@delete call.respond(
                        HttpStatusCode.InternalServerError,
                        error("MEDIA_DELETE_FAILED", "Failed to delete media blob"),
                    )
                }
                call.respond(HttpStatusCode.NoContent)
                success = true
            } catch (failure: IdentityWriteRefusedException) {
                call.respondIdentityWriteRefusal(failure)
            } finally {
                metrics.recordOperation(METRIC_MEDIA_DELETE, System.currentTimeMillis() - start, success)
            }
        }
    }
}

/** Random client intent IDs are scoped to the authenticated owner, never caller-supplied owner IDs. */
private fun isMediaUploadIntent(value: String): Boolean =
    value.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))

private fun receiptMediaId(
    owner: UUID,
    intent: String,
): String = "receipt-v1-" + UUID.nameUUIDFromBytes("$owner:upload-intent:$intent".encodeToByteArray()).toString()
