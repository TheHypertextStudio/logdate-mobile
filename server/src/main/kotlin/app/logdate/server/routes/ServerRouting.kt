package app.logdate.server.routes

import app.logdate.server.ServerDescriptorConfig
import app.logdate.server.accountkeys.AccountKeyEnvelopeRepository
import app.logdate.server.atproto.AtprotoSessionTokenService
import app.logdate.server.atproto.firehose.AtprotoFirehoseConfig
import app.logdate.server.atproto.firehose.PublicRepoAccess
import app.logdate.server.atproto.firehose.RepoEventStream
import app.logdate.server.auth.AccessTokenVerifier
import app.logdate.server.auth.AccountDeletionService
import app.logdate.server.auth.AccountIdentityRepository
import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.AuthMetricsRegistry
import app.logdate.server.auth.EmailVerificationService
import app.logdate.server.auth.GoogleIdTokenVerifier
import app.logdate.server.auth.RefreshTokenRevocationRepository
import app.logdate.server.auth.SessionManager
import app.logdate.server.auth.TokenService
import app.logdate.server.auth.storageModes
import app.logdate.server.bluesky.BlueskyAccountContinuationService
import app.logdate.server.bluesky.BlueskyOAuthRuntime
import app.logdate.server.bluesky.BlueskyPasskeyAccountService
import app.logdate.server.bluesky.connection.AtprotoConnectionService
import app.logdate.server.bluesky.connection.AtprotoReconnectService
import app.logdate.server.devices.AccountDeviceRepository
import app.logdate.server.devices.DeviceLinkService
import app.logdate.server.enrollment.DeviceEnrollmentRepository
import app.logdate.server.entitlements.EntitlementEnforcer
import app.logdate.server.entitlements.EntitlementService
import app.logdate.server.entitlements.PlanCatalogService
import app.logdate.server.entitlements.UsageCalculator
import app.logdate.server.identity.AtprotoIdentityConfig
import app.logdate.server.identity.AtprotoIdentityService
import app.logdate.server.identity.SigningKeyService
import app.logdate.server.logdate.CompositeLogDateMediaBlobRepository
import app.logdate.server.logdate.LogDateBackupRepository
import app.logdate.server.logdate.LogDateBlobStorage
import app.logdate.server.logdate.LogDateCollectionsRepository
import app.logdate.server.logdate.LogDateMomentsRepository
import app.logdate.server.logdate.ResourceRouteRepository
import app.logdate.server.migration.LegacyMediaCopier
import app.logdate.server.migration.SpaceMigrationHolds
import app.logdate.server.migration.SpaceMigrationService
import app.logdate.server.oauth.OAuthAccessTokenService
import app.logdate.server.oauth.OAuthAuthorizationService
import app.logdate.server.oauth.OAuthConfig
import app.logdate.server.oauth.OAuthDpopVerifier
import app.logdate.server.oauth.OAuthKeyService
import app.logdate.server.oauth.OAuthNonceService
import app.logdate.server.oauth.OAuthPasskeySignInService
import app.logdate.server.passkeys.PasskeyRepository
import app.logdate.server.passkeys.RestoreCredentialService
import app.logdate.server.passkeys.WebAuthnConfig
import app.logdate.server.passkeys.WebAuthnPasskeyService
import app.logdate.server.passkeys.WebAuthnPasskeyStepUp
import app.logdate.server.ratelimit.SlidingWindowRateLimiter
import app.logdate.server.routes.sync.locationHistoryRoutes
import app.logdate.server.routes.sync.spaceMigrationRoutes
import app.logdate.server.space.SpaceStore
import app.logdate.server.sync.LocationHistoryRepository
import app.logdate.server.sync.SyncMetricsRegistry
import app.logdate.server.transcription.cloudTranscriptionSessionProviderFromEnvironment
import app.logdate.server.transfer.TransferSlots
import io.ktor.client.HttpClient
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import org.koin.ktor.ext.getKoin
import org.koin.ktor.ext.inject
import studio.hypertext.atproto.pds.PdsBlobService
import studio.hypertext.atproto.pds.PdsDiscoveryService
import studio.hypertext.atproto.pds.PdsRepoService
import studio.hypertext.atproto.pds.PdsSessionService
import studio.hypertext.atproto.pds.PdsSyncService
import kotlin.time.Duration.Companion.seconds

