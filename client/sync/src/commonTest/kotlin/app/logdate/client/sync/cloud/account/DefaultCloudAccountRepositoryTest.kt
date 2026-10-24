package app.logdate.client.sync.cloud.account

import app.logdate.client.sync.InMemoryKeyValueStorage
import app.logdate.client.sync.cloud.CloudApiClient
import app.logdate.client.sync.cloud.CloudApiException
import app.logdate.client.sync.cloud.RefreshedSession
import app.logdate.shared.config.DefaultLogDateConfigRepository
import app.logdate.shared.model.AccountStorageMode
import app.logdate.shared.model.CompleteAccountCreationResponse
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Tests for [DefaultCloudAccountRepository].
 *
 * Verifies that the repository correctly manages cloud account information,
 * including completion of account creation, storage of decentralized identifiers (DIDs),
 * and handling of sign-out operations.
 */
class DefaultCloudAccountRepositoryTest {
    @Test
    fun `production account module follows the selected server configuration`() =
        runTest {
            val config = DefaultLogDateConfigRepository(initialBackendUrl = "https://first.invalid")
            val app =
                org.koin.dsl.koinApplication {
                    modules(
                        org.koin.dsl.module {
                            single<app.logdate.client.datastore.KeyValueStorage> { InMemoryKeyValueStorage() }
                            single<app.logdate.client.datastore.SessionStorage> {
                                app.logdate.client.sync.test
                                    .FakeSessionStorage(false)
                            }
                            single<app.logdate.shared.config.LogDateConfigRepository> { config }
                            single<CloudApiClient> { FakeCloudApiClient() }
                        },
                        app.logdate.client.sync.cloud.di.cloudAccountModule,
                    )
                }
            try {
                val account =
                    app.koin.get<app.logdate.shared.model.CloudAccountRepository>()
                        as app.logdate.client.sync.cloud.CloudRequestLocationProvider
                assertEquals("https://first.invalid", account.captureLocation().origin)
                config.updateBackendUrl("https://second.invalid")
                assertEquals("https://second.invalid", account.captureLocation().origin)
                assertEquals("https://second.invalid/api/v1", account.captureLocation().apiBaseUrl)
            } finally {
                app.close()
            }
        }

    @Test
    fun `complete account creation stores did and handle for later load`() =
        runTest {
            val storage = InMemoryKeyValueStorage()
            val configRepository = DefaultLogDateConfigRepository(initialBackendUrl = "https://test.logdate.app")
            val repository =
                DefaultCloudAccountRepository(
                    apiClient =
                        FakeCloudApiClient(
                            completeAccountCreationResult =
                                CompleteAccountCreationResponse(
                                    success = true,
                                    data =
                                        app.logdate.shared.model.CompleteAccountCreationData(
                                            account =
                                                app.logdate.shared.model.LogDateAccount(
                                                    id = Uuid.parse("550e8400-e29b-41d4-a716-446655440000"),
                                                    username = "tester",
                                                    displayName = "Tester",
                                                    did = "did:web:tester.logdate.app",
                                                    handle = "tester.logdate.app",
                                                    bio = "Polished bio",
                                                    originalBio = "My own words",
                                                    passkeyCredentialIds = listOf("cred-1"),
                                                    createdAt = Instant.parse("2026-03-05T00:00:00Z"),
                                                    updatedAt = Instant.parse("2026-03-05T00:00:00Z"),
                                                ),
                                            tokens =
                                                app.logdate.shared.model.AccountTokens(
                                                    accessToken = "access",
                                                    refreshToken = "refresh",
                                                ),
                                        ),
                                ),
                        ),
                    accountMetadata = storage,
                    configRepository = configRepository,
                    sessionStorage = accountSessions(configRepository),
                    coroutineScope = TestScope(StandardTestDispatcher(testScheduler)),
                )

            repository.completeAccountCreation(
                sessionToken = "session",
                credentialId = "cred-1",
                clientDataJSON = "client",
                attestationObject = "attestation",
            )

            assertEquals(
                "did:web:tester.logdate.app",
                storage.getString(scopedKey("cloud_account_did", configRepository.getCurrentBackendUrl())),
            )
            assertEquals(
                "tester.logdate.app",
                storage.getString(scopedKey("cloud_account_handle", configRepository.getCurrentBackendUrl())),
            )

            val reloaded =
                DefaultCloudAccountRepository(
                    apiClient = FakeCloudApiClient(),
                    accountMetadata = storage,
                    configRepository = configRepository,
                    sessionStorage = accountSessions(configRepository),
                    coroutineScope = TestScope(StandardTestDispatcher(testScheduler)),
                )
            testScheduler.advanceUntilIdle()

            val current = reloaded.getCurrentAccount()
            assertEquals("did:web:tester.logdate.app", current?.did)
            assertEquals("tester.logdate.app", current?.handle)
            assertEquals("Polished bio", current?.bio)
            assertEquals("My own words", current?.originalBio)
        }

