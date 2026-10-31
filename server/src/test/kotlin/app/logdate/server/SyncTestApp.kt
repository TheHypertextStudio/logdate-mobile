package app.logdate.server

import app.logdate.server.auth.AccessTokenVerifier
import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.AccountStorageModes
import app.logdate.server.auth.InMemoryAccountRepository
import app.logdate.server.auth.JwtTokenService
import app.logdate.server.devices.DeviceSessionGuard
import app.logdate.server.identity.AtprotoIdentityConfig
import app.logdate.server.identity.AtprotoIdentityService
import app.logdate.server.identity.InMemorySigningKeyRepository
import app.logdate.server.identity.SigningKeyService
import app.logdate.server.logdate.InMemoryJournalMergeStore
import app.logdate.server.logdate.InMemoryLogDateBackupRepository
import app.logdate.server.logdate.InMemoryLogDateCollectionsMetadataStore
import app.logdate.server.logdate.InMemoryLogDateMediaRepository
import app.logdate.server.logdate.LogDateMomentsRepository
import app.logdate.server.logdate.MergeAwareLogDateCollectionsRepository
import app.logdate.server.logdate.RepoBackedLogDateCollectionsRepository
import app.logdate.server.logdate.RepoBackedLogDateMomentsRepository
import app.logdate.server.logdate.asLogDateMediaBlobRepository
import app.logdate.server.routes.syncRoutes
import app.logdate.server.sync.GcsMediaStorage
import app.logdate.server.sync.InMemorySyncRepository
import app.logdate.server.sync.MediaAccessPolicy
import app.logdate.server.sync.SyncMetricsRegistry
import app.logdate.util.UuidSerializer
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.TestApplicationBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import studio.hypertext.atproto.repo.InMemoryRepoBlockStore
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class SyncTestEnvironment(
    val tokenService: JwtTokenService,
    val repository: InMemorySyncRepository,
    val mediaRepository: InMemoryLogDateMediaRepository,
    val backupRepository: InMemoryLogDateBackupRepository,
    val metrics: SyncMetricsRegistry,
)

@OptIn(ExperimentalUuidApi::class)
fun TestApplicationBuilder.configureSyncTestApp(
    tokenService: JwtTokenService = JwtTokenService("test-secret-for-sync-tests"),
    repository: InMemorySyncRepository = InMemorySyncRepository(),
    metrics: SyncMetricsRegistry = SyncMetricsRegistry(),
    mediaStorage: GcsMediaStorage? = null,
    mediaAccessPolicy: MediaAccessPolicy = MediaAccessPolicy(useSignedUrls = false, signedUrlTtlHours = 1),
    accessTokenVerifier: AccessTokenVerifier? = null,
    momentDecorator: (LogDateMomentsRepository) -> LogDateMomentsRepository = { it },
    storageModes: AccountStorageModes? = null,
): SyncTestEnvironment {
    val accountRepository = InMemoryAccountRepository()
    val signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "sync-test-kek")
    val atprotoIdentityService =
        AtprotoIdentityService(
            accountRepository = accountRepository,
            signingKeyService = signingKeyService,
            config = AtprotoIdentityConfig(handleDomain = "logdate.app", pdsServiceEndpoint = "https://logdate.app"),
        )
    val blockStore = InMemoryRepoBlockStore()
    val metadataStore = InMemoryLogDateCollectionsMetadataStore()
    val collectionsRepository =
        RepoBackedLogDateCollectionsRepository(
            accountRepository = accountRepository,
            identityService = atprotoIdentityService,
            signingKeyService = signingKeyService,
            blockStore = blockStore,
            metadataStore = metadataStore,
        )
    val momentMutations = InMemoryJournalMergeStore()
    val momentsRepository =
        RepoBackedLogDateMomentsRepository(
            accountRepository = accountRepository,
            signingKeyService = signingKeyService,
            blockStore = blockStore,
            metadataStore = metadataStore,
            repoDidFor = collectionsRepository::canonicalRepoDid,
            mutations = momentMutations,
        )
    val mediaRepository = InMemoryLogDateMediaRepository()
    val backupRepository = InMemoryLogDateBackupRepository()
    val json =
        Json {
            ignoreUnknownKeys = true
            serializersModule =
                SerializersModule {
                    contextual(Uuid::class, UuidSerializer)
                }
        }

    application {
        install(ContentNegotiation) {
            json(json)
        }
        routing {
            route("/api/v1") {
                syncRoutes(
                    tokenService = tokenService,
                    mediaStorage = mediaStorage,
                    metrics = metrics,
                    mediaAccessPolicy = mediaAccessPolicy,
                    collectionsRepository = MergeAwareLogDateCollectionsRepository(collectionsRepository, momentMutations),
                    mediaBlobRepository = mediaRepository.asLogDateMediaBlobRepository(),
                    backupRepository = backupRepository,
                    accessTokenVerifier = accessTokenVerifier ?: AccessTokenVerifier(tokenService, DeviceSessionGuard.Disabled),
                    momentsRepository = momentDecorator(momentsRepository),
                    storageModes = storageModes,
                )
            }
        }
    }

    return SyncTestEnvironment(tokenService, repository, mediaRepository, backupRepository, metrics)
}

/** A moments repository over its own in-memory repo, for tests that mount `syncRoutes` themselves. */
internal fun inMemoryMomentsRepository(accountRepository: AccountRepository): LogDateMomentsRepository {
    val signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "moments-test-kek")
    val identityService =
        AtprotoIdentityService(
            accountRepository = accountRepository,
            signingKeyService = signingKeyService,
            config = AtprotoIdentityConfig(handleDomain = "logdate.app", pdsServiceEndpoint = "https://logdate.app"),
        )
    val blockStore = InMemoryRepoBlockStore()
    val metadataStore = InMemoryLogDateCollectionsMetadataStore()
    val collections =
        RepoBackedLogDateCollectionsRepository(accountRepository, identityService, signingKeyService, blockStore, metadataStore)
    return RepoBackedLogDateMomentsRepository(
        accountRepository,
        signingKeyService,
        blockStore,
        metadataStore,
        collections::canonicalRepoDid,
        InMemoryJournalMergeStore(),
    )
}
