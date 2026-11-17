package app.logdate.server.database

import app.logdate.server.config.RuntimeProfile
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.configuration.FluentConfiguration
import org.flywaydb.core.api.output.MigrateResult
import org.jetbrains.exposed.v1.jdbc.Database
import java.sql.Connection
import java.sql.SQLException
import java.util.logging.Logger
import javax.sql.DataSource
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit and integration tests for [DatabaseConfig], ensuring robust database connection management.
 *
 * This test suite covers:
 * - Parsing and normalization of various database connection URL formats (postgres, postgresql, jdbc).
 * - Configuration of Hikari connection pools with appropriate driver and performance settings.
 * - Dynamic data source creation for different environments, including Google Cloud SQL.
 * - Credential resolution logic from environment variables and connection strings.
 * - Database migration orchestration using Flyway, including error handling and profile-specific behavior.
 */
class DatabaseConfigTest {
    @AfterTest
    fun cleanupMocks() {
        unmockkAll()
    }

    @Test
    fun `parse and normalize database URLs`() {
        val parsed = invokePrivate("parseDatabaseUrl", "postgres://dbuser:dbpass@localhost:5544/logdate?sslmode=require")
        assertNotNull(parsed)
        assertEquals("jdbc:postgresql://localhost:5544/logdate?sslmode=require", readField(parsed, "jdbcUrl"))
        assertEquals("dbuser", readField(parsed, "username"))
        assertEquals("dbpass", readField(parsed, "password"))
        assertEquals("dbuser", invokeGetter(parsed, "getUsername"))
        assertEquals("dbpass", invokeGetter(parsed, "getPassword"))

        val parsedJdbc = invokePrivate("parseDatabaseUrl", "jdbc:postgresql://localhost:5432/logdate")
        assertNotNull(parsedJdbc)
        assertEquals("jdbc:postgresql://localhost:5432/logdate", readField(parsedJdbc, "jdbcUrl"))

        val parsedInvalid = invokePrivate("parseDatabaseUrl", "not-a-valid-uri")
        assertNull(parsedInvalid)
        val parsedException = invokePrivate("parseDatabaseUrl", "postgres://%zz")
        assertNull(parsedException)

        val normalizedPostgres = invokePrivateString("normalizeJdbcUrl", "postgres://localhost:5432/logdate")
        assertEquals("jdbc:postgresql://localhost:5432/logdate", normalizedPostgres)

        val normalizedPostgresql = invokePrivateString("normalizeJdbcUrl", "postgresql://localhost:5432/logdate")
        assertEquals("jdbc:postgresql://localhost:5432/logdate", normalizedPostgresql)

        val normalizedJdbc = invokePrivateString("normalizeJdbcUrl", "jdbc:postgresql://localhost:5432/logdate")
        assertEquals("jdbc:postgresql://localhost:5432/logdate", normalizedJdbc)

        val normalizedOther = invokePrivateString("normalizeJdbcUrl", "mysql://localhost/logdate")
        assertEquals("mysql://localhost/logdate", normalizedOther)
    }

    @Test
    fun `buildConfig sets expected pool and driver properties`() {
        val method =
            DatabaseConfig::class.java.getDeclaredMethod(
                "buildConfig",
                String::class.java,
                String::class.java,
                String::class.java,
            )
        method.isAccessible = true
        val config = method.invoke(DatabaseConfig, "jdbc:postgresql://localhost:5432/logdate", "user", "pass") as HikariConfig

        assertEquals("jdbc:postgresql://localhost:5432/logdate", config.jdbcUrl)
        assertEquals("user", config.username)
        assertEquals("pass", config.password)
        assertEquals("org.postgresql.Driver", config.driverClassName)
        // No idle floor so serverless Postgres (Neon) can autosuspend when idle, and a pool at
        // least as large as Cloud Run's per-instance concurrency: a smaller one made requests
        // queue for a connection rather than run, which starved the health checks and got the
        // instance killed under a single device's sync.
        assertTrue(config.maximumPoolSize >= DatabaseConfig.CLOUD_RUN_REQUEST_CONCURRENCY)
        assertEquals(0, config.minimumIdle)
        // Shorter than the liveness probe's patience, so a request fails rather than the instance.
        assertTrue(config.connectionTimeout < 30000)
        assertEquals(300000, config.idleTimeout)
        assertEquals(1800000, config.maxLifetime)
        assertTrue(!config.isAutoCommit)
        assertEquals("TRANSACTION_READ_COMMITTED", config.transactionIsolation)
    }

    @Test
    fun `createDataSource creates hikari data sources`() {
        // No env-only invocation is exercised here: DATABASE_USER/DATABASE_PASSWORD are now required
        // and the no-arg overload would depend on the test runner's ambient environment. Each
        // explicit-arg case below covers one branch of credential resolution.

        val createDefault =
            runCatching {
                DatabaseConfig.createDataSource(
                    host = "localhost",
                    port = 5432,
                    database = "logdate",
                    username = "logdate",
                    password = "logdate",
                    databaseUrl = null,
                )
            }
        assertTrue(createDefault.isSuccess)
        (createDefault.getOrNull() as? HikariDataSource)?.close()

        val createFromUrl =
            runCatching {
                DatabaseConfig.createDataSource(
                    username = "explicit",
                    password = "secret",
                    databaseUrl = "postgres://dbuser:dbpass@localhost:5432/logdate",
                )
            }
        assertTrue(createFromUrl.isSuccess)
        (createFromUrl.getOrNull() as? HikariDataSource)?.close()
    }

