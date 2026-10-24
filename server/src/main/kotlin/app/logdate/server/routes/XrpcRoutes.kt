package app.logdate.server.routes

import app.logdate.server.atproto.AtprotoSessionTokenService
import app.logdate.server.atproto.InvalidSwapException
import app.logdate.server.atproto.firehose.PublicRepoAccess
import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.TokenService
import app.logdate.server.identity.AtprotoIdentityService
import app.logdate.server.logging.IdentityNotices
import app.logdate.server.oauth.OAuthAccessTokenService
import app.logdate.server.oauth.OAuthDpopVerifier
import app.logdate.server.oauth.OAuthNonceService
import app.logdate.server.oauth.OAuthScopes
import app.logdate.server.oauth.OAuthUseDpopNonceException
import app.logdate.server.routes.docs.XrpcDocs
import app.logdate.server.routes.docs.XrpcRepoDocs
import io.github.aakira.napier.Napier
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.request.contentType
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.pds.CreateRecordRequest
import studio.hypertext.atproto.pds.DeleteRecordRequest
import studio.hypertext.atproto.pds.EmptyPdsResponse
import studio.hypertext.atproto.pds.GetBlobRequest
import studio.hypertext.atproto.pds.GetLatestCommitRequest
import studio.hypertext.atproto.pds.GetRecordRequest
import studio.hypertext.atproto.pds.GetRepoRequest
import studio.hypertext.atproto.pds.GetRepoStatusRequest
import studio.hypertext.atproto.pds.ListRecordsRequest
import studio.hypertext.atproto.pds.ListRecordsResponse
import studio.hypertext.atproto.pds.PdsBlobService
import studio.hypertext.atproto.pds.PdsDiscoveryService
import studio.hypertext.atproto.pds.PdsErrorResponse
import studio.hypertext.atproto.pds.PdsRepoService
import studio.hypertext.atproto.pds.PdsSessionService
import studio.hypertext.atproto.pds.PdsSyncService
import studio.hypertext.atproto.pds.PutRecordRequest
import studio.hypertext.atproto.pds.UploadBlobRequest
import studio.hypertext.atproto.repo.Cid
import studio.hypertext.atproto.repo.RepoRecordId
import studio.hypertext.atproto.repo.UnsupportedCollectionException
import studio.hypertext.atproto.syntax.Nsid
import studio.hypertext.atproto.syntax.RecordKey
import studio.hypertext.atproto.syntax.Tid
import java.util.UUID

