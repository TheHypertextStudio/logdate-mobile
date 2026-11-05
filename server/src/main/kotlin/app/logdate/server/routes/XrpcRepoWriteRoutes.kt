@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.routes

import app.logdate.server.identity.adoption.withAccountWriteOrigin
import app.logdate.server.routes.docs.XrpcRepoDocs
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import studio.hypertext.atproto.pds.CreateRecordRequest
import studio.hypertext.atproto.pds.DeleteRecordRequest
import studio.hypertext.atproto.pds.EmptyPdsResponse
import studio.hypertext.atproto.pds.PdsErrorResponse
import studio.hypertext.atproto.pds.PutRecordRequest

internal fun Route.xrpcRepoWriteRoutes(context: XrpcRouteContext) {
    registerXrpcCreateRecord(context)
    registerXrpcPutRecord(context)
    registerXrpcDeleteRecord(context)
}

private fun Route.registerXrpcCreateRecord(context: XrpcRouteContext) {
    with(context) {
        post("/com.atproto.repo.createRecord", XrpcRepoDocs.createRecord) {
            val repoApi =
                repoService ?: return@post call.respond(
                    HttpStatusCode.NotImplemented,
                    PdsErrorResponse("Unsupported", "Repo service is not configured"),
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
            val wireRequest = call.receive<CreateRecordInput>()
            if (!call.allowOAuthWrite(principal, wireRequest.collection.toString(), openCollections)) return@post
            val repo =
                resolveRepoDid(identityService, wireRequest.repo)
                    ?: return@post call.respond(
                        HttpStatusCode.BadRequest,
                        PdsErrorResponse("InvalidRequest", "Invalid repo"),
                    )
            if (!ownsRepo(principal.account, wireRequest.repo)) {
                call.respond(HttpStatusCode.Forbidden, PdsErrorResponse("RepoMismatch", "Authenticated account does not own repo"))
                return@post
            }
            val request =
                CreateRecordRequest(
                    repo = repo,
                    collection = wireRequest.collection,
                    record = wireRequest.record,
                    recordKey = wireRequest.recordKey,
                    validate = wireRequest.validate,
                    swapCommit = wireRequest.swapCommit,
                )

            val result =
                withAccountWriteOrigin(principal.account.id) { repoApi.createRecord(request) }
            val writeResult = result.getOrNull()
            if (writeResult != null) {
                call.respond(HttpStatusCode.OK, writeResult)
                return@post
            }

            call.respondForRepoError(result.exceptionOrNull())
        }
    }
}

private fun Route.registerXrpcPutRecord(context: XrpcRouteContext) {
    with(context) {
        post("/com.atproto.repo.putRecord", XrpcRepoDocs.putRecord) {
            val repoApi =
                repoService ?: return@post call.respond(
                    HttpStatusCode.NotImplemented,
                    PdsErrorResponse("Unsupported", "Repo service is not configured"),
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
            val wireRequest = call.receive<PutRecordInput>()
            if (!call.allowOAuthWrite(principal, wireRequest.collection.toString(), openCollections)) return@post
            val repo =
                resolveRepoDid(identityService, wireRequest.repo)
                    ?: return@post call.respond(
                        HttpStatusCode.BadRequest,
                        PdsErrorResponse("InvalidRequest", "Invalid repo"),
                    )
            if (!ownsRepo(principal.account, wireRequest.repo)) {
                call.respond(HttpStatusCode.Forbidden, PdsErrorResponse("RepoMismatch", "Authenticated account does not own repo"))
                return@post
            }
            val request =
                PutRecordRequest(
                    repo = repo,
                    collection = wireRequest.collection,
                    recordKey = wireRequest.recordKey,
                    record = wireRequest.record,
                    validate = wireRequest.validate,
                    swapRecord = wireRequest.swapRecord,
                    swapCommit = wireRequest.swapCommit,
                )

            val result = withAccountWriteOrigin(principal.account.id) { repoApi.putRecord(request) }
            val writeResult = result.getOrNull()
            if (writeResult != null) {
                call.respond(HttpStatusCode.OK, writeResult)
                return@post
            }

            call.respondForRepoError(result.exceptionOrNull())
        }
    }
}

private fun Route.registerXrpcDeleteRecord(context: XrpcRouteContext) {
    with(context) {
        post("/com.atproto.repo.deleteRecord", XrpcRepoDocs.deleteRecord) {
            val repoApi =
                repoService ?: return@post call.respond(
                    HttpStatusCode.NotImplemented,
                    PdsErrorResponse("Unsupported", "Repo service is not configured"),
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
            val wireRequest = call.receive<DeleteRecordInput>()
            if (!call.allowOAuthWrite(principal, wireRequest.collection.toString(), openCollections)) return@post
            val repo =
                resolveRepoDid(identityService, wireRequest.repo)
                    ?: return@post call.respond(
                        HttpStatusCode.BadRequest,
                        PdsErrorResponse("InvalidRequest", "Invalid repo"),
                    )
            if (!ownsRepo(principal.account, wireRequest.repo)) {
                call.respond(HttpStatusCode.Forbidden, PdsErrorResponse("RepoMismatch", "Authenticated account does not own repo"))
                return@post
            }
            val request =
                DeleteRecordRequest(
                    repo = repo,
                    collection = wireRequest.collection,
                    recordKey = wireRequest.recordKey,
                    swapRecord = wireRequest.swapRecord,
                    swapCommit = wireRequest.swapCommit,
                )

            val result = withAccountWriteOrigin(principal.account.id) { repoApi.deleteRecord(request) }
            if (result.isSuccess) {
                call.respond(HttpStatusCode.OK, EmptyPdsResponse())
                return@post
            }

            call.respondForRepoError(result.exceptionOrNull())
        }
    }
}
