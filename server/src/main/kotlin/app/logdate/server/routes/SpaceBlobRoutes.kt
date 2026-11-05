package app.logdate.server.routes

import app.logdate.lexicon.app.logdate.space.UploadBlobOutput
import app.logdate.server.database.toJavaUUID
import app.logdate.server.entitlements.EntitlementEnforcer
import app.logdate.server.identity.adoption.IdentityWriteRefusedException
import app.logdate.server.logdate.LogDateBlobNamespace
import app.logdate.server.logdate.LogDateBlobStorage
import app.logdate.server.logdate.LogDateBlobStreamRequest
import app.logdate.server.logdate.SpaceObjectUploads
import app.logdate.server.routes.docs.XrpcSpaceDocs
import app.logdate.server.space.SpaceBlob
import app.logdate.server.space.SpaceBlobPurgingException
import app.logdate.server.space.SpaceQuota
import app.logdate.server.space.SpaceQuotaExceededException
import app.logdate.server.space.SpaceStore
import app.logdate.server.space.SpaceUploadReservation
import app.logdate.server.transfer.TransferSlots
import app.logdate.space.BlobUploadReceipt
import app.logdate.space.BlobUploadReceiptLookup
import io.github.aakira.napier.Napier
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import studio.hypertext.atproto.lexicon.data.Blob
import studio.hypertext.atproto.lexicon.data.CidLink
import studio.hypertext.atproto.pds.PdsErrorResponse
import studio.hypertext.atproto.repo.Cid
import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val SPACE_BLOB_MIME_TYPE = "application/octet-stream"

/** The record store, object storage and transfer slots the blob methods need together. */
internal class SpaceObjects(
    val store: SpaceStore,
    val storage: LogDateBlobStorage,
    val transfers: TransferSlots,
    val uploads: SpaceObjectUploads = SpaceObjectUploads.Direct,
)

/**
 * `app.logdate.space.uploadBlob`. The upload must declare its length, so its room is held against the
 * quota before any byte arrives. It then takes a transfer slot, which is checked before the request
 * budget so a refused transfer costs the account nothing, and streams the body to object storage.
 */
@OptIn(ExperimentalUuidApi::class)
internal fun Route.uploadBlob(
    blobs: SpaceObjects?,
    gate: SpaceGate,
    quota: EntitlementEnforcer?,
    maxBlobBytes: Long,
) {
    post("/app.logdate.space.uploadBlob", XrpcSpaceDocs.uploadBlob) {
        try {
            val (account, objects) = gate.authorize(call, blobs) ?: return@post
            if (!gate.writable(call, account)) return@post
            val space = call.ownSpace(call.request.queryParameters["space"], account, BLOB_NOT_FOUND) ?: return@post
            val declared =
                call.request.headers[HttpHeaders.ContentLength]
                    ?.toLongOrNull()
                    ?.takeIf { it >= 0 }
                    ?: return@post call.respondLengthRequired()
            val intent = call.request.headers["X-LogDate-Upload-Intent"]
            if (intent != null && !isSpaceUploadIntent(intent)) return@post call.respondInvalid("Invalid upload identity")
            if (intent != null) {
                if (!gate.spend(call, account, SpaceBudget.READS)) return@post
                objects.confirmedReceipt(space.uri, intent)?.let { stored ->
                    call.respond(stored.uploadOutput())
                    return@post
                }
            }
            if (declared > maxBlobBytes) return@post call.respondBlobTooLarge(maxBlobBytes)
            val slot = objects.transfers.tryStreamedUpload() ?: return@post call.respondServerBusy(objects.transfers.limits)
            try {
                if (!gate.spend(call, account, SpaceBudget.BLOB_UPLOADS)) return@post
                val ownerId = account.id.toJavaUUID()
                val limit = quota?.spaceQuota(ownerId)
                val reservation =
                    limit?.let {
                        try {
                            objects.store.reserveUpload(ownerId, declared, it, account.identityEpoch)
                        } catch (e: SpaceQuotaExceededException) {
                            return@post call.respondSpaceQuotaExceeded(e)
                        }
                    }
                try {
                    call.receiveAndStoreBlob(objects, ownerId, account.identityEpoch, space.uri, declared, limit, reservation, intent)
                } finally {
                    reservation?.let { held ->
                        withContext(NonCancellable) {
                            try {
                                objects.store.releaseUpload(held)
                            } catch (_: IdentityWriteRefusedException) {
                                Napier.w("Upload reservation retained for maintenance")
                            }
                        }
                    }
                }
            } finally {
                slot.release()
            }
        } catch (canceled: kotlinx.coroutines.CancellationException) {
            throw canceled
        } catch (failure: Exception) {
            call.respondForBlobError(failure)
        }
    }
}