    @Test
    fun `createDataSource fails fast when no credentials are available`() {
        val failure =
            assertFailsWith<IllegalStateException> {
                DatabaseConfig.createDataSource(
                    host = "db.example.com",
                    port = 5432,
                    database = "logdate",
                    username = null,
                    password = null,
                    databaseUrl = null,
                )
            }
        assertTrue(failure.message!!.contains("DATABASE_USER"))
    }

    @Test
    fun `createDataSource picks credentials out of DATABASE_URL when not passed explicitly`() {
        val source =
            DatabaseConfig.createDataSource(
                username = null,
                password = null,
                databaseUrl = "postgres://embedded:fromurl@localhost:5432/logdate",
            ) as HikariDataSource
        assertEquals("embedded", source.username)
        assertEquals("fromurl", source.password)
        source.close()
    }

    @Test
    fun `Cloud SQL connection name configures the authenticated socket factory`() {
        val source =
            createDataSourceWithRuntimeProfile(
                database = "logdate",
                username = "cloud-user",
                password = "cloud-password",
                databaseUrl = null,
                instanceConnectionName = "logdate-prod:us-central1:logdate-db",
                profile = RuntimeProfile.PRODUCTION,
            )

        assertEquals("jdbc:postgresql:///logdate", source.jdbcUrl)
        assertEquals("cloud-user", source.username)
        assertEquals("cloud-password", source.password)
        assertEquals("com.google.cloud.sql.postgres.SocketFactory", source.dataSourceProperties["socketFactory"])
        assertEquals("logdate-prod:us-central1:logdate-db", source.dataSourceProperties["cloudSqlInstance"])
        assertEquals("PUBLIC,PRIVATE", source.dataSourceProperties["ipTypes"])
        assertEquals("lazy", source.dataSourceProperties["cloudSqlRefreshStrategy"])
        assertTrue(source.maximumPoolSize >= DatabaseConfig.CLOUD_RUN_REQUEST_CONCURRENCY)
        assertEquals(0, source.minimumIdle)
        source.close()
    }

    @Test
    fun `DATABASE_URL takes precedence over Cloud SQL connector configuration`() {
        val source =
            createDataSourceWithRuntimeProfile(
                database = "ignored",
                username = null,
                password = null,
                databaseUrl = "postgres://neon-user:neon-password@db.example.test:5432/neon?sslmode=require",
                instanceConnectionName = "logdate-prod:us-central1:logdate-db",
                profile = RuntimeProfile.PRODUCTION,
            )

        assertEquals("jdbc:postgresql://db.example.test:5432/neon?sslmode=require", source.jdbcUrl)
        assertEquals("neon-user", source.username)
        assertEquals("neon-password", source.password)
        assertNull(source.dataSourceProperties["socketFactory"])
        assertNull(source.dataSourceProperties["cloudSqlInstance"])
        source.close()
    }

    @Test
    fun `host and port fallback is rejected in production`() {
        val failure =
            assertFailsWith<java.lang.reflect.InvocationTargetException> {
                createDataSourceWithRuntimeProfile(
                    database = "logdate",
                    username = "local-user",
                    password = "local-password",
                    databaseUrl = null,
                    instanceConnectionName = null,
                    profile = RuntimeProfile.PRODUCTION,
                )
            }

        assertTrue(failure.cause is IllegalStateException)
        assertTrue(failure.cause!!.message!!.contains("DATABASE_URL or INSTANCE_CONNECTION_NAME"))
    }

    @Test
    fun `host and port fallback remains available in development`() {
        val source =
            createDataSourceWithRuntimeProfile(
                database = "logdate_dev",
                username = "local-user",
                password = "local-password",
                databaseUrl = null,
                instanceConnectionName = null,
                profile = RuntimeProfile.DEVELOPMENT,
            )

        assertEquals("jdbc:postgresql://localhost:6543/logdate_dev", source.jdbcUrl)
        assertNull(source.dataSourceProperties["socketFactory"])
        source.close()
    }

    @Test
    fun `shouldRunMigrations defaults to true in development`() {
        assertTrue(DatabaseConfig.shouldRunMigrations(null, RuntimeProfile.DEVELOPMENT))
        assertTrue(DatabaseConfig.shouldRunMigrations("", RuntimeProfile.DEVELOPMENT))
        assertTrue(DatabaseConfig.shouldRunMigrations(null, RuntimeProfile.TEST))
    }

    @Test
    fun `shouldRunMigrations defaults to false in production so CI owns migrations`() {
        assertTrue(!DatabaseConfig.shouldRunMigrations(null, RuntimeProfile.PRODUCTION))
        assertTrue(!DatabaseConfig.shouldRunMigrations("", RuntimeProfile.PRODUCTION))
    }

