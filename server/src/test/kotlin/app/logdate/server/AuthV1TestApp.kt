package app.logdate.server

import app.logdate.server.atproto.AtprotoPasswordService
import app.logdate.server.atproto.AtprotoPdsSessionService
import app.logdate.server.atproto.AtprotoSessionTokenService
import app.logdate.server.atproto.InMemoryAtprotoPasswordCredentialRepository
import app.logdate.server.atproto.InMemoryAtprotoSessionRepository
import app.logdate.server.atproto.LogDatePdsBlobStore
import app.logdate.server.atproto.LogDateRepoStore
import app.logdate.server.auth.AccessTokenVerifier
import app.logdate.server.auth.AccountDeletionService
import app.logdate.server.auth.AccountIdentityRepository
import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.AuthMetricsRegistry
import app.logdate.server.auth.EmailVerificationService
import app.logdate.server.auth.FakeGoogleIdTokenVerifier
import app.logdate.server.auth.GoogleIdTokenClaims
import app.logdate.server.auth.GoogleIdTokenVerifier
import app.logdate.server.auth.InMemoryAccountIdentityRepository
import app.logdate.server.auth.InMemoryAccountRepository
import app.logdate.server.auth.InMemoryRefreshTokenRevocationRepository
import app.logdate.server.auth.InMemorySessionManager
import app.logdate.server.auth.JwtTokenService
import app.logdate.server.auth.RefreshTokenRevocationRepository
import app.logdate.server.auth.SessionManager
import app.logdate.server.bluesky.connection.AccountCredentialMutationLock
import app.logdate.server.bluesky.connection.InProcessAccountCredentialMutationLock
import app.logdate.server.devices.DeviceLinkService
import app.logdate.server.devices.DeviceSessionGuard
import app.logdate.server.entitlements.EntitlementEnforcer
import app.logdate.server.entitlements.EntitlementService
import app.logdate.server.identity.AtprotoIdentityConfig
import app.logdate.server.identity.AtprotoIdentityService
import app.logdate.server.identity.InMemorySigningKeyRepository
import app.logdate.server.identity.SigningKeyService
import app.logdate.server.logdate.CompositeLogDateMediaBlobRepository
import app.logdate.server.logdate.InMemoryLogDateAtprotoBlobRepository
import app.logdate.server.logdate.InMemoryLogDateBackupRepository
import app.logdate.server.logdate.InMemoryLogDateBlobStorage
import app.logdate.server.logdate.InMemoryLogDateCollectionsMetadataStore
import app.logdate.server.logdate.InMemoryLogDateMediaRepository
import app.logdate.server.logdate.LogDateBlobStorage
import app.logdate.server.logdate.RepoBackedLogDateCollectionsRepository
import app.logdate.server.migration.SpaceMigrationHolds
import app.logdate.server.oauth.OAuthAccessTokenService
import app.logdate.server.oauth.OAuthAuthorizationService
import app.logdate.server.oauth.OAuthClientMetadataResolver
import app.logdate.server.oauth.OAuthConfig
import app.logdate.server.oauth.OAuthDpopVerifier
import app.logdate.server.oauth.OAuthKeyService
import app.logdate.server.oauth.OAuthNonceService
import app.logdate.server.oauth.OAuthPasskeySignInService
import app.logdate.server.passkeys.InMemoryRestoreCredentialRepository
import app.logdate.server.passkeys.RestoreCredentialService
import app.logdate.server.passkeys.WebAuthnPasskeyService
import app.logdate.server.passkeys.WebAuthnPasskeyStepUp
import app.logdate.server.routes.LogDateAccountAuth
import app.logdate.server.routes.MAX_SPACE_BLOB_BYTES
import app.logdate.server.routes.SpaceRateLimits
import app.logdate.server.routes.authV1Routes
import app.logdate.server.routes.identityApiRoutes
import app.logdate.server.routes.identityRoutes
import app.logdate.server.routes.oauthRoutes
import app.logdate.server.routes.spaceXrpcRoutes
import app.logdate.server.routes.xrpcRoutes
import app.logdate.server.space.InMemorySpaceStore
import app.logdate.server.space.SpaceStore
import app.logdate.server.sync.InMemorySyncRepository
import app.logdate.server.sync.SyncRepository
import app.logdate.server.transfer.TransferLimits
import app.logdate.server.transfer.TransferSlots
import app.logdate.util.UuidSerializer
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.TestApplicationBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import studio.hypertext.atproto.pds.DescribeServerResponse
import studio.hypertext.atproto.pds.runtime.DefaultPdsBlobService
import studio.hypertext.atproto.pds.runtime.DefaultPdsRepoService
import studio.hypertext.atproto.pds.runtime.DefaultPdsSyncService
import studio.hypertext.atproto.pds.runtime.StaticPdsDiscoveryService
import studio.hypertext.atproto.repo.InMemoryRepoBlockStore
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class AuthV1TestEnvironment(
    val tokenService: JwtTokenService,
    val accountRepository: AccountRepository,
    val identityRepository: AccountIdentityRepository,
    val metrics: AuthMetricsRegistry,
    val syncRepository: SyncRepository,
    val signingKeyService: SigningKeyService,
    val atprotoIdentityService: AtprotoIdentityService,
    val oauthConfig: OAuthConfig,
    val oauthKeyService: OAuthKeyService,
    val oauthNonceService: OAuthNonceService,
    val oauthDpopVerifier: OAuthDpopVerifier,
    val oauthAccessTokenService: OAuthAccessTokenService,
    val oauthAuthorizationService: OAuthAuthorizationService,
    val blobStorage: LogDateBlobStorage,
    val spaceStore: SpaceStore,
    val atprotoSessionTokenService: AtprotoSessionTokenService,
)