fun Route.xrpcRoutes(
    identityService: AtprotoIdentityService,
    discoveryService: PdsDiscoveryService? = null,
    accountRepository: AccountRepository? = null,
    tokenService: TokenService? = null,
    repoService: PdsRepoService? = null,
    sessionService: PdsSessionService? = null,
    syncService: PdsSyncService? = null,
    blobService: PdsBlobService? = null,
    atprotoSessionTokenService: AtprotoSessionTokenService? = null,
    oauthAccessTokenService: OAuthAccessTokenService? = null,
    oauthDpopVerifier: OAuthDpopVerifier? = null,
    oauthNonceService: OAuthNonceService? = null,
    publicOrigin: String? = null,
    collectionsOpenToApps: Set<String> = OAuthScopes.collectionsOpenToApps,
    publicRepos: PublicRepoAccess? = null,
) {
    val openCollections = OAuthScopes.requireOpenable(collectionsOpenToApps)
    val publicReads = publicRepos?.let { PublicRepoReads(it, identityService, blobService) }

    // A repo can hold its owner's private journal, so reads are owner-only. Anyone else gets
    // `respondAsMissing`, the answer for a repo that does not exist, before anything is looked up.
    // The exception is a repo the server shows publicly (see [PublicRepoReads]): each read handler
    // offers it to `publicReads` first, which answers only from public collections and returns
    // without touching the response for any other repo.
    suspend fun ApplicationCall.requireRepoOwner(
        repo: String,
        respondAsMissing: suspend ApplicationCall.() -> Unit,
    ): XrpcPrincipal? {
        val principal =
            requireAuthenticatedAccount(
                identityService = identityService,
                accountRepository = accountRepository,
                tokenService = tokenService,
                atprotoSessionTokenService = atprotoSessionTokenService,
                oauthAccessTokenService = oauthAccessTokenService,
                oauthDpopVerifier = oauthDpopVerifier,
                oauthNonceService = oauthNonceService,
                publicOrigin = publicOrigin,
            ) ?: return null
        if (ownsRepo(principal.account, repo)) return principal
        respondAsMissing()
        return null
    }

    route("/xrpc") {
        get("/com.atproto.identity.resolveHandle", XrpcDocs.resolveHandle) {
            val handleValue =
                call.request.queryParameters["handle"]
                    ?.trim()
                    .orEmpty()
            if (handleValue.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    PdsErrorResponse("InvalidRequest", "Handle is required"),
                )
                return@get
            }

            val result = identityService.resolveHandle(handleValue)
            // A failed lookup is an outage, not a missing handle: answering HandleNotFound would let
            // other apps treat a real account as nonexistent.
            result.exceptionOrNull()?.let { error ->
                Napier.e(IdentityNotices.HANDLE_RESOLUTION_FAILED, error)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    PdsErrorResponse("InternalServerError", "Handle resolution failed"),
                )
                return@get
            }
            val resolved = result.getOrNull()
            if (resolved == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    PdsErrorResponse("HandleNotFound", "Handle not found: ${handleValue.lowercase()}"),
                )
                return@get
            }

            call.respond(HttpStatusCode.OK, resolved)
        }

        // Sign-in is passkey-only, so neither password method does any work. Refusing before the body is
        // read means no request, however malformed, reaches the account or password services. Neither
        // method can succeed, so both stay out of the API reference.
        post("/com.atproto.server.createAccount", { hidden = true }) {
            call.respond(
                HttpStatusCode.BadRequest,
                PdsErrorResponse("InvalidRequest", "Accounts cannot be created with a password. Create your account in the LogDate app."),
            )
        }

        post("/com.atproto.server.createSession", { hidden = true }) {
            call.respond(
                HttpStatusCode.BadRequest,
                PdsErrorResponse("InvalidRequest", "Password sign-in is not supported. Sign in with a passkey."),
            )
        }

        get("/com.atproto.server.getSession", XrpcDocs.getSession) {
            val sessions =
                sessionService ?: return@get call.respond(
                    HttpStatusCode.NotImplemented,
                    PdsErrorResponse("Unsupported", "Session service is not configured"),
                )
            val bearerToken = call.requireBearerToken() ?: return@get
            val result = sessions.getSession(bearerToken)
            val session = result.getOrNull()
            if (session == null) {
                call.respondForSessionError(result.exceptionOrNull())
                return@get
            }
            call.respond(HttpStatusCode.OK, session)
        }

        post("/com.atproto.server.refreshSession", XrpcDocs.refreshSession) {
            val sessions =
                sessionService ?: return@post call.respond(
                    HttpStatusCode.NotImplemented,
                    PdsErrorResponse("Unsupported", "Session service is not configured"),
                )
            val bearerToken = call.requireBearerToken() ?: return@post
            val result = sessions.refreshSession(bearerToken)
            val session = result.getOrNull()
            if (session == null) {
                call.respondForSessionError(result.exceptionOrNull())
                return@post
            }
            call.respond(HttpStatusCode.OK, session)
        }

        post("/com.atproto.server.deleteSession", XrpcDocs.deleteSession) {
            val sessions =
                sessionService ?: return@post call.respond(
                    HttpStatusCode.NotImplemented,
                    PdsErrorResponse("Unsupported", "Session service is not configured"),
                )
            val bearerToken = call.requireBearerToken() ?: return@post
            val result = sessions.deleteSession(bearerToken)
            if (result.isFailure) {
                call.respondForSessionError(result.exceptionOrNull())
                return@post
            }
            call.respond(HttpStatusCode.OK, EmptyPdsResponse())
        }

        get("/com.atproto.server.describeServer", XrpcDocs.describeServer) {
            val response =
                discoveryService?.describeServer()
                    ?: studio.hypertext.atproto.pds.DescribeServerResponse(
                        did = identityService.config.serverDid,
                        availableUserDomains = listOf(identityService.config.normalizedHandleDomain),
                        inviteCodeRequired = false,
                        phoneVerificationRequired = false,
                    )
            call.respond(HttpStatusCode.OK, response)
        }

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

            val recordResult =
                repoApi.getRecord(
                    GetRecordRequest(
                        repo = recordId.repo,
                        collection = recordId.collection,
                        recordKey = recordId.recordKey,
                        cid =
                            call.request.queryParameters["cid"]
                                ?.trim()
                                ?.takeIf(String::isNotEmpty),
                    ),
                )
            if (recordResult.isFailure) {
                call.respondForRepoError(recordResult.exceptionOrNull())
                return@get
            }
            val record = recordResult.getOrNull() ?: return@get call.respondRecordNotFound()

            call.respond(HttpStatusCode.OK, record)
        }

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

            val pageLimit =
                (
                    call.request.queryParameters["limit"]?.toIntOrNull()
                        ?: studio.hypertext.atproto.pds.ListRecordsRequest.DEFAULT_PAGE_SIZE
                ).coerceIn(1, 100)
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

            val result =
                repoApi.listRecords(
                    ListRecordsRequest(
                        repo = repo,
                        collection = collection,
                        limit =
                            (
                                call.request.queryParameters["limit"]?.toIntOrNull()
                                    ?: studio.hypertext.atproto.pds.ListRecordsRequest.DEFAULT_PAGE_SIZE
                            ).coerceIn(1, 100),
                        cursor =
                            call.request.queryParameters["cursor"]
                                ?.trim()
                                ?.takeIf(String::isNotEmpty),
                        reverse = call.request.queryParameters["reverse"]?.toBooleanStrictOrNull() ?: false,
                    ),
                )
            val page = result.getOrNull()
            if (page != null) {
                call.respond(HttpStatusCode.OK, page)
                return@get
            }

            call.respondForRepoError(result.exceptionOrNull())
        }

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
                repoApi.createRecord(request)
            val writeResult = result.getOrNull()
            if (writeResult != null) {
                call.respond(HttpStatusCode.OK, writeResult)
                return@post
            }

            call.respondForRepoError(result.exceptionOrNull())
        }

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

            val result = repoApi.putRecord(request)
            val writeResult = result.getOrNull()
            if (writeResult != null) {
                call.respond(HttpStatusCode.OK, writeResult)
                return@post
            }

            call.respondForRepoError(result.exceptionOrNull())
        }

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

            val result = repoApi.deleteRecord(request)
            if (result.isSuccess) {
                call.respond(HttpStatusCode.OK, EmptyPdsResponse())
                return@post
            }

            call.respondForRepoError(result.exceptionOrNull())
        }

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
                pdsBlobService.uploadBlob(
                    UploadBlobRequest(
                        repo = repoDid,
                        contentType = contentType,
                        bytes = body,
                    ),
                )
            val uploaded = result.getOrNull()
            if (uploaded != null) {
                call.respond(HttpStatusCode.OK, uploaded)
                return@post
            }

            call.respondForBlobError(result.exceptionOrNull())
        }

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