// The composition root: each function resolves what its routes need from Koin and hands it over
// as explicit parameters, so the route files themselves stay container-free and testable with
// in-memory implementations.

/** AT Protocol surface: identity, app links, OAuth, and XRPC. */
internal fun Application.atprotoRoutes() {
    val accountRepository by inject<AccountRepository>()
    val tokenService by inject<TokenService>()
    val atprotoIdentityService by inject<AtprotoIdentityService>()
    val assetLinksConfig by inject<AssetLinksConfig>()
    val pdsDiscoveryService by inject<PdsDiscoveryService>()
    val pdsRepoService by inject<PdsRepoService>()
    val pdsSessionService by inject<PdsSessionService>()
    val pdsSyncService by inject<PdsSyncService>()
    val pdsBlobService by inject<PdsBlobService>()
    val atprotoSessionTokenService by inject<AtprotoSessionTokenService>()
    val oauthConfig by inject<OAuthConfig>()
    val oauthKeyService by inject<OAuthKeyService>()
    val oauthAuthorizationService by inject<OAuthAuthorizationService>()
    val oauthAccessTokenService by inject<OAuthAccessTokenService>()
    val oauthDpopVerifier by inject<OAuthDpopVerifier>()
    val oauthNonceService by inject<OAuthNonceService>()
    val publicRepos = publicReposIfEnabled()
    val oauthPasskeySignIn = oauthPasskeySignInIfEnabled()

    routing {
        identityRoutes(atprotoIdentityService)
        assetLinksRoutes(assetLinksConfig)
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
            publicOrigin = oauthConfig.normalizedResource,
            publicRepos = publicRepos,
        )
    }
    publicRepos?.let { firehoseRoutes(it) }
    personalSpaceRoutes()
}

/**
 * Built eagerly so a misconfigured issuer stops the server at startup instead of serving a page
 * whose passkey button can never work.
 */
private fun Application.oauthPasskeySignInIfEnabled(): OAuthPasskeySignInService? {
    val oauthConfig by inject<OAuthConfig>()
    val oauthAuthorizationService by inject<OAuthAuthorizationService>()
    val webAuthnService by inject<WebAuthnPasskeyService>()
    val accountRepository by inject<AccountRepository>()
    val atprotoIdentityService by inject<AtprotoIdentityService>()
    return oauthConfig.takeIf(OAuthConfig::passkeySignInEnabled)?.let { config ->
        OAuthPasskeySignInService(
            config = config,
            authorizationService = oauthAuthorizationService,
            webAuthnService = webAuthnService,
            accountRepository = accountRepository,
            identityService = atprotoIdentityService,
        )
    }
}

/** What strangers may read, or `null` while the repository event stream is turned off. */
private fun Application.publicReposIfEnabled(): PublicRepoAccess? {
    val firehoseConfig by inject<AtprotoFirehoseConfig>()
    return if (firehoseConfig.enabled) inject<PublicRepoAccess>().value else null
}

/** `com.atproto.sync.subscribeRepos` and `listRepos`, served only while the event stream is turned on. */
private fun Application.firehoseRoutes(publicRepos: PublicRepoAccess) {
    val stream by inject<RepoEventStream>()
    install(WebSockets) {
        pingPeriod = WEBSOCKET_PING_PERIOD
        timeout = WEBSOCKET_TIMEOUT
        maxFrameSize = Long.MAX_VALUE
    }
    routing { repoEventStreamRoutes(stream, publicRepos) }
}

