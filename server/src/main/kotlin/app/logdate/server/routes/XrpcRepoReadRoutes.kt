package app.logdate.server.routes

import app.logdate.server.oauth.OAuthScopes
import app.logdate.server.routes.docs.XrpcDocs
import app.logdate.server.routes.docs.XrpcRepoDocs
import io.github.smiley4.ktoropenapi.get
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.contentType
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.pds.GetLatestCommitRequest
import studio.hypertext.atproto.pds.GetRecordRequest
import studio.hypertext.atproto.pds.GetRepoRequest
import studio.hypertext.atproto.pds.GetRepoStatusRequest
import studio.hypertext.atproto.pds.ListRecordsRequest
import studio.hypertext.atproto.pds.ListRecordsResponse
import studio.hypertext.atproto.pds.PdsErrorResponse
import studio.hypertext.atproto.syntax.Nsid
import studio.hypertext.atproto.syntax.Tid

internal fun Route.xrpcRepoReadRoutes(context: XrpcRouteContext) {
    registerXrpcDescribeRepo(context)
    registerXrpcGetRepo(context)
    registerXrpcGetLatestCommit(context)
    registerXrpcGetRepoStatus(context)
    registerXrpcGetRecord(context)
    registerXrpcListRecords(context)
}

private fun Route.registerXrpcDescribeRepo(context: XrpcRouteContext) {
    with(context) {
        get("/com.atproto.repo.describeRepo", XrpcDocs.describeRepo) {
            val repoValue =
                call.request.queryParameters["repo"]
                    ?.trim()
                    .orEmpty()
            if (repoValue.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    PdsErrorResponse("InvalidRequest", "Repo is required"),
                )
                return@get
            }

            val result = identityService.describeRepo(repoValue)
            val repoDescription = result.getOrNull()
            if (repoDescription == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    PdsErrorResponse("RepoNotFound", "Repo not found: $repoValue"),
                )
                return@get
            }

            call.respond(
                HttpStatusCode.OK,
                repoDescription.copy(
                    collections = repoDescription.collections.filter { OAuthScopes.isOpenToApps(it.toString(), openCollections) },
                ),
            )
        }
    }
}

private fun Route.registerXrpcGetRepo(context: XrpcRouteContext) {
    with(context) {
        get("/com.atproto.sync.getRepo", XrpcRepoDocs.getRepo) {
            val syncApi =
                syncService ?: return@get call.respond(
                    HttpStatusCode.NotImplemented,
                    PdsErrorResponse("Unsupported", "Sync service is not configured"),
                )
            val didValue =
                call.request.queryParameters["did"]
                    ?.trim()
                    .orEmpty()
            if (didValue.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, PdsErrorResponse("InvalidRequest", "did is required"))
                return@get
            }
            val did = AtprotoDid.parse(didValue).getOrNull()
            val since =
                call.request.queryParameters["since"]
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?.let(Tid::parse)
                    ?.getOrNull()
            if (did == null || (call.request.queryParameters["since"] != null && since == null)) {
                call.respond(HttpStatusCode.BadRequest, PdsErrorResponse("InvalidRequest", "Invalid did or since"))
                return@get
            }
            if (publicReads?.tryGetRepo(call, did, since) == true) return@get
            val principal = call.requireRepoOwner(didValue) { respondRepoNotFound(didValue) } ?: return@get
            if (principal.oauth != null) return@get call.respondClosedToOAuthApps()
            val result = syncApi.getRepo(GetRepoRequest(did = did, since = since))
            val export = result.getOrNull()
            if (export == null) {
                call.respondForSyncError(result.exceptionOrNull())
                return@get
            }
            call.respondBytes(
                bytes = export.bytes,
                contentType = ContentType.parse(export.contentType),
                status = HttpStatusCode.OK,
            )
        }
    }
}

private fun Route.registerXrpcGetLatestCommit(context: XrpcRouteContext) {
    with(context) {
        get("/com.atproto.sync.getLatestCommit", XrpcRepoDocs.getLatestCommit) {
            val syncApi =
                syncService ?: return@get call.respond(
                    HttpStatusCode.NotImplemented,
                    PdsErrorResponse("Unsupported", "Sync service is not configured"),
                )
            val didValue =
                call.request.queryParameters["did"]
                    ?.trim()
                    .orEmpty()
            val did = AtprotoDid.parse(didValue).getOrNull()
            if (did == null) {
                call.respond(HttpStatusCode.BadRequest, PdsErrorResponse("InvalidRequest", "did is required"))
                return@get
            }
            if (publicReads?.tryGetLatestCommit(call, did) == true) return@get
            val principal = call.requireRepoOwner(didValue) { respondRepoNotFound(didValue) } ?: return@get
            if (principal.oauth != null) return@get call.respondClosedToOAuthApps()
            val result = syncApi.getLatestCommit(GetLatestCommitRequest(did))
            val latestCommit = result.getOrNull()
            if (latestCommit == null) {
                call.respondForSyncError(result.exceptionOrNull())
                return@get
            }
            call.respond(HttpStatusCode.OK, latestCommit)
        }
    }
}