    @Test
    fun `the account's storage mode is stored for its server and loaded again`() =
        runTest {
            val storage = InMemoryKeyValueStorage()
            val configRepository = DefaultLogDateConfigRepository(initialBackendUrl = "https://test.logdate.app")
            val repository = repository(storage, configRepository, createdAccount(AccountStorageMode.PERSONAL_SPACE_V1))

            repository.completeAccountCreation("session", "cred-1", "client", "attestation")

            assertEquals(AccountStorageMode.PERSONAL_SPACE_V1, repository.getCurrentAccount()?.storageMode)
            assertEquals("personalSpaceV1", storage.getString(scopedKey("cloud_account_storage_mode", "https://test.logdate.app")))
            assertNull(storage.getString(scopedKey("cloud_account_storage_mode", "https://other.logdate.app")))
            val reloaded = repository(storage, configRepository)
            testScheduler.advanceUntilIdle()
            assertEquals(AccountStorageMode.PERSONAL_SPACE_V1, reloaded.getCurrentAccount()?.storageMode)

            reloaded.signOut()
            assertNull(storage.getString(scopedKey("cloud_account_storage_mode", "https://test.logdate.app")))
        }

    @Test
    fun `an account stored before storage modes has an unknown mode and is never legacy`() =
        runTest {
            val storage = InMemoryKeyValueStorage()
            val configRepository = DefaultLogDateConfigRepository(initialBackendUrl = "https://test.logdate.app")
            repository(storage, configRepository, createdAccount(AccountStorageMode.PERSONAL_SPACE_V1))
                .completeAccountCreation("session", "cred-1", "client", "attestation")
            storage.remove(scopedKey("cloud_account_storage_mode", "https://test.logdate.app"))

            val reloaded = repository(storage, configRepository)
            testScheduler.advanceUntilIdle()

            assertEquals("tester", reloaded.getCurrentAccount()?.username)
            assertEquals(AccountStorageMode.UNRECOGNIZED, reloaded.getCurrentAccount()?.storageMode)
        }

    @Test
    fun `protected session loading after metadata construction restores offline account context`() =
        runTest {
            val metadata = InMemoryKeyValueStorage()
            val config = DefaultLogDateConfigRepository(initialBackendUrl = "https://test.logdate.app")
            repository(metadata, config, createdAccount(AccountStorageMode.PERSONAL_SPACE_V1))
                .completeAccountCreation("session", "cred-1", "client", "attestation")
            val sessions =
                app.logdate.client.sync.test
                    .FakeSessionStorage(false)
                    .apply { currentOrigin = config.getCurrentBackendUrl() }
            val account = DefaultCloudAccountRepository(FakeCloudApiClient(), metadata, config, backgroundScope, sessions)
            testScheduler.runCurrent()
            assertNull(account.getCurrentAccount())

            sessions.saveSession(
                app.logdate.client.datastore
                    .UserSession("vault-access", "vault-refresh", "550e8400-e29b-41d4-a716-446655440000"),
            )
            testScheduler.runCurrent()
            assertEquals("tester", account.getCurrentAccount()?.username)

            sessions.clearSession()
            testScheduler.runCurrent()
            assertNull(account.getCurrentAccount())
        }

    @Test
    fun `copied preference tokens cannot authenticate retained account metadata`() =
        runTest {
            val metadata = InMemoryKeyValueStorage()
            val config = DefaultLogDateConfigRepository(initialBackendUrl = "https://test.logdate.app")
            repository(metadata, config, createdAccount(AccountStorageMode.PERSONAL_SPACE_V1))
                .completeAccountCreation("session", "cred-1", "client", "attestation")
            metadata.putString(scopedKey("cloud_access_token", config.getCurrentBackendUrl()), "copied-access")
            val sessions =
                app.logdate.client.sync.test
                    .FakeSessionStorage(false)
            val account = DefaultCloudAccountRepository(FakeCloudApiClient(), metadata, config, backgroundScope, sessions)
            testScheduler.runCurrent()
            assertNull(account.getCurrentAccount())
        }