@Serializable
internal data class CreateRecordInput(
    val repo: String,
    val collection: Nsid,
    val record: JsonObject,
    @SerialName("rkey")
    val recordKey: RecordKey? = null,
    val validate: Boolean? = null,
    val swapCommit: String? = null,
)

@Serializable
internal data class PutRecordInput(
    val repo: String,
    val collection: Nsid,
    @SerialName("rkey")
    val recordKey: RecordKey,
    val record: JsonObject,
    val validate: Boolean? = null,
    val swapRecord: String? = null,
    val swapCommit: String? = null,
)

@Serializable
internal data class DeleteRecordInput(
    val repo: String,
    val collection: Nsid,
    @SerialName("rkey")
    val recordKey: RecordKey,
    val swapRecord: String? = null,
    val swapCommit: String? = null,
)

/** Picks the token kind from the `Authorization` header and checks it with the matching steps. */
private suspend fun io.ktor.server.application.ApplicationCall.requireAuthenticatedAccount(
    identityService: AtprotoIdentityService,
    accountRepository: AccountRepository?,
    tokenService: TokenService?,
    atprotoSessionTokenService: AtprotoSessionTokenService?,
    oauthAccessTokenService: OAuthAccessTokenService?,
    oauthDpopVerifier: OAuthDpopVerifier?,
    oauthNonceService: OAuthNonceService?,
    publicOrigin: String?,
): XrpcPrincipal? {
    if (accountRepository == null) {
        respond(HttpStatusCode.NotImplemented, PdsErrorResponse("Unsupported", "Repo auth is not configured"))
        return null
    }
    val authHeader = request.header(HttpHeaders.Authorization)
    return when {
        authHeader == null -> {
            respondAuthRequired("Missing bearer token")
        }

        authHeader.startsWith("DPoP ") -> {
            if (oauthAccessTokenService == null || oauthDpopVerifier == null || oauthNonceService == null) {
                respond(HttpStatusCode.NotImplemented, PdsErrorResponse("Unsupported", "OAuth DPoP auth is not configured"))
                return null
            }
            requireOAuthPrincipal(
                accessToken = authHeader.removePrefix("DPoP ").trim(),
                checks = OAuthChecks(oauthAccessTokenService, oauthDpopVerifier, oauthNonceService, publicOrigin),
                identityService = identityService,
                accountRepository = accountRepository,
            )
        }

        authHeader.startsWith("Bearer ") -> {
            requireLogDatePrincipal(
                bearerToken = authHeader.removePrefix("Bearer ").trim(),
                identityService = identityService,
                accountRepository = accountRepository,
                tokenService = tokenService,
                atprotoSessionTokenService = atprotoSessionTokenService,
            )
        }

        else -> {
            respondAuthRequired("Missing bearer token")
        }
    }
}

