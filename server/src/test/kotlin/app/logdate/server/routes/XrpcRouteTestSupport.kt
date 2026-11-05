package app.logdate.server.routes

import app.logdate.server.atproto.AtprotoSessionTokenService
import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.InMemoryAccountRepository
import app.logdate.server.auth.JwtTokenService
import app.logdate.server.identity.AtprotoIdentityConfig
import app.logdate.server.identity.AtprotoIdentityService
import app.logdate.server.identity.InMemorySigningKeyRepository
import app.logdate.server.identity.SigningKeyService
import app.logdate.server.oauth.OAuthAccessTokenService
import app.logdate.server.oauth.OAuthConfig
import app.logdate.server.oauth.OAuthDpopVerifier
import app.logdate.server.oauth.OAuthKeyService
import app.logdate.server.oauth.OAuthNonceService
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.TestApplicationBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.pds.CreateAccountRequest
import studio.hypertext.atproto.pds.CreateSessionRequest
import studio.hypertext.atproto.pds.GetLatestCommitRequest
import studio.hypertext.atproto.pds.GetLatestCommitResponse
import studio.hypertext.atproto.pds.GetRepoRequest
import studio.hypertext.atproto.pds.GetRepoStatusRequest
import studio.hypertext.atproto.pds.GetRepoStatusResponse
import studio.hypertext.atproto.pds.PdsSessionService
import studio.hypertext.atproto.pds.PdsSyncService
import studio.hypertext.atproto.pds.RepoExportResponse
import studio.hypertext.atproto.pds.SessionInfoResponse
import studio.hypertext.atproto.pds.SessionResponse
import studio.hypertext.atproto.pds.runtime.DefaultPdsRepoService
import studio.hypertext.atproto.repo.Cid
import studio.hypertext.atproto.repo.RepoEngine
import studio.hypertext.atproto.repo.RepoExport
import studio.hypertext.atproto.repo.RepoHead
import studio.hypertext.atproto.repo.RepoListPage
import studio.hypertext.atproto.repo.RepoRecord
import studio.hypertext.atproto.repo.RepoRecordId
import studio.hypertext.atproto.repo.RepoValidationStatus
import studio.hypertext.atproto.repo.RepoWriteResult
import studio.hypertext.atproto.repo.SignedRepoCommit
import studio.hypertext.atproto.syntax.AtUri
import studio.hypertext.atproto.syntax.Nsid
import studio.hypertext.atproto.syntax.RecordKey
import studio.hypertext.atproto.syntax.Tid

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
abstract class XrpcRouteTestSupport {
    protected val json = Json { ignoreUnknownKeys = true }

    protected fun TestApplicationBuilder.configureXrpcApp(
        repoRecordStore: RepoEngine,
        accountRepository: AccountRepository? = InMemoryAccountRepository(),
        tokenService: JwtTokenService? = JwtTokenService("xrpc-route-coverage-secret"),
        sessionService: PdsSessionService? = null,
        syncService: PdsSyncService? = null,
        atprotoSessionTokenService: AtprotoSessionTokenService? = null,
        collectionsOpenToApps: Set<String> = setOf("com.example.note"),
    ): XrpcCoverageEnvironment {
        val concreteAccountRepository = accountRepository ?: InMemoryAccountRepository()
        val identityService = identityService(concreteAccountRepository)
        val oauthKeyService = OAuthKeyService()
        val oauthNonceService = OAuthNonceService()
        val oauthDpopVerifier = OAuthDpopVerifier()
        val oauthAccessTokenService = OAuthAccessTokenService(OAuthConfig(issuer = "https://logdate.app"), oauthKeyService)

        application {
            install(ContentNegotiation) {
                json(json)
            }
            routing {
                xrpcRoutes(
                    identityService = identityService,
                    accountRepository = accountRepository,
                    tokenService = tokenService,
                    repoService = DefaultPdsRepoService(repoRecordStore),
                    sessionService = sessionService,
                    syncService = syncService,
                    atprotoSessionTokenService = atprotoSessionTokenService,
                    oauthAccessTokenService = oauthAccessTokenService,
                    oauthDpopVerifier = oauthDpopVerifier,
                    oauthNonceService = oauthNonceService,
                    collectionsOpenToApps = collectionsOpenToApps,
                )
            }
        }

        return XrpcCoverageEnvironment(
            identityService = identityService,
            accountRepository = concreteAccountRepository,
            tokenService = tokenService ?: JwtTokenService("unused-secret"),
            oauthNonceService = oauthNonceService,
            oauthDpopVerifier = oauthDpopVerifier,
            oauthAccessTokenService = oauthAccessTokenService,
        )
    }