private val WEBSOCKET_PING_PERIOD = 20.seconds
private val WEBSOCKET_TIMEOUT = 60.seconds

/** `app.logdate.space.*` XRPC for the owner's own LogDate devices and linked watches, answering 501 until the server flag is on. */
private fun Application.personalSpaceRoutes() {
    val accountRepository by inject<AccountRepository>()
    val accessTokenVerifier by inject<AccessTokenVerifier>()
    val atprotoIdentityService by inject<AtprotoIdentityService>()
    val descriptorConfig by inject<ServerDescriptorConfig>()
    val spaceStore by inject<SpaceStore>()
    val blobStorage by inject<LogDateBlobStorage>()
    val entitlementEnforcer by inject<EntitlementEnforcer>()
    val transfers by inject<TransferSlots>()
    val migrationHolds by inject<SpaceMigrationHolds>()

    routing {
        spaceXrpcRoutes(
            enabled = descriptorConfig.personalSpaceEnabled,
            store = spaceStore,
            blobStorage = blobStorage,
            auth = LogDateAccountAuth(accessTokenVerifier, accountRepository, atprotoIdentityService),
            entitlementEnforcer = entitlementEnforcer,
            transfers = transfers,
            migrationHolds = migrationHolds,
        )
    }
}

/** `/api/v1` account surface: server info, plans, auth, and identity management. */
internal fun Application.accountApiRoutes() {
    blueskyAccountApiRoutes()
    val accountRepository by inject<AccountRepository>()
    val accountIdentityRepository by inject<AccountIdentityRepository>()
    val sessionManager by inject<SessionManager>()
    val webAuthnService by inject<WebAuthnPasskeyService>()
    val restoreCredentialService by inject<RestoreCredentialService>()
    val atprotoIdentityService by inject<AtprotoIdentityService>()
    val tokenService by inject<TokenService>()
    val refreshTokenRevocationRepository by inject<RefreshTokenRevocationRepository>()
    val googleIdTokenVerifier by inject<GoogleIdTokenVerifier>()
    val authMetrics by inject<AuthMetricsRegistry>()
    val accountDeletionService by inject<AccountDeletionService>()
    val entitlementService by inject<EntitlementService>()
    val planCatalogService by inject<PlanCatalogService>()
    val emailVerificationService by inject<EmailVerificationService>()
    val deviceEnrollmentRepository by inject<DeviceEnrollmentRepository>()
    val accountDevices by inject<AccountDeviceRepository>()
    val accountKeyEnvelopes by inject<AccountKeyEnvelopeRepository>()
    val passkeys by inject<PasskeyRepository>()
    val descriptorConfig by inject<ServerDescriptorConfig>()
    val deviceLinkService by inject<DeviceLinkService>()
    val oauthGrantRevoker by inject<OAuthAuthorizationService>()
    val accessTokenVerifier by inject<AccessTokenVerifier>()

    routing {
        route("/api/v1") {
            serverInfoRoutes(serverDescriptor())
            deviceEnrollmentRoutes(tokenService, deviceEnrollmentRepository, accountRepository)
            deviceRoutes(deviceLinkService, accessTokenVerifier)
            accountDeviceRoutes(tokenService, accountDevices)
            accountKeyEnvelopeRoutes(tokenService, passkeys, accountKeyEnvelopes, descriptorConfig.encryptedAccountKeysEnabled)
            planRoutes(planCatalogService)
            authV1Routes(
                accountRepository = accountRepository,
                identityRepository = accountIdentityRepository,
                sessionManager = sessionManager,
                webAuthnService = webAuthnService,
                restoreCredentialService = restoreCredentialService,
                atprotoIdentityService = atprotoIdentityService,
                tokenService = tokenService,
                refreshTokenRevocationRepository = refreshTokenRevocationRepository,
                googleIdTokenVerifier = googleIdTokenVerifier,
                metrics = authMetrics,
                accountDeletionService = accountDeletionService,
                entitlementService = entitlementService,
                emailVerificationService = emailVerificationService,
                deviceLinkService = deviceLinkService,
                accessTokenVerifier = accessTokenVerifier,
                oauthGrantRevoker = oauthGrantRevoker,
                newAccountStorageMode = descriptorConfig.newAccountStorageMode,
                credentialMutationLock = getKoin().get(),
            )
        }
    }
    identityApiRegistration()
}