    private suspend fun kotlinx.coroutines.test.TestScope.repository(
        storage: InMemoryKeyValueStorage,
        configRepository: DefaultLogDateConfigRepository,
        created: CompleteAccountCreationResponse? = null,
    ) = DefaultCloudAccountRepository(
        apiClient = FakeCloudApiClient(completeAccountCreationResult = created),
        accountMetadata = storage,
        configRepository = configRepository,
        sessionStorage = accountSessions(configRepository),
        coroutineScope = TestScope(StandardTestDispatcher(testScheduler)),
    )

    private suspend fun accountSessions(config: DefaultLogDateConfigRepository) =
        app.logdate.client.sync.test.FakeSessionStorage().apply {
            currentOrigin = config.getCurrentBackendUrl()
            saveSession(
                app.logdate.client.datastore
                    .UserSession("vault-access", "vault-refresh", "550e8400-e29b-41d4-a716-446655440000"),
            )
        }

    private fun createdAccount(storageMode: AccountStorageMode) =
        CompleteAccountCreationResponse(
            success = true,
            data =
                app.logdate.shared.model.CompleteAccountCreationData(
                    account =
                        app.logdate.shared.model.LogDateAccount(
                            id = Uuid.parse("550e8400-e29b-41d4-a716-446655440000"),
                            username = "tester",
                            displayName = "Tester",
                            did = "did:plc:tester",
                            createdAt = Instant.parse("2026-03-05T00:00:00Z"),
                            updatedAt = Instant.parse("2026-03-05T00:00:00Z"),
                            storageMode = storageMode,
                        ),
                    tokens =
                        app.logdate.shared.model
                            .AccountTokens(accessToken = "access", refreshToken = "refresh"),
                ),
        )

    @Test
    fun `sign out clears persisted did and handle`() =
        runTest {
            val storage = InMemoryKeyValueStorage()
            val configRepository = DefaultLogDateConfigRepository(initialBackendUrl = "https://test.logdate.app")
            storage.putString(
                scopedKey("cloud_account_did", configRepository.getCurrentBackendUrl()),
                "did:web:tester.logdate.app",
            )
            storage.putString(
                scopedKey("cloud_account_handle", configRepository.getCurrentBackendUrl()),
                "tester.logdate.app",
            )

            val repository =
                DefaultCloudAccountRepository(
                    apiClient = FakeCloudApiClient(),
                    accountMetadata = storage,
                    configRepository = configRepository,
                    sessionStorage = accountSessions(configRepository),
                    coroutineScope = TestScope(StandardTestDispatcher(testScheduler)),
                )

            repository.signOut()

            assertNull(storage.getString(scopedKey("cloud_account_did", configRepository.getCurrentBackendUrl())))
            assertNull(storage.getString(scopedKey("cloud_account_handle", configRepository.getCurrentBackendUrl())))
        }

    @Test
    fun `rotated credentials are returned without being copied into account preferences`() =
        runTest {
            val storage = InMemoryKeyValueStorage()
            val config = DefaultLogDateConfigRepository(initialBackendUrl = "https://test.logdate.app")
            val repository =
                DefaultCloudAccountRepository(
                    apiClient = FakeCloudApiClient(refreshSessionResult = Result.success(RefreshedSession("access-2", "refresh-2"))),
                    accountMetadata = storage,
                    configRepository = config,
                    sessionStorage = accountSessions(config),
                    coroutineScope = TestScope(StandardTestDispatcher(testScheduler)),
                )

            assertEquals(RefreshedSession("access-2", "refresh-2"), repository.refreshSession("refresh-1").getOrThrow())
            assertNull(storage.getString(scopedKey("cloud_access_token", config.getCurrentBackendUrl())))
            assertNull(storage.getString(scopedKey("cloud_refresh_token", config.getCurrentBackendUrl())))
        }

