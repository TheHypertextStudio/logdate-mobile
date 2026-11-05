@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.routes

import app.logdate.server.identity.adoption.withAccountWriteOrigin
import app.logdate.server.routes.docs.XrpcRepoDocs
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.contentType
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.pds.GetBlobRequest
import studio.hypertext.atproto.pds.PdsErrorResponse
import studio.hypertext.atproto.pds.UploadBlobRequest
import studio.hypertext.atproto.repo.Cid

internal fun Route.xrpcBlobRoutes(context: XrpcRouteContext) {
    registerXrpcUploadBlob(context)
    registerXrpcGetBlob(context)
}

private fun Route.registerXrpcUploadBlob(context: XrpcRouteContext) {
    with(context) {
        post("/com.atproto.repo.uploadBlob", XrpcRepoDocs.uploadBlob) {
            val pdsBlobService =
                blobService ?: return@post call.respond(
                    HttpStatusCode.NotImplemented,
                    PdsErrorResponse("Unsupported", "Blob service is not configured"),
                )
            val principal =
                call.requireAuthenticatedAccount(
                    identityService = identityService,
                    accountRepository = accountRepository,
                    tokenService = tokenService,
                    atprotoSessionTokenService = atprotoSessionTokenService,
                    oauthAccessTokenService = oauthAccessTokenService,
                    oauthDpopVerifier = oauthDpopVerifier,
                    oauthNonceService = oauthNonceService,
                    publicOrigin = publicOrigin,
                ) ?: return@post
            if (principal.oauth != null) return@post call.respondUploadsClosedToOAuthApps()
            val repoDid =
                principal.account.did
                    ?.let(AtprotoDid::parse)
                    ?.getOrNull()
            if (repoDid == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    PdsErrorResponse("InvalidRequest", "Authenticated account does not have a valid DID"),
                )
                return@post
            }
            val body = call.receive<ByteArray>()
            val contentType =
                call.request
                    .header(HttpHeaders.ContentType)
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?: call.request
                        .contentType()
                        .toString()
                        .takeIf(String::isNotBlank)
                    ?: ContentType.Application.OctetStream
                        .toString()
            val result =
                withAccountWriteOrigin(principal.account.id, principal.account.identityEpoch) {
                    pdsBlobService.uploadBlob(
                        UploadBlobRequest(repo = repoDid, contentType = contentType, bytes = body),
                    )
                }
            val uploaded = result.getOrNull()
            if (uploaded != null) {
                call.respond(HttpStatusCode.OK, uploaded)
                return@post
            }

            call.respondForBlobError(result.exceptionOrNull())
        }
    }
}

private fun Route.registerXrpcGetBlob(context: XrpcRouteContext) {
    with(context) {
        get("/com.atproto.sync.getBlob", XrpcRepoDocs.getBlob) {
            val pdsBlobService =
                blobService ?: return@get call.respond(
                    HttpStatusCode.NotImplemented,
                    PdsErrorResponse("Unsupported", "Blob service is not configured"),
                )
            val didValue =
                call.request.queryParameters["did"]
                    ?.trim()
                    .orEmpty()
            val cidValue =
                call.request.queryParameters["cid"]
                    ?.trim()
                    .orEmpty()
            val did = AtprotoDid.parse(didValue).getOrNull()
            val cid = Cid.parse(cidValue).getOrNull()
            if (did == null || cid == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    PdsErrorResponse("InvalidRequest", "did and cid are required"),
                )
                return@get
            }

            if (publicReads?.tryGetBlob(call, did, cid) == true) return@get
            val principal = call.requireRepoOwner(didValue) { respondBlobNotFound() } ?: return@get
            if (principal.oauth != null) return@get call.respondClosedToOAuthApps()
            val result =
                pdsBlobService.getBlob(
                    GetBlobRequest(
                        did = did,
                        cid = cid,
                    ),
                )
            val blob = result.getOrNull()
            if (blob != null) {
                val contentType =
                    runCatching {
                        ContentType.parse(blob.contentType)
                    }.getOrDefault(ContentType.Application.OctetStream)
                call.respondBytes(
                    bytes = blob.bytes,
                    contentType = contentType,
                    status = HttpStatusCode.OK,
                )
                return@get
            }
            if (result.isSuccess) {
                call.respondBlobNotFound()
                return@get
            }

            call.respondForBlobError(result.exceptionOrNull())
        }
    }
}