@OptIn(ExperimentalUuidApi::class)
fun TestApplicationBuilder.configureAuthV1TestApp(
    tokenService: JwtTokenService = JwtTokenService("auth-v1-test-secret"),
    refreshTokenRevocationRepository: RefreshTokenRevocationRepository = InMemoryRefreshTokenRevocationRepository(),
    accountRepository: AccountRepository = InMemoryAccountRepository(),
    identityRepository: AccountIdentityRepository = InMemoryAccountIdentityRepository(),
    sessionManager: SessionManager = InMemorySessionManager(),
    webAuthnPasskeyService: WebAuthnPasskeyService = WebAuthnPasskeyService(),
    restoreCredentialService: RestoreCredentialService = RestoreCredentialService(InMemoryRestoreCredentialRepository()),
    metrics: AuthMetricsRegistry = AuthMetricsRegistry(),
    googleIdTokenVerifier: GoogleIdTokenVerifier? = null,
    googleClaimsByToken: Map<String, GoogleIdTokenClaims> = emptyMap(),
    atprotoIdentityConfig: AtprotoIdentityConfig = AtprotoIdentityConfig("logdate.app", "https://logdate.app"),
    syncRepository: SyncRepository = InMemorySyncRepository(),
    oauthConfig: OAuthConfig = OAuthConfig(issuer = atprotoIdentityConfig.pdsServiceEndpoint),
    oauthClientMetadataResolver: OAuthClientMetadataResolver? = null,
    emailVerificationService: EmailVerificationService? = null,
    entitlementService: EntitlementService? = null,
    deviceLinkService: DeviceLinkService? = null,
    accessTokenVerifier: AccessTokenVerifier? = null,
    personalSpaceEnabled: Boolean = false,
    spaceStore: SpaceStore = InMemorySpaceStore(),
    spaceMaxBlobBytes: Long = MAX_SPACE_BLOB_BYTES,
    oauthPasskeySignInEnabled: Boolean = false,
    spaceRateLimits: SpaceRateLimits = SpaceRateLimits(),
    spaceTransferLimits: TransferLimits = TransferLimits(),
    spaceEntitlementEnforcer: EntitlementEnforcer? = null,
    spaceMigrationHolds: SpaceMigrationHolds = SpaceMigrationHolds.None,
    additionalAccountRoutes: io.ktor.server.routing.Route.() -> Unit = {},
    credentialMutationLock: AccountCredentialMutationLock = InProcessAccountCredentialMutationLock(),
): AuthV1TestEnvironment {
    val googleVerifier: GoogleIdTokenVerifier = googleIdTokenVerifier ?: FakeGoogleIdTokenVerifier(googleClaimsByToken)
    val verifier = accessTokenVerifier ?: AccessTokenVerifier(tokenService, DeviceSessionGuard.Disabled)
    val newAccountStorageMode = ServerDescriptorConfig(personalSpaceEnabled = personalSpaceEnabled).newAccountStorageMode
    val signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "test-signing-key-kek")
    val atprotoPasswordService = AtprotoPasswordService(InMemoryAtprotoPasswordCredentialRepository())
    val atprotoSessionTokenService = AtprotoSessionTokenService(InMemoryAtprotoSessionRepository(), secret = "test")
    val atprotoIdentityService =
        AtprotoIdentityService(
            accountRepository = accountRepository,
            signingKeyService = signingKeyService,
            config = atprotoIdentityConfig,
        )
    val repoBlockStore = InMemoryRepoBlockStore()
    val blobStorage = InMemoryLogDateBlobStorage()
    val atprotoBlobRepository = InMemoryLogDateAtprotoBlobRepository()
    val mediaRepository = InMemoryLogDateMediaRepository()
    val mediaBlobRepository =
        CompositeLogDateMediaBlobRepository(
            mediaRepository = mediaRepository,
            atprotoBlobRepository = atprotoBlobRepository,
        )
    val logDateCollectionsRepository =
        RepoBackedLogDateCollectionsRepository(
            accountRepository = accountRepository,
            identityService = atprotoIdentityService,
            signingKeyService = signingKeyService,
            blockStore = repoBlockStore,
            metadataStore = InMemoryLogDateCollectionsMetadataStore(),
        )
    val logDateRepoStore =
        LogDateRepoStore(
            collectionsRepository = logDateCollectionsRepository,
            identityService = atprotoIdentityService,
            signingKeyService = signingKeyService,
            accountRepository = accountRepository,
            blockStore = repoBlockStore,
        )
    atprotoIdentityService.setRepoCollectionsResolver(logDateRepoStore::collectionsForDid)
    val oauthKeyService = OAuthKeyService()
    val oauthNonceService = OAuthNonceService()
    val oauthDpopVerifier = OAuthDpopVerifier()
    val oauthAccessTokenService = OAuthAccessTokenService(config = oauthConfig, keyService = oauthKeyService)
    val pdsRepoService = DefaultPdsRepoService(logDateRepoStore)
    val pdsSyncService = DefaultPdsSyncService(logDateRepoStore)
    val pdsBlobService =
        DefaultPdsBlobService(
            LogDatePdsBlobStore(
                identityService = atprotoIdentityService,
                mediaBlobRepository = mediaBlobRepository,
                blobStorage = blobStorage,
            ),
        )
    val pdsSessionService =
        AtprotoPdsSessionService(
            accountRepository = accountRepository,
            identityService = atprotoIdentityService,
            passwordService = atprotoPasswordService,
            sessionTokenService = atprotoSessionTokenService,
            newAccountStorageMode = newAccountStorageMode,
        )
    val pdsDiscoveryService =
        StaticPdsDiscoveryService(
            authorizationServerMetadata = oauthConfig.authorizationServerMetadata(),
            protectedResourceMetadata = oauthConfig.protectedResourceMetadata(),
            describeServerResponse =
                DescribeServerResponse(
                    did = atprotoIdentityConfig.serverDid,
                    availableUserDomains = listOf(atprotoIdentityConfig.normalizedHandleDomain),
                    inviteCodeRequired = false,
                    phoneVerificationRequired = false,
                ),
        )
    val metadataResolver = oauthClientMetadataResolver ?: OAuthClientMetadataResolver(HttpClient(OkHttp))
    val oauthAuthorizationService =
        OAuthAuthorizationService(
            clientMetadataResolver = metadataResolver,
            dpopVerifier = oauthDpopVerifier,
            accessTokenService = oauthAccessTokenService,
            nonceService = oauthNonceService,
            authorizationServerIssuer = oauthConfig.normalizedIssuer,
            trustedClientIds = oauthConfig.trustedClientIds,
        )
    val oauthPasskeySignIn =
        if (oauthPasskeySignInEnabled) {
            OAuthPasskeySignInService(
                config = oauthConfig.copy(passkeySignInEnabled = true),
                authorizationService = oauthAuthorizationService,
                webAuthnService = webAuthnPasskeyService,
                accountRepository = accountRepository,
                identityService = atprotoIdentityService,
            )
        } else {
            null
        }

    val json =
        Json {
            prettyPrint = true
            isLenient = true
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
            identityRoutes(atprotoIdentityService)
            oauthRoutes(
                config = oauthConfig,
                keyService = oauthKeyService,
                discoveryService = pdsDiscoveryService,
                authorizationService = oauthAuthorizationService,
                accountRepository = accountRepository,
                tokenService = tokenService,
                identityService = atprotoIdentityService,
                passkeySignIn = oauthPasskeySignIn,
            )
            xrpcRoutes(
                identityService = atprotoIdentityService,
                discoveryService = pdsDiscoveryService,
                accountRepository = accountRepository,
                tokenService = tokenService,
                repoService = pdsRepoService,
                sessionService = pdsSessionService,
                syncService = pdsSyncService,
                blobService = pdsBlobService,
                atprotoSessionTokenService = atprotoSessionTokenService,
                oauthAccessTokenService = oauthAccessTokenService,
                oauthDpopVerifier = oauthDpopVerifier,
                oauthNonceService = oauthNonceService,
            )
            spaceXrpcRoutes(
                enabled = personalSpaceEnabled,
                store = spaceStore,
                blobStorage = blobStorage,
                auth = LogDateAccountAuth(verifier, accountRepository, atprotoIdentityService),
                entitlementEnforcer = spaceEntitlementEnforcer,
                rateLimits = spaceRateLimits,
                transfers = TransferSlots(spaceTransferLimits),
                maxBlobBytes = spaceMaxBlobBytes,
                migrationHolds = spaceMigrationHolds,
            )
            route("/api/v1") {
                additionalAccountRoutes()
                authV1Routes(
                    accountRepository = accountRepository,
                    identityRepository = identityRepository,
                    sessionManager = sessionManager,
                    webAuthnService = webAuthnPasskeyService,
                    restoreCredentialService = restoreCredentialService,
                    atprotoIdentityService = atprotoIdentityService,
                    tokenService = tokenService,
                    refreshTokenRevocationRepository = refreshTokenRevocationRepository,
                    googleIdTokenVerifier = googleVerifier,
                    metrics = metrics,
                    credentialMutationLock = credentialMutationLock,
                    entitlementService = entitlementService,
                    emailVerificationService = emailVerificationService,
                    deviceLinkService = deviceLinkService,
                    oauthGrantRevoker = oauthAuthorizationService,
                    accountDeletionService =
                        AccountDeletionService(
                            accountRepository = accountRepository,
                            mediaBlobRepository = mediaBlobRepository,
                            backupRepository = InMemoryLogDateBackupRepository(),
                            blobStorage = blobStorage,
                            oauthGrantRevoker = oauthAuthorizationService,
                        ),
                    accessTokenVerifier = verifier,
                    newAccountStorageMode = newAccountStorageMode,
                )
                identityApiRoutes(
                    accountRepository = accountRepository,
                    tokenService = tokenService,
                    atprotoIdentityService = atprotoIdentityService,
                    signingKeyService = signingKeyService,
                    passkeyStepUp = WebAuthnPasskeyStepUp(webAuthnPasskeyService),
                )
            }
        }
    }

    return AuthV1TestEnvironment(
        tokenService = tokenService,
        accountRepository = accountRepository,
        identityRepository = identityRepository,
        metrics = metrics,
        syncRepository = syncRepository,
        signingKeyService = signingKeyService,
        atprotoIdentityService = atprotoIdentityService,
        oauthConfig = oauthConfig,
        oauthKeyService = oauthKeyService,
        oauthNonceService = oauthNonceService,
        oauthDpopVerifier = oauthDpopVerifier,
        oauthAccessTokenService = oauthAccessTokenService,
        oauthAuthorizationService = oauthAuthorizationService,
        blobStorage = blobStorage,
        spaceStore = spaceStore,
        atprotoSessionTokenService = atprotoSessionTokenService,
    )
}
