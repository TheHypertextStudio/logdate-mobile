package app.logdate.server.di

import app.logdate.server.auth.AccountIdentityRepository
import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.GoogleIdTokenVerifier
import app.logdate.server.auth.InMemoryAccountIdentityRepository
import app.logdate.server.auth.InMemoryAccountRepository
import app.logdate.server.auth.InMemorySessionManager
import app.logdate.server.auth.SessionManager
import app.logdate.server.config.RuntimeProfile
import app.logdate.server.database.DatabaseConfig
import app.logdate.server.database.PostgreSQLAccountIdentityRepository
import app.logdate.server.database.PostgreSQLAccountRepository
import app.logdate.server.database.PostgreSQLHostedPlcOperationRepository
import app.logdate.server.database.PostgreSQLLogDateAtprotoBlobRepository
import app.logdate.server.database.PostgreSQLLogDateBackupRepository
import app.logdate.server.database.PostgreSQLLogDateCollectionsMetadataStore
import app.logdate.server.database.PostgreSQLLogDateMediaRepository
import app.logdate.server.database.PostgreSQLPasskeyRepository
import app.logdate.server.database.PostgreSQLRepoBlockStore
import app.logdate.server.database.PostgreSQLSessionManager
import app.logdate.server.identity.AtprotoIdentityConfig
import app.logdate.server.identity.HostedPlcOperationRepository
import app.logdate.server.identity.InMemoryHostedPlcOperationRepository
import app.logdate.server.identity.PlcIdentityService
import app.logdate.server.logdate.InMemoryLogDateAtprotoBlobRepository
import app.logdate.server.logdate.InMemoryLogDateBackupRepository
import app.logdate.server.logdate.InMemoryLogDateCollectionsMetadataStore
import app.logdate.server.logdate.InMemoryLogDateMediaRepository
import app.logdate.server.logdate.LogDateAtprotoBlobRepository
import app.logdate.server.logdate.LogDateBackupRepository
import app.logdate.server.logdate.LogDateCollectionsMetadataStore
import app.logdate.server.logdate.LogDateMediaRepository
import app.logdate.server.passkeys.InMemoryPasskeyRepository
import app.logdate.server.passkeys.PasskeyRepository
import app.logdate.server.passkeys.WebAuthnPasskeyService
import app.logdate.server.sync.DbSyncRepository
import app.logdate.server.sync.InMemorySyncRepository
import app.logdate.server.sync.SyncRepository
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.pluginOrNull
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.koin.core.context.stopKoin
import org.koin.core.logger.EmptyLogger
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import studio.hypertext.atproto.repo.InMemoryRepoBlockStore
import studio.hypertext.atproto.repo.RepoBlockStore
import javax.sql.DataSource
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for the Koin dependency injection configuration in [ServerModule].
 *
 * This suite ensures the structural integrity of the server's dependency graph by:
 * - Verifying the correct wiring of services and repositories for both development
 *   (in-memory) and production (PostgreSQL) environments.
 * - Testing the graceful degradation and fallback logic when the database is unavailable.
 * - Validating the successful initialization of the database schema and shared
 *   cryptographic services.
 * - Ensuring that all required components for AT Protocol identity and OAuth
 *   flows are correctly provided and injectable.
 */
class ServerModuleTest {
    @AfterTest
    fun cleanupKoin() {
        unmockkAll()
        stopKoin()
    }

    @Test
    fun `initialize database refuses to start when the database is unavailable`() {
        mockkObject(DatabaseConfig)
        every { DatabaseConfig.createDataSource() } throws
            IllegalStateException("Database credential missing: set DATABASE_USER")

        val error = assertFailsWith<IllegalStateException> { initializeDatabase(readEnv = { null }) }

        assertTrue(
            error.message.orEmpty().contains(ALLOW_INMEMORY_FALLBACK_ENV),
            "The failure should name the opt-in that would allow an in-memory run",
        )
    }

    @Test
    fun `initialize database falls back to in memory only when explicitly allowed`() {
        mockkObject(DatabaseConfig)
        every { DatabaseConfig.createDataSource() } throws IllegalStateException("no database here")

        val allowFallback = { name: String -> "true".takeIf { name == ALLOW_INMEMORY_FALLBACK_ENV } }

        assertFalse(initializeDatabase(readEnv = allowFallback))
    }

    @Test
    fun `initialize database in production refuses even when the fallback is allowed`() {
        mockkObject(DatabaseConfig)
        every { DatabaseConfig.createDataSource() } throws IllegalStateException("no database here")

        val productionAllowingFallback = { name: String ->
            when (name) {
                RuntimeProfile.ENV_VAR -> "production"
                ALLOW_INMEMORY_FALLBACK_ENV -> "true"
                else -> null
            }
        }

        assertFailsWith<IllegalStateException> {
            initializeDatabase(readEnv = productionAllowingFallback)
        }
    }