    @Test
    fun `a server without rotation still refreshes the access token`() =
        runTest {
            val storage = InMemoryKeyValueStorage()
            val config = DefaultLogDateConfigRepository(initialBackendUrl = "https://test.logdate.app")
            val repository =
                DefaultCloudAccountRepository(
                    apiClient = FakeCloudApiClient(),
                    accountMetadata = storage,
                    configRepository = config,
                    sessionStorage = accountSessions(config),
                    coroutineScope = TestScope(StandardTestDispatcher(testScheduler)),
                )

            assertEquals(RefreshedSession("access", null), repository.refreshSession("refresh-1").getOrThrow())
            assertNull(storage.getString(scopedKey("cloud_access_token", config.getCurrentBackendUrl())))
            assertNull(storage.getString(scopedKey("cloud_refresh_token", config.getCurrentBackendUrl())))
        }

    @Test
    fun `a refused session refresh stores nothing and says why`() =
        runTest {
            val storage = InMemoryKeyValueStorage()
            val config = DefaultLogDateConfigRepository(initialBackendUrl = "https://test.logdate.app")
            val revoked = CloudApiException("DEVICE_SESSION_REVOKED", "revoked", statusCode = 401)
            val repository =
                DefaultCloudAccountRepository(
                    apiClient = FakeCloudApiClient(refreshSessionResult = Result.failure(revoked)),
                    accountMetadata = storage,
                    configRepository = config,
                    sessionStorage = accountSessions(config),
                    coroutineScope = TestScope(StandardTestDispatcher(testScheduler)),
                )

            assertEquals(revoked, repository.refreshSession("refresh-1").exceptionOrNull())
            assertNull(storage.getString(scopedKey("cloud_access_token", config.getCurrentBackendUrl())))
            assertNull(storage.getString(scopedKey("cloud_refresh_token", config.getCurrentBackendUrl())))
        }

    @Test
    fun `changing backend clears active identity instead of reviving another server account`() =
        runTest {
            val storage = InMemoryKeyValueStorage()
            val configRepository = DefaultLogDateConfigRepository(initialBackendUrl = "https://first.logdate.app")
            storage.putString(
                scopedKey("cloud_access_token", configRepository.getCurrentBackendUrl()),
                "first-access",
            )
            storage.putString(
                scopedKey("cloud_account_id", configRepository.getCurrentBackendUrl()),
                "550e8400-e29b-41d4-a716-446655440000",
            )
            storage.putString(
                scopedKey("cloud_username", configRepository.getCurrentBackendUrl()),
                "first-user",
            )
            storage.putString(
                scopedKey("cloud_display_name", configRepository.getCurrentBackendUrl()),
                "First User",
            )
            storage.putString(
                scopedKey("cloud_user_id", configRepository.getCurrentBackendUrl()),
                "550e8400-e29b-41d4-a716-446655440000",
            )
            storage.putString(
                scopedKey("cloud_created_at", configRepository.getCurrentBackendUrl()),
                "2026-03-05T00:00:00Z",
            )
            storage.putString(
                scopedKey("cloud_updated_at", configRepository.getCurrentBackendUrl()),
                "2026-03-05T00:00:00Z",
            )

            // Recreate the repository so its initial load sees the persisted first identity.
            val reloadedRepository =
                DefaultCloudAccountRepository(
                    apiClient = FakeCloudApiClient(),
                    accountMetadata = storage,
                    configRepository = configRepository,
                    sessionStorage = accountSessions(configRepository),
                    coroutineScope = TestScope(StandardTestDispatcher(testScheduler)),
                )
            testScheduler.advanceUntilIdle()
            assertEquals("first-user", reloadedRepository.getCurrentAccount()?.username)

            // Even if another origin has credentials left by an older build, selecting it must
            // not implicitly switch identities. Explicit authentication is required instead.
            storage.putString(
                scopedKey("cloud_access_token", "https://second.logdate.app"),
                "second-access",
            )
            configRepository.updateBackendUrl("https://second.logdate.app")
            testScheduler.advanceUntilIdle()

            assertNull(reloadedRepository.getCurrentAccount())
        }
}

private fun scopedKey(
    baseKey: String,
    backendUrl: String,
): String = "${baseKey}_${backendUrl.trim().removePrefix("https://").removePrefix("http://").replace(Regex("[^A-Za-z0-9]"), "_")}"

/*
 * A fake [CloudApiClient] for testing account repository operations.
 */