    protected class StubSessionService : PdsSessionService {
        var lastCreateAccountRequest: CreateAccountRequest? = null
        var lastCreateSessionRequest: CreateSessionRequest? = null
        var lastAccessToken: String? = null
        var lastRefreshToken: String? = null
        var deletedRefreshToken: String? = null

        private val sessionInfo =
            SessionInfoResponse(
                handle = "alice.logdate.app",
                did = AtprotoDid.require("did:web:alice.logdate.app"),
                active = true,
            )
        private val sessionResponse =
            SessionResponse(
                accessJwt = "hosted-access",
                refreshJwt = "hosted-refresh",
                handle = "alice.logdate.app",
                did = sessionInfo.did,
                active = true,
            )

        override suspend fun createAccount(request: CreateAccountRequest): Result<SessionResponse> {
            lastCreateAccountRequest = request
            return Result.success(sessionResponse)
        }

        override suspend fun createSession(request: CreateSessionRequest): Result<SessionResponse> {
            lastCreateSessionRequest = request
            return Result.success(sessionResponse)
        }

        override suspend fun getSession(accessJwt: String): Result<SessionInfoResponse> {
            lastAccessToken = accessJwt
            return Result.success(sessionInfo)
        }

        override suspend fun refreshSession(refreshJwt: String): Result<SessionResponse> {
            lastRefreshToken = refreshJwt
            return Result.success(sessionResponse)
        }

        override suspend fun deleteSession(refreshJwt: String): Result<Unit> {
            deletedRefreshToken = refreshJwt
            return Result.success(Unit)
        }
    }

    protected class StubSyncService : PdsSyncService {
        var lastGetRepoRequest: GetRepoRequest? = null
        var lastGetLatestCommitRequest: GetLatestCommitRequest? = null
        var lastGetRepoStatusRequest: GetRepoStatusRequest? = null

        override suspend fun getRepo(request: GetRepoRequest): Result<RepoExportResponse?> {
            lastGetRepoRequest = request
            return Result.success(
                RepoExportResponse(
                    contentType = "application/vnd.ipld.car",
                    bytes = "car-bytes".encodeToByteArray(),
                ),
            )
        }

        override suspend fun getLatestCommit(request: GetLatestCommitRequest): Result<GetLatestCommitResponse?> {
            lastGetLatestCommitRequest = request
            return Result.success(
                GetLatestCommitResponse(
                    cid = Cid.require("bafyreihdwdcefgh4dqkjv67uzcmw7ojee6xedzdetojuzjevtenxquvyku"),
                    rev = Tid.fromLong(9L),
                ),
            )
        }

        override suspend fun getRepoStatus(request: GetRepoStatusRequest): Result<GetRepoStatusResponse?> {
            lastGetRepoStatusRequest = request
            return Result.success(
                GetRepoStatusResponse(
                    did = request.did,
                    active = true,
                    rev = Tid.fromLong(9L),
                ),
            )
        }
    }