/** `/api/v1` identity surface: handle, recovery key, and server signing key changes. */
private fun Application.identityApiRegistration() {
    val accountRepository by inject<AccountRepository>()
    val tokenService by inject<TokenService>()
    val atprotoIdentityService by inject<AtprotoIdentityService>()
    val signingKeyService by inject<SigningKeyService>()
    val webAuthnService by inject<WebAuthnPasskeyService>()

    routing {
        route("/api/v1") {
            identityApiRoutes(
                accountRepository = accountRepository,
                tokenService = tokenService,
                atprotoIdentityService = atprotoIdentityService,
                signingKeyService = signingKeyService,
                passkeyStepUp = WebAuthnPasskeyStepUp(webAuthnService),
            )
        }
    }
}

/** `/api/v1` content surface: sync, quota, resources, and transcription. */
internal fun Application.contentApiRoutes() {
    val accountRepository by inject<AccountRepository>()
    val tokenService by inject<TokenService>()
    val entitlementService by inject<EntitlementService>()
    val entitlementEnforcer by inject<EntitlementEnforcer>()
    val usageCalculator by inject<UsageCalculator>()
    val locationHistoryRepository by inject<LocationHistoryRepository>()
    val syncMetrics by inject<SyncMetricsRegistry>()
    val diagnosticReportAvailability by inject<app.logdate.server.diagnostics.DiagnosticReportAvailability>()
    val diagnosticReportStore by inject<app.logdate.server.diagnostics.DiagnosticReportStore>()
    val blobStorage by inject<LogDateBlobStorage>()
    val logDateCollectionsRepository by inject<LogDateCollectionsRepository>()
    val logDateMediaBlobRepository by inject<CompositeLogDateMediaBlobRepository>()
    val logDateBackupRepository by inject<LogDateBackupRepository>()
    val resourceRouteRepository by inject<ResourceRouteRepository>()
    val httpClient by inject<HttpClient>()
    val accessTokenVerifier by inject<AccessTokenVerifier>()
    val momentsRepository by inject<LogDateMomentsRepository>()
    val syncRateLimiter = SlidingWindowRateLimiter()
    val transfers by inject<TransferSlots>()
    val migrationHolds by inject<SpaceMigrationHolds>()
    val storageModes = accountRepository.storageModes(migrationHolds)

    routing {
        route("/api/v1") {
            locationHistoryRoutes(tokenService, locationHistoryRepository, storageModes)
            syncRoutes(
                tokenService = tokenService,
                mediaStorage = blobStorage,
                metrics = syncMetrics,
                collectionsRepository = logDateCollectionsRepository,
                mediaBlobRepository = logDateMediaBlobRepository,
                backupRepository = logDateBackupRepository,
                entitlementEnforcer = entitlementEnforcer,
                rateLimiter = syncRateLimiter,
                diagnosticReportService = diagnosticReportService(diagnosticReportAvailability, diagnosticReportStore),
                accessTokenVerifier = accessTokenVerifier,
                momentsRepository = momentsRepository,
                storageModes = storageModes,
                transfers = transfers,
            )
            quotaRoutes(
                tokenService = tokenService,
                entitlementService = entitlementService,
                usageCalculator = usageCalculator,
            )
            resourceRoutes(
                accountRepository = accountRepository,
                collectionsRepository = logDateCollectionsRepository,
                resourceRouteRepository = resourceRouteRepository,
            )
            transcriptionRoutes(
                tokenService = tokenService,
                entitlementService = entitlementService,
                sessionProvider = cloudTranscriptionSessionProviderFromEnvironment(httpClient),
            )
        }
    }
}