    @Test
    fun `shouldRunMigrations respects explicit values in any profile`() {
        assertTrue(DatabaseConfig.shouldRunMigrations("true", RuntimeProfile.PRODUCTION))
        assertTrue(DatabaseConfig.shouldRunMigrations("yes", RuntimeProfile.PRODUCTION))
        assertTrue(DatabaseConfig.shouldRunMigrations("1", RuntimeProfile.PRODUCTION))
        assertTrue(!DatabaseConfig.shouldRunMigrations("false", RuntimeProfile.DEVELOPMENT))
        assertTrue(!DatabaseConfig.shouldRunMigrations("0", RuntimeProfile.DEVELOPMENT))
        assertTrue(!DatabaseConfig.shouldRunMigrations("no", RuntimeProfile.DEVELOPMENT))
    }

    @Test
    fun `initialize database rethrows migration errors from datasource failures`() {
        val dataSource =
            object : DataSource {
                override fun getConnection(): Connection = throw SQLException("expected-test-failure")

                override fun getConnection(
                    username: String?,
                    password: String?,
                ): Connection = throw SQLException("expected-test-failure")

                override fun <T : Any?> unwrap(iface: Class<T>?): T = throw SQLException("unsupported")

                override fun isWrapperFor(iface: Class<*>?): Boolean = false

                override fun getLogWriter() = null

                override fun setLogWriter(out: java.io.PrintWriter?) = Unit

                override fun setLoginTimeout(seconds: Int) = Unit

                override fun getLoginTimeout(): Int = 0

                override fun getParentLogger(): Logger = Logger.getGlobal()
            }

        assertFailsWith<Exception> {
            DatabaseConfig.initializeDatabase(dataSource)
        }
    }

    @Test
    fun `initializeDatabase and runMigrations cover success and failure branches`() {
        val dataSource = mockk<DataSource>(relaxed = true)
        val database = mockk<Database>()

        var migrated = false
        val initialized =
            DatabaseConfig.initializeDatabase(
                dataSource = dataSource,
                migrate = { migrated = true },
                connect = { database },
            )
        assertTrue(migrated)
        assertEquals(database, initialized)

        assertFailsWith<IllegalStateException> {
            DatabaseConfig.initializeDatabase(
                dataSource = dataSource,
                migrate = { throw IllegalStateException("migration-failure") },
                connect = { database },
            )
        }

        var skippedMigration = false
        val initializedWithoutMigrations =
            DatabaseConfig.initializeDatabase(
                dataSource = dataSource,
                autoMigrate = false,
                migrate = { skippedMigration = true },
                connect = { database },
            )
        assertEquals(database, initializedWithoutMigrations)
        assertTrue(!skippedMigration)

        val configuration = mockk<FluentConfiguration>()
        val flyway = mockk<Flyway>()
        val migrateResult = MigrateResult().apply { migrationsExecuted = 1 }

        mockkStatic(Flyway::class)

        every { Flyway.configure() } returns configuration
        every { configuration.dataSource(dataSource) } returns configuration
        every { configuration.locations("classpath:db/migration") } returns configuration
        every { configuration.load() } returns flyway
        every { flyway.migrate() } returns migrateResult

        val runMigrations =
            DatabaseConfig::class.java.getDeclaredMethod(
                "runMigrations",
                DataSource::class.java,
            )
        runMigrations.isAccessible = true
        runMigrations.invoke(DatabaseConfig, dataSource)

        every { flyway.migrate() } throws IllegalStateException("migration-boom")
        val ex =
            assertFailsWith<java.lang.reflect.InvocationTargetException> {
                runMigrations.invoke(DatabaseConfig, dataSource)
            }
        assertTrue(ex.cause is IllegalStateException)
    }

    private fun invokePrivate(
        methodName: String,
        vararg args: Any,
    ): Any? {
        val argTypes = args.map { it::class.java }.toTypedArray()
        val method = DatabaseConfig::class.java.getDeclaredMethod(methodName, *argTypes)
        method.isAccessible = true
        return method.invoke(DatabaseConfig, *args)
    }

    private fun invokePrivateString(
        methodName: String,
        arg: String,
    ): String = invokePrivate(methodName, arg) as String

    private fun createDataSourceWithRuntimeProfile(
        database: String,
        username: String?,
        password: String?,
        databaseUrl: String?,
        instanceConnectionName: String?,
        profile: RuntimeProfile,
    ): HikariDataSource {
        val method =
            DatabaseConfig::class.java.getDeclaredMethod(
                "createDataSource",
                String::class.java,
                Int::class.javaPrimitiveType,
                String::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                RuntimeProfile::class.java,
            )
        return method.invoke(
            DatabaseConfig,
            "localhost",
            6543,
            database,
            username,
            password,
            databaseUrl,
            instanceConnectionName,
            profile,
        ) as HikariDataSource
    }

    private fun readField(
        target: Any,
        fieldName: String,
    ): String? {
        val field = target::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(target) as String?
    }

    private fun invokeGetter(
        target: Any,
        methodName: String,
    ): String? = target::class.java.getDeclaredMethod(methodName).invoke(target) as String?
}