/** The services that check an OAuth access token and its DPoP proof. */
private class OAuthChecks(
    val accessTokens: OAuthAccessTokenService,
    val dpop: OAuthDpopVerifier,
    val nonces: OAuthNonceService,
    /** The server's configured public origin; the DPoP `htu` is compared against it, never a client-sent host. */
    val publicOrigin: String?,
)

/**
 * Checks an OAuth access token, its DPoP proof and the account it acts for. A failure to reach
 * the replay store is a server problem, so it is logged and answered as one, not as a bad proof.
 */
private suspend fun io.ktor.server.application.ApplicationCall.requireOAuthPrincipal(
    accessToken: String,
    checks: OAuthChecks,
    identityService: AtprotoIdentityService,
    accountRepository: AccountRepository,
): XrpcPrincipal? {
    val accessClaims = checks.accessTokens.validateAccessToken(accessToken) ?: return respondAuthRequired("Invalid DPoP access token")
    val proof =
        request.header(DPOP_HEADER)?.trim()?.takeIf(String::isNotEmpty)
            ?: return respondAuthRequired("Missing DPoP proof")
    val verification =
        try {
            checks.dpop.verify(
                proof = proof,
                method = request.httpMethod.value,
                htu = checks.publicOrigin?.let { it.trimEnd('/') + request.path() } ?: absoluteRequestUrl(),
                expectedNonce = checks.nonces.currentNonce(),
                expectedAth = checks.dpop.accessTokenHash(accessToken),
            )
        } catch (error: Exception) {
            Napier.e("Could not check a DPoP proof", error)
            respond(
                HttpStatusCode.ServiceUnavailable,
                PdsErrorResponse("TemporarilyUnavailable", "The server could not check this request. Try again shortly."),
            )
            return null
        }
    val verified =
        verification.getOrElse { error ->
            if (error is OAuthUseDpopNonceException) response.headers.append(DPOP_NONCE_HEADER, error.nonce)
            return respondAuthRequired(error.message ?: "Invalid DPoP proof")
        }
    if (verified.keyThumbprint != accessClaims.keyThumbprint) return respondAuthRequired("DPoP proof does not match the access token")
    val account =
        accountRepository
            .findByDid(accessClaims.subjectDid)
            ?.takeIf(Account::isActive)
            ?.let { identityService.ensureIdentity(it) }
            ?: return respondAuthRequired("Account not found for DPoP access token")
    return XrpcPrincipal(account, OAuthGrant(clientId = accessClaims.clientId, scopes = OAuthScopes.parse(accessClaims.scope)))
}