private fun Route.registerXrpcGetRepoStatus(context: XrpcRouteContext) {
    with(context) {
        get("/com.atproto.sync.getRepoStatus", XrpcRepoDocs.getRepoStatus) {
            val syncApi =
                syncService ?: return@get call.respond(
                    HttpStatusCode.NotImplemented,
                    PdsErrorResponse("Unsupported", "Sync service is not configured"),
                )
            val didValue =
                call.request.queryParameters["did"]
                    ?.trim()
                    .orEmpty()
            val did = AtprotoDid.parse(didValue).getOrNull()
            if (did == null) {
                call.respond(HttpStatusCode.BadRequest, PdsErrorResponse("InvalidRequest", "did is required"))
                return@get
            }
            if (publicReads?.tryGetRepoStatus(call, did) == true) return@get
            val principal = call.requireRepoOwner(didValue) { respondRepoNotFound(didValue) } ?: return@get
            if (principal.oauth != null) return@get call.respondClosedToOAuthApps()
            val result = syncApi.getRepoStatus(GetRepoStatusRequest(did))
            val repoStatus = result.getOrNull()
            if (repoStatus == null) {
                call.respondForSyncError(result.exceptionOrNull())
                return@get
            }
            call.respond(HttpStatusCode.OK, repoStatus)
        }
    }
}

private fun Route.registerXrpcGetRecord(context: XrpcRouteContext) {
    with(context) {
        get("/com.atproto.repo.getRecord", XrpcRepoDocs.getRecord) {
            val repoApi =
                repoService ?: return@get call.respond(
                    HttpStatusCode.NotImplemented,
                    PdsErrorResponse("Unsupported", "Repo service is not configured"),
                )
            val repoValue =
                call.request.queryParameters["repo"]
                    ?.trim()
                    .orEmpty()
            val collectionValue =
                call.request.queryParameters["collection"]
                    ?.trim()
                    .orEmpty()
            val recordKeyValue =
                call.request.queryParameters["rkey"]
                    ?.trim()
                    .orEmpty()
            if (repoValue.isBlank() || collectionValue.isBlank() || recordKeyValue.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    PdsErrorResponse("InvalidRequest", "repo, collection, and rkey are required"),
                )
                return@get
            }

            val recordCid =
                call.request.queryParameters["cid"]
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
            if (publicReads?.tryGetRecord(call, repoValue, collectionValue, recordKeyValue, recordCid) == true) return@get
            val respondInvalidRecordId: suspend ApplicationCall.() -> Unit = {
                respond(
                    HttpStatusCode.BadRequest,
                    PdsErrorResponse("InvalidRequest", "Invalid repo, collection, or record key"),
                )
            }
            val principal = call.requireRepoOwner(repoValue, respondInvalidRecordId) ?: return@get
            val recordId =
                parseRecordId(identityService, repoValue, collectionValue, recordKeyValue)
                    ?: return@get call.respondInvalidRecordId()
            principal.oauth?.let { grant ->
                if (!call.allowOAuthRepositoryUse(grant)) return@get
                if (!OAuthScopes.isOpenToApps(recordId.collection.toString(), openCollections)) return@get call.respondRecordNotFound()
            }

            val recordResult = repoApi.getRecord(GetRecordRequest(recordId.repo, recordId.collection, recordId.recordKey, recordCid))
            if (recordResult.isFailure) {
                call.respondForRepoError(recordResult.exceptionOrNull())
                return@get
            }
            val record = recordResult.getOrNull() ?: return@get call.respondRecordNotFound()

            call.respond(HttpStatusCode.OK, record)
        }
    }
}

private fun Route.registerXrpcListRecords(context: XrpcRouteContext) {
    with(context) {
        get("/com.atproto.repo.listRecords", XrpcRepoDocs.listRecords) {
            val repoApi =
                repoService ?: return@get call.respond(
                    HttpStatusCode.NotImplemented,
                    PdsErrorResponse("Unsupported", "Repo service is not configured"),
                )
            val repoValue =
                call.request.queryParameters["repo"]
                    ?.trim()
                    .orEmpty()
            val collectionValue =
                call.request.queryParameters["collection"]
                    ?.trim()
                    .orEmpty()
            if (repoValue.isBlank() || collectionValue.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    PdsErrorResponse("InvalidRequest", "repo and collection are required"),
                )
                return@get
            }

            val pageLimit = call.requestedRecordPageLimit()
            val pageCursor =
                call.request.queryParameters["cursor"]
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
            val pageReverse = call.request.queryParameters["reverse"]?.toBooleanStrictOrNull() ?: false
            if (publicReads?.tryListRecords(call, repoValue, collectionValue, pageLimit, pageCursor, pageReverse) == true) return@get
            val respondInvalidRepoOrCollection: suspend ApplicationCall.() -> Unit = {
                respond(
                    HttpStatusCode.BadRequest,
                    PdsErrorResponse("InvalidRequest", "Invalid repo or collection"),
                )
            }
            val principal = call.requireRepoOwner(repoValue, respondInvalidRepoOrCollection) ?: return@get
            val repo = resolveRepoDid(identityService, repoValue)
            val collection = Nsid.parse(collectionValue).getOrNull()
            if (repo == null || collection == null) {
                call.respondInvalidRepoOrCollection()
                return@get
            }
            principal.oauth?.let { grant ->
                if (!call.allowOAuthRepositoryUse(grant)) return@get
                if (!OAuthScopes.isOpenToApps(collection.toString(), openCollections)) {
                    return@get call.respond(HttpStatusCode.OK, ListRecordsResponse(records = emptyList()))
                }
            }

            val result = repoApi.listRecords(ListRecordsRequest(repo, collection, pageLimit, pageCursor, pageReverse))
            val page = result.getOrNull()
            if (page != null) {
                call.respond(HttpStatusCode.OK, page)
                return@get
            }

            call.respondForRepoError(result.exceptionOrNull())
        }
    }
}

private fun ApplicationCall.requestedRecordPageLimit(): Int =
    (request.queryParameters["limit"]?.toIntOrNull() ?: ListRecordsRequest.DEFAULT_PAGE_SIZE).coerceIn(1, 100)
