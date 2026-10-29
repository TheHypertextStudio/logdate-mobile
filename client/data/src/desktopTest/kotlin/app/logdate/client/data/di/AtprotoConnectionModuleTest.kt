package app.logdate.client.data.di

import app.logdate.client.data.account.DefaultPasskeyAccountRepository
import app.logdate.client.data.account.DefaultPasskeyAccountRepositoryTest
import app.logdate.client.data.account.RenewalStorage
import app.logdate.client.datastore.SessionStorage
import app.logdate.client.datastore.UserSession
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import app.logdate.client.device.identity.CanonicalOwnerProvider
import app.logdate.client.device.storage.SecureSessionStorage
import app.logdate.client.device.storage.SecureStorage
import app.logdate.client.permissions.PasskeyManager
import app.logdate.client.repository.account.AtprotoConnectionRepository
import app.logdate.client.repository.account.PasskeyAccountRepository
import app.logdate.shared.config.LogDateConfigRepository
import app.logdate.shared.model.DeploymentKind
import app.logdate.shared.model.ServerCapability
import app.logdate.shared.model.ServerDescriptor
import app.logdate.shared.model.ServerPasskeyConfig
import app.logdate.shared.model.ServerProtocolFeature
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AtprotoConnectionModuleTest {
    @Test
    fun `Android composition requires its own disabled by default flag and explicit compatible server capability`() =
        runTest {
            assertFalse(FeatureFlag.ATPROTO_CONNECTION.defaultEnabled)
            val config = DefaultPasskeyAccountRepositoryTest.FakeConfigRepository()
            val origin = config.getCurrentBackendUrl()
            val descriptor =
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
                            ServerProtocolFeature.PERSONAL_SPACE_V1,
                            ServerProtocolFeature.ENCRYPTED_ACCOUNT_KEYS_V1,
                        ),
                )
            config.updateServerDescriptor(descriptor)
            val storage = RenewalStorage()
            val sessions = SecureSessionStorage(storage, config, backgroundScope)
            sessions.saveExplicitSession(UserSession("account-token", "private-refresh", OWNER))
            val manager = DefaultPasskeyAccountRepositoryTest.FakePasskeyManager()
            val accounts =
                DefaultPasskeyAccountRepository(
                    DefaultPasskeyAccountRepositoryTest().FakePasskeyApiClient(),
                    manager,
                    DefaultPasskeyAccountRepositoryTest.FakeRestoreCredentialManager(),
                    sessions,
                    DefaultPasskeyAccountRepositoryTest.FakePlatformAccountManager(),
                    config,
                    object : CanonicalOwnerProvider {
                        override suspend fun getCanonicalOwnerId() = OWNER

                        override suspend fun hasBoundOwner() = true
                    },
                    { false },
                    repositoryScope = backgroundScope,
                )
            val enabled = MutableStateFlow(false)
            var requests = 0
            val transport =
                HttpClient(
                    MockEngine { request ->
                        requests += 1
                        assertEquals("$origin/api/v1/auth/atproto/connection", request.url.toString())
                        respond(
                            """{"state":"connected","revision":1,"observedAt":"2026-10-27T00:00:00Z","eligible":true,"canDisconnect":true}""",
                        )
                    },
                )
            val app =
                koinApplication {
                    modules(
                        atprotoConnectionModule,
                        module {
                            single<PasskeyAccountRepository> { accounts }
                            single<SessionStorage> { sessions }
                            single<SecureStorage> { storage }
                            single<LogDateConfigRepository> { config }
                            single<PasskeyManager> { manager }
                            single { transport }
                            single<FeatureFlagStore> {
                                object : FeatureFlagStore {
                                    override fun observe(flag: FeatureFlag): kotlinx.coroutines.flow.Flow<Boolean> {
                                        assertEquals(FeatureFlag.ATPROTO_CONNECTION, flag)
                                        return enabled
                                    }

                                    override suspend fun setEnabled(
                                        flag: FeatureFlag,
                                        enabled: Boolean,
                                    ) = Unit
                                }
                            }
                        },
                    )
                }
            try {
                val connection = app.koin.get<AtprotoConnectionRepository>()
                assertSame(connection, app.koin.get<AtprotoConnectionRepository>())
                connection.refresh()
                assertEquals(0, requests)
                enabled.value = true
                connection.refresh()
                assertEquals(0, requests)
                config.updateServerDescriptor(
                    descriptor.copy(
                        protocolFeatures =
                            descriptor.protocolFeatures + ServerProtocolFeature.ATPROTO_CONNECTION_V1,
                    ),
                )
                connection.refresh()
                assertEquals(1, requests)
                assertTrue(connection.state.value.available)
                enabled.value = false
                connection.refresh()
                assertFalse(connection.state.value.available)
            } finally {
                app.close()
                transport.close()
            }
        }

    private companion object {
        const val OWNER = "00000000-0000-4000-8000-000000000001"
    }
}