/**
 * Streams the request body into a new object, hashing it on the way, then checks that storage holds
 * exactly those bytes before recording the blob in [space], which is when it becomes readable. The
 * row is written with [quota] checked in the same transaction. Answers the request either way.
 */
@OptIn(ExperimentalUuidApi::class)
private suspend fun ApplicationCall.receiveAndStoreBlob(
    objects: SpaceObjects,
    ownerId: UUID,
    identityEpoch: Long,
    space: String,
    declared: Long,
    quota: SpaceQuota?,
    reservation: SpaceUploadReservation?,
    intent: String?,
) {
    val idleTimeout = objects.transfers.limits.idleTimeout
    val request = LogDateBlobStreamRequest(ownerId, LogDateBlobNamespace.SPACE, Uuid.random().toString(), SPACE_BLOB_MIME_TYPE)
    val upload = withContext(Dispatchers.IO) { objects.uploads.begin(ownerId, identityEpoch, space, objects.storage, request) }
    val writer = withContext(Dispatchers.IO) { objects.storage.openBlobWriter(request) }
    check(upload == null || writer.storagePath == upload.path) { "The provider wrote an unexpected object path" }
    val received =
        try {
            streamSpaceBlob(writer, limit = declared, idleTimeout = idleTimeout)
                ?.takeIf { it.size == declared }
                .also { if (it != null) withContext(Dispatchers.IO) { writer.commit() } }
        } catch (error: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                val cleanupFailure = runCatching { objects.uploads.discard(upload, writer, objects.storage) }.exceptionOrNull()
                cleanupFailure?.let(error::addSuppressed)
            }
            if (error is SpaceTransferStalledException) return respondUploadStalled()
            throw error
        }
    if (received == null) {
        objects.uploads.discard(upload, writer, objects.storage)
        return respondInvalid("The body must be exactly as long as its Content-Length")
    }
    val path = writer.storagePath
    val blob =
        SpaceBlob(cid = received.cid, mimeType = SPACE_BLOB_MIME_TYPE, size = received.size, storagePath = path, uploadIntent = intent)
    val stored =
        try {
            objects.uploads.register(upload, objects.storage) {
                if (upload == null) {
                    objects.store.putBlob(ownerId, space, blob, quota, reservation, identityEpoch)
                } else {
                    objects.store.putAdmittedBlob(ownerId, space, blob, upload.id, quota, reservation, upload.origin.identityEpoch)
                }
            }
        } catch (error: Throwable) {
            if (upload == null) {
                withContext(NonCancellable + Dispatchers.IO) {
                    objects.storage.deleteUnsavedUpload(objects.store, space, received.cid, path, error)
                }
            }
            if (error is SpaceQuotaExceededException) return respondSpaceQuotaExceeded(error)
            if (error is SpaceBlobPurgingException) {
                return respondServerBusy(objects.transfers.limits, "This upload is temporarily unavailable.")
            }
            throw error
        }
    if (upload == null && stored.storagePath != path) withContext(Dispatchers.IO) { objects.storage.deleteBlobIfExists(path) }
    respond(stored.uploadOutput())
}

private fun SpaceBlob.uploadOutput() =
    UploadBlobOutput(
        blob = Blob(ref = CidLink(cid = Cid.require(cid)), mimeType = mimeType, size = size),
    )

