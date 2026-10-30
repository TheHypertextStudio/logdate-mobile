package app.logdate.server.di

import app.logdate.server.ServerDescriptorConfig
import app.logdate.server.accountkeys.AccountKeyEnvelopeRepository
import app.logdate.server.accountkeys.InMemoryAccountKeyEnvelopeRepository
import app.logdate.server.atproto.AtprotoPasswordCredentialRepository
import app.logdate.server.atproto.AtprotoPasswordService
import app.logdate.server.atproto.AtprotoPdsSessionService
import app.logdate.server.atproto.AtprotoSessionRepository
import app.logdate.server.atproto.AtprotoSessionTokenService
import app.logdate.server.atproto.InMemoryAtprotoPasswordCredentialRepository
import app.logdate.server.atproto.InMemoryAtprotoSessionRepository
import app.logdate.server.atproto.LogDatePdsBlobStore
import app.logdate.server.atproto.LogDateRepoStore
import app.logdate.server.atproto.firehose.PublicRecordValidator
import app.logdate.server.atproto.firehose.PublicRepoPolicy
import app.logdate.server.auth.AccessTokenVerifier
import app.logdate.server.auth.AccountBlobCleanup
import app.logdate.server.auth.AccountDeletionService
import app.logdate.server.auth.AccountIdentityRepository
import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.AccountStatusEvents
import app.logdate.server.auth.AuthMetricsRegistry
import app.logdate.server.auth.DigitalCredentialVerifier
import app.logdate.server.auth.EmailVerificationService
import app.logdate.server.auth.GoogleIdTokenVerifier
import app.logdate.server.auth.GoogleVcJwksCache
import app.logdate.server.auth.HttpGoogleIdTokenVerifier
import app.logdate.server.auth.InMemoryAccountIdentityRepository
import app.logdate.server.auth.InMemoryAccountRepository
import app.logdate.server.auth.InMemoryPendingEmailVerificationRepository
import app.logdate.server.auth.InMemoryRefreshTokenRevocationRepository
import app.logdate.server.auth.InMemorySessionManager
import app.logdate.server.auth.JwtTokenService
import app.logdate.server.auth.PendingEmailVerificationRepository
import app.logdate.server.auth.RefreshTokenRevocationRepository
import app.logdate.server.auth.SessionManager
import app.logdate.server.auth.TokenService
import app.logdate.server.config.AtprotoSessionSecret
import app.logdate.server.config.AtprotoSigningKeyKek
import app.logdate.server.config.AtprotoSigningKeyKekDecryptOnly
import app.logdate.server.config.RuntimeProfile
import app.logdate.server.database.PostgreSQLAccountIdentityRepository
import app.logdate.server.database.PostgreSQLAccountKeyEnvelopeRepository
import app.logdate.server.database.PostgreSQLAccountRepository
import app.logdate.server.database.PostgreSQLAtprotoPasswordCredentialRepository
import app.logdate.server.database.PostgreSQLAtprotoSessionRepository
import app.logdate.server.database.PostgreSQLDeviceEnrollmentRepository
import app.logdate.server.database.PostgreSQLDeviceSessionRepository
import app.logdate.server.database.PostgreSQLDiagnosticReportStore
import app.logdate.server.database.PostgreSQLHostedPlcOperationRepository
import app.logdate.server.database.PostgreSQLJournalMergeStore
import app.logdate.server.database.PostgreSQLLogDateAtprotoBlobRepository
import app.logdate.server.database.PostgreSQLLogDateBackupRepository
import app.logdate.server.database.PostgreSQLLogDateCollectionsMetadataStore
import app.logdate.server.database.PostgreSQLLogDateMediaRepository
import app.logdate.server.database.PostgreSQLOAuthReplayStore
import app.logdate.server.database.PostgreSQLOAuthRuntimeStateRepository
import app.logdate.server.database.PostgreSQLOAuthSigningKeyRepository
import app.logdate.server.database.PostgreSQLPendingEmailVerificationRepository
import app.logdate.server.database.PostgreSQLRefreshTokenRevocationRepository
import app.logdate.server.database.PostgreSQLResourceRouteRepository
import app.logdate.server.database.PostgreSQLSessionManager
import app.logdate.server.database.PostgreSQLSigningKeyRepository
import app.logdate.server.database.PostgreSQLSpaceStore
import app.logdate.server.devices.DeviceLinkService
import app.logdate.server.devices.DeviceSessionGuard
import app.logdate.server.devices.DeviceSessionRepository
import app.logdate.server.devices.InMemoryDeviceSessionRepository
import app.logdate.server.diagnostics.DiagnosticReportAvailability
import app.logdate.server.diagnostics.DiagnosticReportStore
import app.logdate.server.diagnostics.InMemoryDiagnosticReportStore
import app.logdate.server.enrollment.DeviceEnrollmentRepository
import app.logdate.server.enrollment.InMemoryDeviceEnrollmentRepository
import app.logdate.server.identity.AtprotoIdentityConfig
import app.logdate.server.identity.AtprotoIdentityService
import app.logdate.server.identity.HostedPlcOperationRepository
import app.logdate.server.identity.InMemoryHostedPlcOperationRepository
import app.logdate.server.identity.InMemorySigningKeyRepository
import app.logdate.server.identity.PlcIdentityService
import app.logdate.server.identity.SigningKeyRepository
import app.logdate.server.identity.SigningKeyService
import app.logdate.server.logdate.CompositeLogDateMediaBlobRepository
import app.logdate.server.logdate.FilesystemLogDateBlobStorage
import app.logdate.server.logdate.InMemoryJournalMergeStore
import app.logdate.server.logdate.InMemoryLogDateAtprotoBlobRepository
import app.logdate.server.logdate.InMemoryLogDateBackupRepository
import app.logdate.server.logdate.InMemoryLogDateBlobStorage
import app.logdate.server.logdate.InMemoryLogDateCollectionsMetadataStore
import app.logdate.server.logdate.InMemoryLogDateMediaRepository
import app.logdate.server.logdate.InMemoryResourceRouteRepository
import app.logdate.server.logdate.JournalMergeStore
import app.logdate.server.logdate.LogDateAtprotoBlobRepository
import app.logdate.server.logdate.LogDateBackupRepository
import app.logdate.server.logdate.LogDateBlobStorage
import app.logdate.server.logdate.LogDateCollectionsMetadataStore
import app.logdate.server.logdate.LogDateCollectionsRepository
import app.logdate.server.logdate.LogDateMediaBlobRepository
import app.logdate.server.logdate.LogDateMediaRepository
import app.logdate.server.logdate.LogDateMomentsRepository
import app.logdate.server.logdate.MergeAwareLogDateCollectionsRepository
import app.logdate.server.logdate.RepoBackedLogDateCollectionsRepository
import app.logdate.server.logdate.RepoBackedLogDateMomentsRepository
import app.logdate.server.logdate.ResourceRouteRepository
import app.logdate.server.oauth.InMemoryOAuthReplayStore
import app.logdate.server.oauth.InMemoryOAuthRuntimeStateRepository
import app.logdate.server.oauth.InMemoryOAuthSigningKeyRepository
import app.logdate.server.oauth.OAuthAccessTokenService
import app.logdate.server.oauth.OAuthAuthorizationService
import app.logdate.server.oauth.OAuthClientMetadataResolver
import app.logdate.server.oauth.OAuthConfig
import app.logdate.server.oauth.OAuthDpopVerifier
import app.logdate.server.oauth.OAuthKeyRotationPolicy
import app.logdate.server.oauth.OAuthKeyService
import app.logdate.server.oauth.OAuthNonceService
import app.logdate.server.oauth.OAuthReplayStore
import app.logdate.server.oauth.OAuthRuntimeStateRepository
import app.logdate.server.oauth.OAuthSigningKeyRepository
import app.logdate.server.space.InMemorySpaceStore
import app.logdate.server.space.SpaceStore
import app.logdate.server.sync.DbLocationHistoryRepository
import app.logdate.server.sync.DbSyncRepository
import app.logdate.server.sync.GcsMediaStorage
import app.logdate.server.sync.InMemoryLocationHistoryRepository
import app.logdate.server.sync.InMemorySyncRepository
import app.logdate.server.sync.LocationHistoryRepository
import app.logdate.server.sync.SyncMetricsRegistry
import app.logdate.server.sync.SyncRepository
import app.logdate.server.transfer.TransferSlots
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import org.koin.dsl.bind
import org.koin.dsl.module
import studio.hypertext.atproto.oauth.client.JvmOAuthHttp
import studio.hypertext.atproto.pds.DescribeServerResponse
import studio.hypertext.atproto.pds.PdsBlobService
import studio.hypertext.atproto.pds.PdsDiscoveryService
import studio.hypertext.atproto.pds.PdsRepoService
import studio.hypertext.atproto.pds.PdsSessionService
import studio.hypertext.atproto.pds.PdsSyncService
import studio.hypertext.atproto.pds.runtime.DefaultPdsBlobService
import studio.hypertext.atproto.pds.runtime.DefaultPdsRepoService
import studio.hypertext.atproto.pds.runtime.DefaultPdsSyncService
import studio.hypertext.atproto.pds.runtime.StaticPdsDiscoveryService
import studio.hypertext.atproto.plc.KtorPlcDirectoryClient
import studio.hypertext.atproto.repo.RepoEngine
import studio.hypertext.atproto.repo.RepoRecordStore

