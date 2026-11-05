package app.logdate.server.routes

import app.logdate.server.identity.adoption.IdentityAdoptionAction
import app.logdate.server.logdate.LogDateBlobStream
import app.logdate.shared.model.identity.IdentityAdoptionMediaSourceRequest
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import java.io.FilterInputStream

internal suspend fun IdentityAdoptionCopyContext.readSourceMedia(call: ApplicationCall) =
    withOperation(call) { account, current, service ->
        val ingress = service.privateMedia ?: return@withOperation missing(call)
        val request = call.receiveAccountRequest<IdentityAdoptionMediaSourceRequest>(metrics) ?: return@withOperation
        val operation = expected(account, current, request.expected, request.phase) ?: return@withOperation changed(call)
        val challenge =
            finalizationChallenge(request.challenge, IdentityAdoptionAction.READ_STAGED, request.signature)
                ?: return@withOperation invalid(call)
        val transfers = ingress.transfers
        val buffered = transfers.tryBuffered() ?: return@withOperation call.respondServerBusy(transfers.limits)
        val download = transfers.tryDownload()
        if (download == null) {
            buffered.release()
            return@withOperation call.respondServerBusy(transfers.limits)
        }
        val source =
            try {
                ingress.readSource(operation, challenge, request.signature, request.source.domain(), request.sourceDigest)
            } catch (error: Throwable) {
                buffered.release()
                download.release()
                throw error
            }
        if (source == null) {
            buffered.release()
            download.release()
            return@withOperation changed(call)
        }
        call.response.header("X-LogDate-Adoption-Media-Encoding", source.encoding)
        call.response.header("X-LogDate-Adoption-Media-SHA256", source.sha256)
        call.response.header("X-LogDate-Adoption-Source-Digest", source.sourceDigest)
        val input =
            object : FilterInputStream(source.stream.input) {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        buffered.release()
                    }
                }
            }
        val content = SpaceBlobContent(LogDateBlobStream(source.stream.size, input), download, transfers.limits.idleTimeout)
        try {
            call.respond(content)
        } catch (error: Throwable) {
            content.release()
            throw error
        }
    }