private fun isSpaceUploadIntent(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

/** A missing or unreadable backing object is not permission to replay an acknowledged payload. */
private suspend fun SpaceObjects.confirmedReceipt(
    space: String,
    intent: String,
): SpaceBlob? {
    val blob = store.getBlobUploadReceipt(space, intent) ?: return null
    withContext(Dispatchers.IO) {
        val stream = checkNotNull(storage.openBlob(blob.storagePath)) { "Stored receipt object is unavailable" }
        stream.use { check(it.size == blob.size) { "Stored receipt object size mismatch" } }
    }
    return blob
}

internal fun Route.getBlobUploadReceipt(
    blobs: SpaceObjects?,
    gate: SpaceGate,
) {
    get("/app.logdate.space.getBlobUploadReceipt", XrpcSpaceDocs.getBlobUploadReceipt) {
        val (account, objects) = gate.admit(call, blobs, SpaceBudget.READS) ?: return@get
        val space = call.ownSpace(call.request.queryParameters["space"], account, BLOB_NOT_FOUND) ?: return@get
        val intent = call.request.queryParameters["intent"].orEmpty()
        if (!isSpaceUploadIntent(intent)) return@get call.respondInvalid("Invalid upload identity")
        val receipt = objects.confirmedReceipt(space.uri, intent)?.let { BlobUploadReceipt(it.cid, it.size) }
        call.response.header(HttpHeaders.CacheControl, "private, no-store")
        call.respond(BlobUploadReceiptLookup(true, receipt))
    }
}

/**
 * `app.logdate.space.getBlob`. Takes a download slot, checked before the request budget so a refused
 * transfer costs the account nothing, then streams the object from storage to the response.
 */
internal fun Route.getBlob(
    blobs: SpaceObjects?,
    gate: SpaceGate,
) {
    get("/app.logdate.space.getBlob", XrpcSpaceDocs.getBlob) {
        val (account, objects) = gate.authorize(call, blobs) ?: return@get
        val space = call.ownSpace(call.request.queryParameters["space"], account, BLOB_NOT_FOUND) ?: return@get
        val cid = call.request.queryParameters["cid"]?.takeIf(::isCid) ?: return@get call.respondInvalid("cid must be CID-shaped")
        val slot = objects.transfers.tryDownload() ?: return@get call.respondServerBusy(objects.transfers.limits)
        val stream =
            try {
                if (!gate.spend(call, account, SpaceBudget.READS)) return@get slot.release()
                val blob = objects.store.getBlob(space.uri, cid)
                blob?.let { withContext(Dispatchers.IO) { objects.storage.openBlob(it.storagePath) } }
            } catch (error: Throwable) {
                slot.release()
                throw error
            }
        if (stream == null) {
            slot.release()
            return@get call.respondMissing(BLOB_NOT_FOUND)
        }
        val content = SpaceBlobContent(stream, slot, objects.transfers.limits.idleTimeout)
        try {
            // Decrypted on the device only; nothing between it and the server should keep a copy.
            call.response.header(HttpHeaders.CacheControl, "private, no-store")
            call.respond(content)
        } catch (error: Throwable) {
            withContext(NonCancellable) { content.release() }
            throw error
        }
    }
}

/**
 * Deletes the object at [path] after a failed metadata write, unless the write saved a row that
 * points at it anyway, as when the call is cancelled after the transaction commits. When the row
 * can't be read, the object stays: an orphaned object is harmless, a row without its object isn't.
 * Failures here are added to [error] rather than replacing it.
 */
private suspend fun LogDateBlobStorage.deleteUnsavedUpload(
    store: SpaceStore,
    space: String,
    cid: String,
    path: String,
    error: Throwable,
) {
    val saved = runCatching { store.getBlob(space, cid)?.storagePath == path }
    saved.exceptionOrNull()?.let(error::addSuppressed)
    if (saved.getOrNull() != false) return
    runCatching { deleteBlobIfExists(path) }.exceptionOrNull()?.let(error::addSuppressed)
}

private suspend fun ApplicationCall.respondBlobTooLarge(maxBlobBytes: Long) =
    respond(HttpStatusCode.PayloadTooLarge, PdsErrorResponse("BlobTooLarge", "Blobs can be at most $maxBlobBytes bytes"))

private suspend fun ApplicationCall.respondUploadStalled() =
    respond(HttpStatusCode.RequestTimeout, PdsErrorResponse(UPLOAD_STALLED, "The upload sent nothing for too long; send it again"))

/** The error an upload whose client stopped sending answers. */
internal const val UPLOAD_STALLED = "UploadStalled"