/** Checks a LogDate app token or an AT Protocol session token; neither carries an OAuth grant. */
@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
private suspend fun io.ktor.server.application.ApplicationCall.requireLogDatePrincipal(
    bearerToken: String,
    identityService: AtprotoIdentityService,
    accountRepository: AccountRepository,
    tokenService: TokenService?,
    atprotoSessionTokenService: AtprotoSessionTokenService?,
): XrpcPrincipal? {
    if (atprotoSessionTokenService == null && tokenService == null) {
        respond(HttpStatusCode.NotImplemented, PdsErrorResponse("Unsupported", "Repo auth is not configured"))
        return null
    }
    atprotoSessionTokenService
        ?.getAccessAccount(bearerToken, accountRepository, identityService)
        ?.let { return XrpcPrincipal(it, oauth = null) }

    val accountId =
        tokenService
            ?.validateAccessToken(bearerToken)
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return respondAuthRequired("Invalid bearer token")
    val account =
        accountRepository
            .findById(kotlin.uuid.Uuid.parse(accountId.toString()))
            ?.let { identityService.ensureIdentity(it) }
            ?: return respondAuthRequired("Account not found for bearer token")
    return XrpcPrincipal(account, oauth = null)
}

private suspend fun io.ktor.server.application.ApplicationCall.respondAuthRequired(message: String): XrpcPrincipal? {
    respond(HttpStatusCode.Unauthorized, PdsErrorResponse("AuthRequired", message))
    return null
}

/**
 * Who is calling an XRPC method: the account, and for an app signed in through OAuth, which app
 * and what it was granted. LogDate app and AT Protocol session tokens carry no [oauth] grant.
 */
internal data class XrpcPrincipal(
    val account: Account,
    val oauth: OAuthGrant?,
)

/** What an app signed in through OAuth was granted. */
internal data class OAuthGrant(
    val clientId: String,
    val scopes: Set<String>,
) {
    /** `atproto` alone only confirms the identity; any repository read or write needs `transition:generic`. */
    val canUseRepository: Boolean get() = OAuthScopes.TRANSITION_GENERIC in scopes
}

/** Answers `403 InsufficientScope` and returns `false` when [grant] only confirms the identity. */
private suspend fun ApplicationCall.allowOAuthRepositoryUse(grant: OAuthGrant): Boolean {
    if (grant.canUseRepository) return true
    respond(
        HttpStatusCode.Forbidden,
        PdsErrorResponse("InsufficientScope", "This app was only granted the atproto scope, which does not include the repository"),
    )
    return false
}

/**
 * Lets LogDate sessions write anything they own. An OAuth app needs `transition:generic` and may
 * only write a collection that is open to apps.
 */
private suspend fun ApplicationCall.allowOAuthWrite(
    principal: XrpcPrincipal,
    collection: String,
    openCollections: Set<String>,
): Boolean {
    val grant = principal.oauth ?: return true
    if (!allowOAuthRepositoryUse(grant)) return false
    if (OAuthScopes.isOpenToApps(collection, openCollections)) return true
    respond(HttpStatusCode.Forbidden, PdsErrorResponse("Forbidden", "Apps signed in through OAuth cannot write to this collection"))
    return false
}

private suspend fun ApplicationCall.respondUploadsClosedToOAuthApps() {
    respond(
        HttpStatusCode.Forbidden,
        PdsErrorResponse(
            "Forbidden",
            "Apps signed in through OAuth cannot upload files yet, because no collection open to apps refers to files",
        ),
    )
}

private suspend fun ApplicationCall.respondClosedToOAuthApps() {
    respond(HttpStatusCode.Forbidden, PdsErrorResponse("Forbidden", "This method is not available to apps signed in through OAuth"))
}

private suspend fun ApplicationCall.respondRecordNotFound() {
    respond(HttpStatusCode.BadRequest, PdsErrorResponse("RecordNotFound", "Record not found"))
}

private fun io.ktor.server.application.ApplicationCall.absoluteRequestUrl(): String {
    val origin = request.origin
    val host =
        if (origin.serverPort == DEFAULT_HTTPS_PORT || origin.serverPort == DEFAULT_HTTP_PORT) {
            origin.serverHost
        } else {
            "${origin.serverHost}:${origin.serverPort}"
        }
    return "${origin.scheme}://$host${request.path()}"
}

