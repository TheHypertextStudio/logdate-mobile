package app.logdate.client.data.di

import app.logdate.client.data.account.DefaultPasskeyAccountRepository
import app.logdate.client.data.account.DefaultPasskeyAccountRepositoryTest
import app.logdate.client.data.account.RenewalStorage
import app.logdate.client.data.account.UnavailablePasskeyAccountRepository
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import app.logdate.client.device.identity.CanonicalOwnerProvider
import app.logdate.client.device.storage.SecureStorage
import app.logdate.client.permissions.PasskeyManager
import app.logdate.client.repository.account.BlueskySignInRepository
import app.logdate.client.repository.account.BlueskySignInState
import app.logdate.client.repository.account.PasskeyAccountRepository
import app.logdate.shared.config.LogDateConfigRepository
import app.logdate.shared.model.BlueskyBrowserReturn
import app.logdate.shared.model.DeploymentKind
import app.logdate.shared.model.ServerCapability
import app.logdate.shared.model.ServerDescriptor
import app.logdate.shared.model.ServerPasskeyConfig
import app.logdate.shared.model.ServerProtocolFeature
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BlueskySignInModuleTest {
    @Test
    fun `unsupported platform stays unavailable without resolving secure storage or transport`() =
        runTest {
            val app =
                koinApplication {
                    modules(
                        blueskySignInModule,
                        module {
                            single<PasskeyAccountRepository> { UnavailablePasskeyAccountRepository() }
                        },
                    )
                }
            try {
                val repository = app.koin.get<BlueskySignInRepository>()
                assertSame(repository, app.koin.get<BlueskySignInRepository>())
                assertFalse(repository.isAvailable())
                assertTrue(repository.start("alice.test").isFailure)
            } finally {
                app.close()
            }
        }

    @Test
    fun `one shared flow uses the active flag server and injected transport`() =
        runTest {
            val config = DefaultPasskeyAccountRepositoryTest.FakeConfigRepository()
            val origin = config.getCurrentBackendUrl()
            config.updateServerDescriptor(
                ServerDescriptor(
                    serverOrigin = origin,
                    apiBaseUrl = "$origin/api/v1",
                    deploymentKind = DeploymentKind.FIRST_PARTY,
                    displayName = "LogDate",
                    passkey = ServerPasskeyConfig("logdate.app", "LogDate"),
                    capabilities = listOf(ServerCapability.AUTH_PASSKEY),
                    protocolFeatures =
                        listOf(
                            ServerProtocolFeature.BLUESKY_SIGN_IN_V1,
                            ServerProtocolFeature.ENCRYPTED_ACCOUNT_KEYS_V1,
                            ServerProtocolFeature.PERSONAL_SPACE_V1,
                        ),
                ),
            )
            val flags = MutableStateFlow(false)
            var passkeySupported = true
            val delegateManager = DefaultPasskeyAccountRepositoryTest.FakePasskeyManager()
            val manager =
                object : PasskeyManager by delegateManager {
                    override suspend fun getCapabilities() = delegateManager.getCapabilities().copy(isSupported = passkeySupported)
                }
            val passkeys =
                DefaultPasskeyAccountRepository(
                    DefaultPasskeyAccountRepositoryTest().FakePasskeyApiClient(),
                    manager,
                    DefaultPasskeyAccountRepositoryTest.FakeRestoreCredentialManager(),
                    DefaultPasskeyAccountRepositoryTest.FakeSessionStorage(),
                    DefaultPasskeyAccountRepositoryTest.FakePlatformAccountManager(),
                    config,
                    object : CanonicalOwnerProvider {
                        override suspend fun getCanonicalOwnerId() = "745d7803-013a-4bea-9605-99ac3b27dbe7"

                        override suspend fun hasBoundOwner() = false
                    },
                    { false },
                    repositoryScope = backgroundScope,
                )
            var requests = 0
            val client =
                HttpClient(
                    MockEngine { request ->
                        requests++
                        assertEquals(origin, "${request.url.protocol.name}://${request.url.host}")
                        val body =
                            if (request.url.encodedPath.endsWith("/start")) {
                                """{"attemptId":"${"a".repeat(43)}","authorizationUrl":"https://bsky.example/authorize"}"""
                            } else {
                                """{"continuationToken":"${"t".repeat(43)}","nextStep":"create_account"}"""
                            }
                        respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    },
                )
            val app =
                koinApplication {
                    modules(
                        blueskySignInModule,
                        module {
                            single<PasskeyAccountRepository> { passkeys }
                            single<SecureStorage> { RenewalStorage() }
                            single<LogDateConfigRepository> { config }
                            single { client }
                            single<FeatureFlagStore> {
                                object : FeatureFlagStore {
                                    override fun observe(flag: FeatureFlag): kotlinx.coroutines.flow.Flow<Boolean> {
                                        assertEquals(FeatureFlag.BLUESKY_SIGN_IN, flag)
                                        return flags
                                    }

                                    override suspend fun setEnabled(
                                        flag: FeatureFlag,
                                        enabled: Boolean,
                                    ) {
                                        flags.value = enabled
                                    }
                                }
                            }
                        },
                    )
                }
            try {
                val flow = app.koin.get<BlueskySignInRepository>()
                assertSame(flow, app.koin.get<BlueskySignInRepository>())
                assertFalse(flow.isAvailable())
                assertTrue(flow.start("alice.test").isFailure)
                assertEquals(0, requests)
                flags.value = true
                assertTrue(flow.isAvailable())
                assertEquals("https://bsky.example/authorize", flow.start("alice.test").getOrThrow())
                assertTrue(app.koin.get<BlueskySignInRepository>().resume("${BlueskyBrowserReturn.URL}?attempt=${"a".repeat(43)}"))
                assertIs<BlueskySignInState.AccountDetails>(flow.state.value)
                assertEquals(2, requests)
                passkeySupported = false
                assertFalse(flow.isAvailable())
                assertEquals(2, requests)
                config.updateBackendUrl("https://other.logdate.app")
                assertFalse(flow.isAvailable())
            } finally {
                app.close()
                client.close()
            }
        }
}
