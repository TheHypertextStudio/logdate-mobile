package app.logdate.server.atproto.firehose

import app.logdate.server.atproto.LogDatePdsBlobStore
import app.logdate.server.atproto.LogDateRepoStore
import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountModerationService
import app.logdate.server.auth.InMemoryAccountRepository
import app.logdate.server.auth.JwtTokenService
import app.logdate.server.identity.AtprotoIdentityConfig
import app.logdate.server.identity.AtprotoIdentityService
import app.logdate.server.identity.HostedAccountDidMethod
import app.logdate.server.identity.InMemorySigningKeyRepository
import app.logdate.server.identity.SigningKeyService
import app.logdate.server.logdate.CompositeLogDateMediaBlobRepository
import app.logdate.server.logdate.InMemoryLogDateAtprotoBlobRepository
import app.logdate.server.logdate.InMemoryLogDateBlobStorage
import app.logdate.server.logdate.InMemoryLogDateCollectionsMetadataStore
import app.logdate.server.logdate.InMemoryLogDateMediaRepository
import app.logdate.server.logdate.RepoBackedLogDateCollectionsRepository
import app.logdate.server.routes.repoEventStreamRoutes
import app.logdate.server.routes.xrpcRoutes
import app.logdate.server.serverJson
import app.logdate.shared.model.AccountStorageMode
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.websocket.WebSockets
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.pds.runtime.DefaultPdsBlobService
import studio.hypertext.atproto.pds.runtime.DefaultPdsRepoService
import studio.hypertext.atproto.pds.runtime.DefaultPdsSyncService
import studio.hypertext.atproto.repo.InMemoryRepoBlockStore
import studio.hypertext.atproto.repo.RepoBlockStore
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * A complete public-repository server built from in-memory parts: the real sequencing block store,
 * event log, gate, policy, streaming routes and repository routes, with `did:web` identities and explicit publication-consent fixtures.
 *
 * Accounts are created with [createAccount]; the ones in a personal space are shown, the legacy ones
 * are not, which is what most tests are about.
 */
@OptIn(ExperimentalUuidApi::class)
internal class FirehoseHarness(
    val clock: Clock = Clock.System,
    maxFollowers: Int = 8,
) {
    val tokens = JwtTokenService("firehose-test-secret")
    val accounts = InMemoryAccountRepository()
    val identityConfig = AtprotoIdentityConfig("logdate.app", "https://logdate.app", hostedAccountDidMethod = HostedAccountDidMethod.WEB)
    private val signingKeys = SigningKeyService(InMemorySigningKeyRepository(), "firehose-test-kek")
    val identity = AtprotoIdentityService(accounts, signingKeys, identityConfig)
    val rawBlocks = InMemoryRepoBlockStore()
    val eventStore = InMemoryRepoEventStore()
    val policy = PublicRepoPolicy()
    val gate = AccountPublicRepoGate(accounts, eventStore)
    val sequencer = RepoEventSequencer(eventStore, rawBlocks, gate, policy, clock)
    val blocks: RepoBlockStore = SequencingRepoBlockStore(rawBlocks, lazy { sequencer })
    val access = PublicRepoAccess(gate, policy, rawBlocks, AccountsPublicRepoDirectory(accounts, gate, rawBlocks))
    val stream =
        RepoEventStream(
            store = eventStore,
            wakeups = sequencer.announcements,
            pollInterval = 20.milliseconds,
            maxFollowers = maxFollowers,
            retention = 7.days,
            clock = clock,
        )
    val blobStorage = InMemoryLogDateBlobStorage()

    /** Every request the server sent to a relay, as the URL and the body. */
    val crawlRequests = CopyOnWriteArrayList<String>()
    val crawler =
        RelayCrawler(
            httpClient =
                HttpClient(
                    MockEngine { request ->
                        crawlRequests += "${request.url} ${String(request.body.toByteArray())}"
                        respond("{}", HttpStatusCode.OK)
                    },
                ),
            relayHosts = listOf("relay.example.com"),
            serverHostname = "logdate.app",
        )
    val announcer = PublicRepoAnnouncer(accounts, sequencer, crawler)
    val moderation = AccountModerationService(accounts, announcer)
    private val collections =
        RepoBackedLogDateCollectionsRepository(
            accountRepository = accounts,
            identityService = identity,
            signingKeyService = signingKeys,
            blockStore = blocks,
            metadataStore = InMemoryLogDateCollectionsMetadataStore(),
        )
    val repoStore =
        LogDateRepoStore(
            collectionsRepository = collections,
            identityService = identity,
            signingKeyService = signingKeys,
            accountRepository = accounts,
            blockStore = blocks,
            publicPolicy = policy,
            publicRecords = PublicRecordValidator(),
        )
    private val blobService =
        DefaultPdsBlobService(
            LogDatePdsBlobStore(
                identityService = identity,
                mediaBlobRepository =
                    CompositeLogDateMediaBlobRepository(InMemoryLogDateMediaRepository(), InMemoryLogDateAtprotoBlobRepository()),
                blobStorage = blobStorage,
            ),
        )

    /** Creates an account with an identity; [personalSpace] decides whether the server shows its repository. */
    suspend fun createAccount(
        username: String,
        personalSpace: Boolean = true,
        publicIdentity: Boolean = true,
    ): Account {
        val saved =
            accounts.save(
                Account(
                    id = Uuid.random(),
                    username = username,
                    displayName = username,
                    createdAt = clock.now(),
                    publicIdentityDid = if (publicIdentity) "did:web:$username.logdate.app" else null,
                    storageMode = if (personalSpace) AccountStorageMode.PERSONAL_SPACE_V1 else AccountStorageMode.LEGACY,
                ),
            )
        return identity.ensureIdentity(saved)
    }

    fun did(account: Account): AtprotoDid = AtprotoDid.require(checkNotNull(account.did))

    fun bearer(account: Account): String = tokens.generateAccessToken(account.id.toString())

    /** Installs JSON, WebSockets and the repository and stream routes into a test application. */
    fun install(test: ApplicationTestBuilder) {
        test.application {
            install(ContentNegotiation) { json(serverJson) }
            install(WebSockets)
            routing {
                xrpcRoutes(
                    identityService = identity,
                    accountRepository = accounts,
                    tokenService = tokens,
                    repoService = DefaultPdsRepoService(repoStore),
                    syncService = DefaultPdsSyncService(repoStore),
                    blobService = blobService,
                    publicRepos = access,
                )
                repoEventStreamRoutes(stream, access)
            }
        }
    }

    /** Writes [record] into [collection] as the account's owner would, through `com.atproto.repo.putRecord`. */
    suspend fun put(
        client: HttpClient,
        account: Account,
        collection: String,
        rkey: String,
        record: JsonObject,
        bearerOf: Account = account,
    ): HttpResponse =
        client.post("/xrpc/com.atproto.repo.putRecord") {
            header(HttpHeaders.Authorization, "Bearer ${bearer(bearerOf)}")
            contentType(ContentType.Application.Json)
            setBody(
                JsonObject(
                    mapOf(
                        "repo" to JsonPrimitive(account.did),
                        "collection" to JsonPrimitive(collection),
                        "rkey" to JsonPrimitive(rkey),
                        "record" to record,
                    ),
                ).toString(),
            )
        }
}
