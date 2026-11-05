package app.logdate.server.routes

import app.logdate.server.identity.adoption.IDENTITY_ADOPTION_MAX_BLOB_BYTES
import app.logdate.server.identity.adoption.IdentityAdoptionAction
import app.logdate.server.identity.adoption.IdentityAdoptionMediaUpload
import app.logdate.server.identity.adoption.IdentityAdoptionPrivateMediaReceipt
import app.logdate.shared.model.identity.IdentityAdoptionMediaBeginRequest
import app.logdate.shared.model.identity.IdentityAdoptionMediaData
import app.logdate.shared.model.identity.IdentityAdoptionMediaResponse
import app.logdate.shared.model.identity.IdentityAdoptionMediaUploadData
import app.logdate.shared.model.identity.IdentityAdoptionMediaUploadProof
import app.logdate.shared.model.identity.IdentityAdoptionMediaUploadReceiptView
import app.logdate.shared.model.identity.IdentityAdoptionMediaUploadResponse
import app.logdate.shared.model.identity.IdentityAdoptionMediaVerificationView
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.util.Base64

internal suspend fun IdentityAdoptionCopyContext.beginPrivateBlob(call: ApplicationCall) =
    withOperation(call) { account, current, service ->
        val ingress = service.privateMedia ?: return@withOperation missing(call)
        val request = call.receiveAccountRequest<IdentityAdoptionMediaBeginRequest>(metrics) ?: return@withOperation
        val operation = expected(account, current, request.expected, request.phase) ?: return@withOperation changed(call)
        val challenge =
            finalizationChallenge(
                request.challenge,
                IdentityAdoptionAction.BEGIN_BLOB,
                request.signature,
            ) ?: return@withOperation invalid(call)
        val value = request.upload
        val upload = IdentityAdoptionMediaUpload(value.uploadId, value.source.domain(), value.sourceDigest, value.cid, value.size)
        val receipt = ingress.begin(operation, challenge, request.signature, upload) ?: return@withOperation changed(call)
        val view = view(call, operation) ?: return@withOperation
        call.respond(IdentityAdoptionMediaUploadResponse(true, IdentityAdoptionMediaUploadData(view, receipt.wire())))
    }

internal suspend fun IdentityAdoptionCopyContext.writePrivateBlob(call: ApplicationCall) =
    withOperation(call) { account, current, service ->
        val ingress = service.privateMedia ?: return@withOperation missing(call)
        val request = uploadProof(call) ?: return@withOperation invalid(call)
        val length =
            call.request.headers[HttpHeaders.ContentLength]
                ?.toLongOrNull()
                ?.takeIf { it in 0..IDENTITY_ADOPTION_MAX_BLOB_BYTES }
                ?: return@withOperation invalid(call)
        val operation = expected(account, current, request.expected, request.phase) ?: return@withOperation changed(call)
        val challenge =
            finalizationChallenge(
                request.challenge,
                IdentityAdoptionAction.BEGIN_BLOB,
                request.signature,
            ) ?: return@withOperation invalid(call)
        val transfers = ingress.transfers
        val slot = transfers.tryStreamedUpload() ?: return@withOperation call.respondServerBusy(transfers.limits)
        try {
            val channel = call.receiveChannel()
            val input =
                object : InputStream() {
                    override fun read(): Int {
                        val one = ByteArray(1)
                        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
                    }

                    override fun read(
                        bytes: ByteArray,
                        offset: Int,
                        count: Int,
                    ): Int =
                        runBlocking {
                            withTimeout(transfers.limits.idleTimeout) { channel.readAvailable(bytes, offset, count) }
                        }
                }
            val receipt =
                ingress.write(
                    operation,
                    challenge,
                    request.signature,
                    request.uploadId,
                    length,
                    input,
                ) ?: return@withOperation changed(call)
            val view = view(call, operation) ?: return@withOperation
            call.respond(IdentityAdoptionMediaUploadResponse(true, IdentityAdoptionMediaUploadData(view, receipt.wire())))
        } finally {
            slot.release()
        }
    }