private const val OUTBOUND_CONNECT_TIMEOUT_MILLIS = 10_000L
private const val OUTBOUND_REQUEST_TIMEOUT_MILLIS = 30_000L

/**
 * Creates server Koin module based on database availability.
 */
fun serverModule(isDatabaseAvailable: Boolean) =
    module {
        single<AccountRepository> {
            if (isDatabaseAvailable) PostgreSQLAccountRepository() else InMemoryAccountRepository()
        }

        single<AtprotoPasswordCredentialRepository> {
            if (isDatabaseAvailable) {
                PostgreSQLAtprotoPasswordCredentialRepository()
            } else {
                InMemoryAtprotoPasswordCredentialRepository()
            }
        }

        single<AtprotoSessionRepository> {
            if (isDatabaseAvailable) {
                PostgreSQLAtprotoSessionRepository()
            } else {
                InMemoryAtprotoSessionRepository()
            }
        }

        single<AccountIdentityRepository> {
            if (isDatabaseAvailable) {
                PostgreSQLAccountIdentityRepository()
            } else {
                InMemoryAccountIdentityRepository()
            }
        }

        single<SigningKeyRepository> {
            if (isDatabaseAvailable) PostgreSQLSigningKeyRepository() else InMemorySigningKeyRepository()
        }

        single<SessionManager> {
            if (isDatabaseAvailable) PostgreSQLSessionManager() else InMemorySessionManager()
        }

        single<RefreshTokenRevocationRepository> {
            if (isDatabaseAvailable) PostgreSQLRefreshTokenRevocationRepository() else InMemoryRefreshTokenRevocationRepository()
        }

        passkeyServices(isDatabaseAvailable)

        single<PendingEmailVerificationRepository> {
            if (isDatabaseAvailable) PostgreSQLPendingEmailVerificationRepository() else InMemoryPendingEmailVerificationRepository()
        }
        single<DeviceEnrollmentRepository> {
            if (isDatabaseAvailable) PostgreSQLDeviceEnrollmentRepository() else InMemoryDeviceEnrollmentRepository()
        }
        single<DeviceSessionRepository> {
            if (isDatabaseAvailable) PostgreSQLDeviceSessionRepository() else InMemoryDeviceSessionRepository()
        }
        single { DeviceSessionGuard(repository = get()) }
        single<AccountKeyEnvelopeRepository> {
            if (isDatabaseAvailable) PostgreSQLAccountKeyEnvelopeRepository() else InMemoryAccountKeyEnvelopeRepository()
        }
        single<SpaceStore> { if (isDatabaseAvailable) PostgreSQLSpaceStore() else InMemorySpaceStore() }
        single { TransferSlots() }
        spaceMigrationDefinitions(isDatabaseAvailable)
        single { GoogleVcJwksCache() }
        single {
            DigitalCredentialVerifier(
                jwksCache = get(),
                expectedAudience = "https://logdate.app/auth/email",
            )
        }
        single {
            EmailVerificationService(
                pendingRepository = get(),
                accountRepository = get(),
                verifier = get(),
            )
        }
        single { AtprotoIdentityConfig.fromEnvironment() }
        single { ServerDescriptorConfig.fromEnvironment() }
        single { DiagnosticReportAvailability.fromEnvironment(isDatabaseAvailable) }
        single<DiagnosticReportStore> {
            if (isDatabaseAvailable) PostgreSQLDiagnosticReportStore() else InMemoryDiagnosticReportStore()
        }
        single {
            OAuthConfig
                .fromEnvironment(defaultIssuer = get<AtprotoIdentityConfig>().pdsServiceEndpoint)
                .copy(
                    passkeySignInEnabled = OAuthConfig.passkeySignInEnabledFromEnvironment(),
                    trustedClientIds = OAuthConfig.trustedClientIdsFromEnvironment(),
                )
        }
        // Calls to the PLC directory, relays and transcription give up instead of holding a request.
        single {
            HttpClient(OkHttp) {
                install(HttpTimeout) {
                    connectTimeoutMillis = OUTBOUND_CONNECT_TIMEOUT_MILLIS
                    requestTimeoutMillis = OUTBOUND_REQUEST_TIMEOUT_MILLIS
                    socketTimeoutMillis = OUTBOUND_REQUEST_TIMEOUT_MILLIS
                }
            }
        }
        single<OAuthSigningKeyRepository> {
            if (isDatabaseAvailable) PostgreSQLOAuthSigningKeyRepository() else InMemoryOAuthSigningKeyRepository()
        }
        single {
            OAuthKeyService(
                repository = get(),
                encryptionKeySeed = AtprotoSigningKeyKek.resolve(),
                decryptOnlyEncryptionKeySeeds = listOfNotNull(AtprotoSigningKeyKekDecryptOnly.resolve()),
                rotationPolicy = OAuthKeyRotationPolicy.fromEnvironment(),
            )
        }
        single { OAuthNonceService() }
        single<OAuthReplayStore> {
            if (isDatabaseAvailable) PostgreSQLOAuthReplayStore() else InMemoryOAuthReplayStore()
        }
        single { OAuthDpopVerifier(usedProofs = get()) }
        single<OAuthRuntimeStateRepository> {
            if (isDatabaseAvailable) {
                PostgreSQLOAuthRuntimeStateRepository()
            } else {
                InMemoryOAuthRuntimeStateRepository()
            }
        }
        single {
            val production = RuntimeProfile.fromEnvironment().isProduction
            OAuthClientMetadataResolver(
                httpClient = if (production) JvmOAuthHttp.publicInternetClient() else get(),
                usedClientAssertions = get(),
                allowLoopbackClients = !production,
            )
        }
        single { OAuthAccessTokenService(config = get(), keyService = get()) }
        single {
            val config: OAuthConfig = get()
            OAuthAuthorizationService(
                clientMetadataResolver = get(),
                dpopVerifier = get(),
                accessTokenService = get(),
                nonceService = get(),
                authorizationServerIssuer = config.normalizedIssuer,
                runtimeStateRepository = get(),
                trustedClientIds = config.trustedClientIds,
                usedCodeChallenges = get(),
            )
        }
        single {
            SigningKeyService(
                repository = get(),
                encryptionKeySeed = AtprotoSigningKeyKek.resolve(),
                decryptOnlyEncryptionKeySeeds = listOfNotNull(AtprotoSigningKeyKekDecryptOnly.resolve()),
            )
        }
        single {
            PlcIdentityService(
                signingKeyService = get(),
                config = get(),
                hostedPlcOperationRepository = get(),
                plcDirectoryClient =
                    get<AtprotoIdentityConfig>()
                        .takeIf(AtprotoIdentityConfig::publishHostedPlcOperations)
                        ?.let { config ->
                            KtorPlcDirectoryClient(
                                httpClient = get(),
                                baseUrl = config.normalizedPlcDirectoryUrl,
                            )
                        },
            )
        }
        single {
            AtprotoIdentityService(
                accountRepository = get(),
                signingKeyService = get(),
                config = get(),
                plcIdentityService = get(),
            )
        }
        single { AtprotoPasswordService(repository = get()) }
        single { AtprotoSessionTokenService(sessionRepository = get(), secret = AtprotoSessionSecret.resolve()) }
        single<PdsSessionService> {
            AtprotoPdsSessionService(
                accountRepository = get(),
                identityService = get(),
                passwordService = get(),
                sessionTokenService = get(),
                newAccountStorageMode = get<ServerDescriptorConfig>().newAccountStorageMode,
            )
        }

        single<LocationHistoryRepository> {
            if (isDatabaseAvailable) DbLocationHistoryRepository() else InMemoryLocationHistoryRepository()
        }
        single<SyncRepository> {
            if (isDatabaseAvailable) DbSyncRepository() else InMemorySyncRepository()
        }

        firehoseDefinitions(isDatabaseAvailable)

        single<LogDateCollectionsMetadataStore> {
            if (isDatabaseAvailable) {
                PostgreSQLLogDateCollectionsMetadataStore()
            } else {
                InMemoryLogDateCollectionsMetadataStore()
            }
        }

        single<LogDateMediaRepository> {
            if (isDatabaseAvailable) {
                PostgreSQLLogDateMediaRepository()
            } else {
                InMemoryLogDateMediaRepository()
            }
        }

        single<LogDateBackupRepository> {
            if (isDatabaseAvailable) {
                PostgreSQLLogDateBackupRepository()
            } else {
                InMemoryLogDateBackupRepository()
            }
        }

        single<LogDateAtprotoBlobRepository> {
            if (isDatabaseAvailable) {
                PostgreSQLLogDateAtprotoBlobRepository()
            } else {
                InMemoryLogDateAtprotoBlobRepository()
            }
        }

        single<ResourceRouteRepository> {
            if (isDatabaseAvailable) {
                PostgreSQLResourceRouteRepository()
            } else {
                InMemoryResourceRouteRepository()
            }
        }

        single<HostedPlcOperationRepository> {
            if (isDatabaseAvailable) {
                PostgreSQLHostedPlcOperationRepository()
            } else {
                InMemoryHostedPlcOperationRepository()
            }
        }

        single { SyncMetricsRegistry() }
        single { AuthMetricsRegistry() }

        single<TokenService> {
            JwtTokenService(
                secret = System.getenv("JWT_SECRET") ?: JwtTokenService.generateSecret(),
            )
        }

        single { AccessTokenVerifier(tokenService = get(), guard = get()) }
        single { DeviceLinkService(repository = get(), tokenService = get(), accountRepository = get(), guard = get()) }

        single {
            CompositeLogDateMediaBlobRepository(
                mediaRepository = get(),
                atprotoBlobRepository = get(),
            )
        } bind LogDateMediaBlobRepository::class

        single {
            AccountDeletionService(
                accountRepository = get(),
                mediaBlobRepository = get(),
                backupRepository = get(),
                blobStorage = getOrNull<LogDateBlobStorage>(),
                blobCleanup =
                    if (isDatabaseAvailable) {
                        AccountBlobCleanup(storage = get())
                    } else {
                        null
                    },
                oauthGrantRevoker = get<OAuthAuthorizationService>(),
                statusEvents = getOrNull<AccountStatusEvents>(),
            )
        }

        single<GoogleIdTokenVerifier> {
            val allowedClientIds =
                (System.getenv("GOOGLE_OIDC_CLIENT_IDS") ?: "")
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .toSet()
            HttpGoogleIdTokenVerifier(allowedClientIds = allowedClientIds)
        }

        single<LogDateBlobStorage> {
            GcsMediaStorage.fromEnvironment()
                ?: FilesystemLogDateBlobStorage.fromEnvironment()
                ?: InMemoryLogDateBlobStorage()
        }

        // AT Protocol Runtime Services
        single {
            RepoBackedLogDateCollectionsRepository(
                accountRepository = get(),
                identityService = get(),
                signingKeyService = get(),
                blockStore = get(),
                metadataStore = get(),
            )
        }
        single<JournalMergeStore> {
            if (isDatabaseAvailable) PostgreSQLJournalMergeStore() else InMemoryJournalMergeStore()
        }
        single<LogDateCollectionsRepository> {
            MergeAwareLogDateCollectionsRepository(get<RepoBackedLogDateCollectionsRepository>(), get())
        }
        single<LogDateMomentsRepository> {
            val collections = get<RepoBackedLogDateCollectionsRepository>()
            RepoBackedLogDateMomentsRepository(
                accountRepository = get(),
                signingKeyService = get(),
                blockStore = get(),
                metadataStore = get(),
                repoDidFor = collections::canonicalRepoDid,
                mutations = get(),
            )
        }
        single {
            LogDateRepoStore(
                collectionsRepository = get<LogDateCollectionsRepository>(),
                identityService = get(),
                signingKeyService = get(),
                accountRepository = get(),
                blockStore = get(),
                publicPolicy = getOrNull<PublicRepoPolicy>(),
                publicRecords = getOrNull<PublicRecordValidator>(),
            )
        } bind RepoEngine::class bind RepoRecordStore::class

        single<PdsRepoService> { DefaultPdsRepoService(get()) }
        single<PdsSyncService> { DefaultPdsSyncService(get()) }
        single<PdsBlobService> {
            DefaultPdsBlobService(
                LogDatePdsBlobStore(
                    identityService = get(),
                    mediaBlobRepository = get(),
                    blobStorage = get(),
                ),
            )
        }
        single<PdsDiscoveryService> {
            val oauthConfig: OAuthConfig = get()
            val identityConfig: AtprotoIdentityConfig = get()
            StaticPdsDiscoveryService(
                authorizationServerMetadata = oauthConfig.authorizationServerMetadata(),
                protectedResourceMetadata = oauthConfig.protectedResourceMetadata(),
                describeServerResponse =
                    DescribeServerResponse(
                        did = identityConfig.serverDid,
                        availableUserDomains = listOf(identityConfig.normalizedHandleDomain),
                        inviteCodeRequired = false,
                        phoneVerificationRequired = false,
                    ),
            )
        }
    }