private suspend fun io.ktor.server.application.ApplicationCall.requireBearerToken(): String? {
    val authHeader = request.header(HttpHeaders.Authorization)
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
        respond(HttpStatusCode.Unauthorized, PdsErrorResponse("AuthRequired", "Missing bearer token"))
        return null
    }
    return authHeader.removePrefix("Bearer ").trim()
}

private const val DPOP_HEADER = "DPoP"
private const val DPOP_NONCE_HEADER = "DPoP-Nonce"
private const val DEFAULT_HTTPS_PORT = 443
private const val DEFAULT_HTTP_PORT = 80

private suspend fun resolveRepoAccount(
    identityService: AtprotoIdentityService,
    repo: String,
): Account? =
    identityService.findByDid(repo.trim())
        ?: identityService.findByHandle(repo.trim())

private suspend fun resolveRepoDid(
    identityService: AtprotoIdentityService,
    repo: String,
): AtprotoDid? =
    resolveRepoAccount(identityService, repo)
        ?.did
        ?.let(AtprotoDid::require)

private suspend fun parseRecordId(
    identityService: AtprotoIdentityService,
    repo: String,
    collection: String,
    recordKey: String,
): RepoRecordId? {
    val resolvedRepo = resolveRepoDid(identityService, repo) ?: return null
    val resolvedCollection = Nsid.parse(collection).getOrNull() ?: return null
    val resolvedRecordKey = RecordKey.parse(recordKey).getOrNull() ?: return null
    return RepoRecordId(repo = resolvedRepo, collection = resolvedCollection, recordKey = resolvedRecordKey)
}

private fun ownsRepo(
    account: Account,
    repo: String,
): Boolean {
    val normalized = repo.trim().lowercase()
    return normalized == account.did?.lowercase() || normalized == account.handle?.lowercase()
}

private suspend fun io.ktor.server.application.ApplicationCall.respondForRepoError(error: Throwable?) {
    when (error) {
        is UnsupportedCollectionException -> {
            respond(
                HttpStatusCode.BadRequest,
                PdsErrorResponse("InvalidRequest", error.message ?: "Unsupported collection"),
            )
        }

        is studio.hypertext.atproto.repo.InvalidRepoCursorException -> {
            respond(
                HttpStatusCode.BadRequest,
                PdsErrorResponse("InvalidRequest", error.message ?: "Invalid cursor"),
            )
        }

        is InvalidSwapException -> {
            respond(
                HttpStatusCode.BadRequest,
                PdsErrorResponse("InvalidSwap", error.message ?: "swapRecord did not match"),
            )
        }

        else -> {
            respond(
                HttpStatusCode.BadRequest,
                PdsErrorResponse("InvalidRequest", error?.message ?: "Invalid XRPC request"),
            )
        }
    }
}

private suspend fun ApplicationCall.respondRepoNotFound(did: String) {
    respond(HttpStatusCode.NotFound, PdsErrorResponse("RepoNotFound", "Unknown repo: $did"))
}

private suspend fun ApplicationCall.respondBlobNotFound() {
    respond(HttpStatusCode.NotFound, PdsErrorResponse("BlobNotFound", "Blob not found"))
}

private suspend fun io.ktor.server.application.ApplicationCall.respondForBlobError(error: Throwable?) {
    respond(
        HttpStatusCode.BadRequest,
        PdsErrorResponse("InvalidRequest", error?.message ?: "Invalid blob request"),
    )
}

private suspend fun io.ktor.server.application.ApplicationCall.respondForSessionError(error: Throwable?) {
    when (error?.message) {
        "InvalidToken" -> {
            respond(HttpStatusCode.Unauthorized, PdsErrorResponse("InvalidToken", "Authentication failed"))
        }

        else -> {
            // The cause can carry database or library text, so it stays in the server log.
            Napier.e(IdentityNotices.SESSION_REQUEST_FAILED, error)
            respond(HttpStatusCode.InternalServerError, PdsErrorResponse("InternalServerError", "Session request failed"))
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondForSyncError(error: Throwable?) {
    val message = error?.message.orEmpty()
    when {
        message.startsWith("Unknown repo:") -> {
            respond(HttpStatusCode.NotFound, PdsErrorResponse("RepoNotFound", message))
        }

        else -> {
            respond(HttpStatusCode.BadRequest, PdsErrorResponse("InvalidRequest", message.ifBlank { "Invalid sync request" }))
        }
    }
}