internal suspend fun IdentityAdoptionCopyContext.verifyPrivateBlob(call: ApplicationCall) =
    withOperation(call) { account, current, service ->
        val ingress = service.privateMedia ?: return@withOperation missing(call)
        val request = call.receiveAccountRequest<IdentityAdoptionMediaUploadProof>(metrics) ?: return@withOperation
        val operation = expected(account, current, request.expected, request.phase) ?: return@withOperation changed(call)
        val challenge =
            finalizationChallenge(
                request.challenge,
                IdentityAdoptionAction.READ_STAGED,
                request.signature,
            ) ?: return@withOperation invalid(call)
        val media = ingress.verify(operation, challenge, request.signature, request.uploadId) ?: return@withOperation changed(call)
        val view = view(call, operation) ?: return@withOperation
        call.respond(
            IdentityAdoptionMediaResponse(
                true,
                IdentityAdoptionMediaData(
                    view,
                    IdentityAdoptionMediaVerificationView(
                        media.operationId,
                        media.admissionId,
                        media.generation,
                        media.cid,
                        media.size,
                        media.mimeType,
                    ),
                ),
            ),
        )
    }

internal suspend fun IdentityAdoptionCopyContext.discardPrivateBlob(call: ApplicationCall) =
    withOperation(call) { account, current, service ->
        val ingress = service.privateMedia ?: return@withOperation missing(call)
        val request = call.receiveAccountRequest<IdentityAdoptionMediaUploadProof>(metrics) ?: return@withOperation
        val operation = expected(account, current, request.expected, request.phase) ?: return@withOperation changed(call)
        val challenge =
            finalizationChallenge(request.challenge, IdentityAdoptionAction.BEGIN_BLOB, request.signature)
                ?: return@withOperation invalid(call)
        val receipt = ingress.discard(operation, challenge, request.signature, request.uploadId) ?: return@withOperation changed(call)
        val view = view(call, operation) ?: return@withOperation
        call.respond(IdentityAdoptionMediaUploadResponse(true, IdentityAdoptionMediaUploadData(view, receipt.wire())))
    }

internal suspend fun IdentityAdoptionCopyContext.readPrivateBlob(call: ApplicationCall) =
    withOperation(call) { account, current, service ->
        val ingress = service.privateMedia ?: return@withOperation missing(call)
        val request = call.receiveAccountRequest<IdentityAdoptionMediaUploadProof>(metrics) ?: return@withOperation
        val operation = expected(account, current, request.expected, request.phase) ?: return@withOperation changed(call)
        val challenge =
            finalizationChallenge(
                request.challenge,
                IdentityAdoptionAction.READ_STAGED,
                request.signature,
            ) ?: return@withOperation invalid(call)
        val transfers = ingress.transfers
        val slot = transfers.tryDownload() ?: return@withOperation call.respondServerBusy(transfers.limits)
        val stream =
            try {
                ingress.read(operation, challenge, request.signature, request.uploadId)
            } catch (error: Throwable) {
                slot.release()
                throw error
            }
        if (stream == null) {
            slot.release()
            return@withOperation changed(call)
        }
        val content = SpaceBlobContent(stream, slot, transfers.limits.idleTimeout)
        try {
            call.respond(content)
        } catch (error: Throwable) {
            content.release()
            throw error
        }
    }

private fun uploadProof(call: ApplicationCall): IdentityAdoptionMediaUploadProof? {
    val text = call.request.headers["X-LogDate-Adoption-Proof"] ?: return null
    if (text.length !in 1..16384 || !text.matches(Regex("[A-Za-z0-9_-]+"))) return null
    return runCatching {
        val bytes = Base64.getUrlDecoder().decode(text)
        require(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == text)
        Json.decodeFromString<IdentityAdoptionMediaUploadProof>(bytes.decodeToString(throwOnInvalidSequence = true))
    }.getOrNull()
}

private fun IdentityAdoptionPrivateMediaReceipt.wire() = IdentityAdoptionMediaUploadReceiptView(uploadId, generation, cid, size, state)