    protected fun identityService(accountRepository: AccountRepository): AtprotoIdentityService =
        AtprotoIdentityService(
            accountRepository = accountRepository,
            signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "test-kek"),
            config = AtprotoIdentityConfig(handleDomain = "logdate.app", pdsServiceEndpoint = "https://logdate.app"),
        )

    protected class YieldingAccountRepository(
        private val delegate: InMemoryAccountRepository = InMemoryAccountRepository(),
    ) : AccountRepository by delegate {
        override suspend fun findByDid(did: String): Account? {
            kotlinx.coroutines.yield()
            return delegate.findByDid(did)
        }

        override suspend fun findByHandle(handle: String): Account? {
            kotlinx.coroutines.yield()
            return delegate.findByHandle(handle)
        }
    }

    protected data class XrpcCoverageEnvironment(
        val identityService: AtprotoIdentityService,
        val accountRepository: AccountRepository,
        val tokenService: JwtTokenService,
        val oauthNonceService: OAuthNonceService,
        val oauthDpopVerifier: OAuthDpopVerifier,
        val oauthAccessTokenService: OAuthAccessTokenService,
    )

    protected class StubRepoRecordStore : RepoEngine {
        var getRecordResult: Result<RepoRecord?> = Result.success(null)
        var listRecordsResult: Result<RepoListPage> = Result.success(RepoListPage(emptyList()))
        var createRecordResult: Result<RepoWriteResult> =
            Result.success(
                RepoWriteResult(
                    uri = AtUri.require("at://did:web:alice.logdate.app/studio.hypertext.logdate.content/entry-1"),
                    cid = "bafy-create",
                    validationStatus = RepoValidationStatus.UNKNOWN,
                ),
            )
        var putRecordResult: Result<RepoWriteResult> =
            Result.success(
                RepoWriteResult(
                    uri = AtUri.require("at://did:web:alice.logdate.app/studio.hypertext.logdate.content/entry-1"),
                    cid = "bafy-put",
                    validationStatus = RepoValidationStatus.UNKNOWN,
                ),
            )
        var deleteRecordResult: Result<Boolean> = Result.success(true)
        var loadHeadResult: Result<RepoHead?> = Result.success(null)
        var listCommitsResult: Result<List<SignedRepoCommit>> = Result.success(emptyList())
        var exportResult: Result<RepoExport> =
            Result.success(
                RepoExport(
                    repo = DEFAULT_REPO,
                    head = DEFAULT_HEAD,
                    commits = emptyList(),
                    blocks = emptyList(),
                ),
            )
        var lastListLimit: Int? = null
        var lastListCursor: String? = null
        var lastListReverse: Boolean? = null

        override suspend fun getRecord(recordId: RepoRecordId): Result<RepoRecord?> = getRecordResult

        override suspend fun listRecords(
            repo: AtprotoDid,
            collection: Nsid,
            limit: Int,
            cursor: String?,
            reverse: Boolean,
        ): Result<RepoListPage> {
            lastListLimit = limit
            lastListCursor = cursor
            lastListReverse = reverse
            return listRecordsResult
        }

        override suspend fun createRecord(
            repo: AtprotoDid,
            collection: Nsid,
            value: JsonObject,
            recordKey: RecordKey?,
        ): Result<RepoWriteResult> = createRecordResult

        override suspend fun putRecord(
            recordId: RepoRecordId,
            value: JsonObject,
            swapRecord: String?,
        ): Result<RepoWriteResult> = putRecordResult

        override suspend fun deleteRecord(
            recordId: RepoRecordId,
            swapRecord: String?,
        ): Result<Boolean> = deleteRecordResult

        override suspend fun loadHead(repo: AtprotoDid): Result<RepoHead?> = loadHeadResult

        override suspend fun listCommits(
            repo: AtprotoDid,
            limit: Int,
        ): Result<List<SignedRepoCommit>> = listCommitsResult

        override suspend fun export(
            repo: AtprotoDid,
            since: Tid?,
        ): Result<RepoExport> = exportResult

        override suspend fun import(export: RepoExport): Result<RepoHead> = Result.success(export.head)

        private companion object {
            private val DEFAULT_REPO = AtprotoDid.require("did:web:alice.logdate.app")
            private val DEFAULT_CID = Cid.sha256(codec = 0x71, bytes = "head".toByteArray())
            private val DEFAULT_HEAD =
                RepoHead(
                    repo = DEFAULT_REPO,
                    root = DEFAULT_CID,
                    commitCid = DEFAULT_CID,
                    revision = 1L,
                )
        }
    }
}