/** `/api/v1/space-migration`: moving existing journals into personal spaces, and switching moved ones back. */
internal fun Application.spaceMigrationApiRoutes() {
    val accountRepository by inject<AccountRepository>()
    val tokenService by inject<TokenService>()
    val syncMetrics by inject<SyncMetricsRegistry>()
    val transfers by inject<TransferSlots>()
    val descriptorConfig by inject<ServerDescriptorConfig>()
    val migrationService by inject<SpaceMigrationService>()
    val mediaCopier by inject<LegacyMediaCopier>()

    routing {
        route("/api/v1") {
            spaceMigrationRoutes(
                tokenService = tokenService,
                service = migrationService,
                copier = mediaCopier,
                accounts = accountRepository,
                transfers = transfers,
                metrics = syncMetrics,
                newMoves = { descriptorConfig.movesJournalsToPersonalSpaces },
            )
        }
    }
}

private fun diagnosticReportService(
    availability: app.logdate.server.diagnostics.DiagnosticReportAvailability,
    store: app.logdate.server.diagnostics.DiagnosticReportStore,
): app.logdate.server.diagnostics.DiagnosticReportService? =
    availability.keyring
        ?.takeIf { availability.enabled }
        ?.let {
            app.logdate.server.diagnostics
                .DiagnosticReportService(store, it)
        }

private fun Application.serverDescriptor() =
    inject<ServerDescriptorConfig>().value.toDescriptor(
        identityConfig = inject<AtprotoIdentityConfig>().value,
        webAuthnRpId = inject<WebAuthnConfig>().value.relyingPartyId,
        webAuthnRpName = inject<WebAuthnConfig>().value.relyingPartyName,
        diagnosticReportsEnabled = inject<app.logdate.server.diagnostics.DiagnosticReportAvailability>().value.enabled,
        blueskySignInEnabled = getKoin().getOrNull<BlueskyOAuthRuntime>() != null,
        atprotoConnectionEnabled = getKoin().getOrNull<AtprotoConnectionService>() != null,
    )

internal fun Application.blueskyApiRoutes() {
    val runtime = getKoin().getOrNull<BlueskyOAuthRuntime>() ?: return
    routing {
        blueskyMetadataRoutes(runtime.client)
        blueskyCallbackRoutes(runtime.signIn)
    }
}

internal fun Application.blueskyAccountApiRoutes() {
    val runtime = getKoin().getOrNull<BlueskyOAuthRuntime>() ?: return
    check(getKoin().get<ServerDescriptorConfig>().personalSpaceEnabled) { "Bluesky sign-in requires personal-space storage" }
    val continuations = getKoin().get<BlueskyAccountContinuationService>()
    val confirmations = getKoin().get<BlueskyPasskeyAccountService>()
    val accounts = getKoin().get<AccountRepository>()
    val identities = getKoin().get<AccountIdentityRepository>()
    val passkeys = getKoin().get<WebAuthnPasskeyService>()
    val hostedIdentities = getKoin().get<AtprotoIdentityService>()
    val tokens = getKoin().get<TokenService>()
    routing {
        route("/api/v1") {
            blueskyAccountRoutes(continuations, confirmations, runtime.signIn) { account ->
                issueAuthResponse(account, accounts, identities, passkeys, hostedIdentities, tokens, passkeyConfirmed = true)
            }
            getKoin().getOrNull<AtprotoConnectionService>()?.let { connection ->
                atprotoConnectionRoutes(
                    accounts,
                    tokens,
                    connection,
                    getKoin().get<AuthMetricsRegistry>(),
                    getKoin().get<AtprotoReconnectService>(),
                )
            }
        }
    }
}