    @Test
    fun `the shared outbound http client times out`() {
        val koin =
            koinApplication {
                logger(EmptyLogger())
                modules(serverModule(isDatabaseAvailable = false))
            }.koin

        assertNotNull(koin.get<HttpClient>().pluginOrNull(HttpTimeout))
    }

    @Test
    fun `server module uses in-memory implementations when database is unavailable`() {
        val koin =
            koinApplication {
                logger(EmptyLogger())
                modules(serverModule(isDatabaseAvailable = false))
            }.koin

        assertIs<InMemoryAccountRepository>(koin.get<AccountRepository>())
        assertIs<InMemoryAccountIdentityRepository>(koin.get<AccountIdentityRepository>())
        assertIs<InMemoryPasskeyRepository>(koin.get<PasskeyRepository>())
        assertIs<InMemorySessionManager>(koin.get<SessionManager>())
        assertIs<InMemorySyncRepository>(koin.get<SyncRepository>())
        assertIs<InMemoryRepoBlockStore>(koin.get<RepoBlockStore>())
        assertIs<InMemoryLogDateCollectionsMetadataStore>(koin.get<LogDateCollectionsMetadataStore>())
        assertIs<InMemoryLogDateMediaRepository>(koin.get<LogDateMediaRepository>())
        assertIs<InMemoryLogDateBackupRepository>(koin.get<LogDateBackupRepository>())
        assertIs<InMemoryLogDateAtprotoBlobRepository>(koin.get<LogDateAtprotoBlobRepository>())
        assertIs<InMemoryHostedPlcOperationRepository>(koin.get<HostedPlcOperationRepository>())
        assertIs<WebAuthnPasskeyService>(koin.get<WebAuthnPasskeyService>())
        assertIs<GoogleIdTokenVerifier>(koin.get<GoogleIdTokenVerifier>())
        assertTrue(koin.get<WebAuthnPasskeyService>().relyingPartyId.isNotBlank())
    }

    @Test
    fun `server module wires database repositories when database flag is enabled`() {
        val koin =
            koinApplication {
                logger(EmptyLogger())
                modules(serverModule(isDatabaseAvailable = true))
            }.koin

        assertIs<PostgreSQLAccountRepository>(koin.get<AccountRepository>())
        assertIs<PostgreSQLAccountIdentityRepository>(koin.get<AccountIdentityRepository>())
        assertIs<PostgreSQLPasskeyRepository>(koin.get<PasskeyRepository>())
        assertIs<PostgreSQLSessionManager>(koin.get<SessionManager>())
        assertIs<DbSyncRepository>(koin.get<SyncRepository>())
        assertIs<PostgreSQLRepoBlockStore>(koin.get<RepoBlockStore>())
        assertIs<PostgreSQLLogDateCollectionsMetadataStore>(koin.get<LogDateCollectionsMetadataStore>())
        assertIs<PostgreSQLLogDateMediaRepository>(koin.get<LogDateMediaRepository>())
        assertIs<PostgreSQLLogDateBackupRepository>(koin.get<LogDateBackupRepository>())
        assertIs<PostgreSQLLogDateAtprotoBlobRepository>(koin.get<LogDateAtprotoBlobRepository>())
        assertIs<PostgreSQLHostedPlcOperationRepository>(koin.get<HostedPlcOperationRepository>())
        assertIs<WebAuthnPasskeyService>(koin.get<WebAuthnPasskeyService>())
    }

    @Test
    fun `initializeDatabase returns true when datasource and schema creation succeed`() {
        val dataSource = mockk<DataSource>()
        val database = mockk<Database>()

        mockkObject(DatabaseConfig)

        every { DatabaseConfig.createDataSource() } returns dataSource
        every { DatabaseConfig.shouldRunMigrations() } returns true
        every { DatabaseConfig.initializeDatabase(dataSource, true, any(), any()) } returns database

        Database.connect(
            url = "jdbc:h2:mem:servermodule_test;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )

        assertTrue(initializeDatabase())
        assertTrue(TransactionManager.defaultDatabase === database)
    }

    @Test
    fun `server module builds plc publishing client when enabled`() {
        val koin =
            koinApplication {
                logger(EmptyLogger())
                allowOverride(true)
                modules(
                    serverModule(isDatabaseAvailable = false),
                    module {
                        single {
                            AtprotoIdentityConfig(
                                handleDomain = "logdate.app",
                                pdsServiceEndpoint = "https://logdate.app",
                                publishHostedPlcOperations = true,
                                plcDirectoryUrl = "https://plc.example.com/",
                            )
                        }
                    },
                )
            }.koin

        assertIs<PlcIdentityService>(koin.get<PlcIdentityService>())
    }
}
