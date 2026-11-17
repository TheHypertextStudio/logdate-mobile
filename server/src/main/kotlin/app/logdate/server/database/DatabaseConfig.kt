package app.logdate.server.database

import app.logdate.server.config.RuntimeProfile
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.aakira.napier.Napier
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import javax.sql.DataSource

object DatabaseConfig {
    internal var initializedDataSource: DataSource? = null
        private set

    /**
     * Requests Cloud Run sends to one instance at a time.
     *
     * Mirrors `request_concurrency` in the Terraform tfvars. The pool is sized from this, so if
     * the two drift apart the instance starves itself; keep them in step.
     */
    internal const val CLOUD_RUN_REQUEST_CONCURRENCY = 16

    /**
     * Whether the server should run Flyway migrations on boot.
     *
     * Profile-aware: development and test boot with migrations enabled so a local iteration cycle
     * picks up schema changes without extra wiring. Production defaults to **off** because in a
     * real deployment migrations should run as a distinct CI step before the new container goes
     * live — conflating them with boot means every container restart could mutate the schema,
     * and during a rolling deploy two versions of the app fight over the migration lock.
     *
     * Override explicitly with `AUTO_MIGRATE=true|false` in either direction.
     */
    fun shouldRunMigrations(
        autoMigrate: String? = System.getenv("AUTO_MIGRATE"),
        profile: RuntimeProfile = RuntimeProfile.fromEnvironment(),
    ): Boolean =
        when (autoMigrate?.trim()?.lowercase()) {
            "false", "0", "no" -> false
            "true", "1", "yes" -> true
            null, "" -> !profile.isProduction
            else -> !profile.isProduction
        }

    fun createDataSource(
        host: String = System.getenv("DB_HOST") ?: "localhost",
        port: Int = System.getenv("DB_PORT")?.toIntOrNull() ?: 5432,
        database: String = System.getenv("DB_NAME") ?: "logdate",
        username: String? = System.getenv("DATABASE_USER") ?: System.getenv("DB_USER"),
        password: String? = System.getenv("DATABASE_PASSWORD") ?: System.getenv("DB_PASSWORD"),
        databaseUrl: String? = System.getenv("DATABASE_URL"),
        instanceConnectionName: String? = System.getenv("INSTANCE_CONNECTION_NAME"),
        profile: RuntimeProfile = RuntimeProfile.fromEnvironment(),
    ): DataSource {
        val urlFromEnv = databaseUrl?.trim().takeIf { !it.isNullOrEmpty() }
        val cloudSqlInstance = instanceConnectionName?.trim().takeIf { !it.isNullOrEmpty() }
        val dataSourceConfig =
            if (urlFromEnv != null) {
                val parsedUrl = parseDatabaseUrl(urlFromEnv)
                val jdbcUrl = parsedUrl?.jdbcUrl ?: normalizeJdbcUrl(urlFromEnv)
                val resolvedUsername = resolveCredential("DATABASE_USER", username, parsedUrl?.username)
                val resolvedPassword = resolveCredential("DATABASE_PASSWORD", password, parsedUrl?.password)
                buildConfig(jdbcUrl, resolvedUsername, resolvedPassword)
            } else if (cloudSqlInstance != null) {
                val resolvedUsername = resolveCredential("DATABASE_USER", username, null)
                val resolvedPassword = resolveCredential("DATABASE_PASSWORD", password, null)
                buildCloudSqlConfig(database, resolvedUsername, resolvedPassword, cloudSqlInstance)
            } else if (!profile.isProduction) {
                val jdbcUrl = "jdbc:postgresql://$host:$port/$database"
                val resolvedUsername = resolveCredential("DATABASE_USER", username, null)
                val resolvedPassword = resolveCredential("DATABASE_PASSWORD", password, null)
                buildConfig(jdbcUrl, resolvedUsername, resolvedPassword)
            } else {
                error("DATABASE_URL or INSTANCE_CONNECTION_NAME is required in production.")
            }

        return HikariDataSource(dataSourceConfig)
    }

    /**
     * Pick the first non-blank credential from (explicit argument, parsed-from-URL) and fail with a
     * clear message otherwise. The previous behavior silently fell back to `"logdate"`, which meant
     * a misconfigured deploy could try to connect with a known-default password rather than crashing
     * with an actionable error.
     */
    private fun resolveCredential(
        envVarName: String,
        explicit: String?,
        fromUrl: String?,
    ): String =
        listOfNotNull(explicit, fromUrl)
            .firstOrNull { it.isNotBlank() }
            ?: error(
                "Database credential missing: set $envVarName (or embed credentials in DATABASE_URL). " +
                    "If you're running locally, export $envVarName=<your-local-password>.",
            )

    fun initializeDatabase(
        dataSource: DataSource,
        migrate: (DataSource) -> Unit = ::runMigrations,
        connect: (DataSource) -> Database = Database::connect,
    ): Database =
        initializeDatabase(
            dataSource = dataSource,
            autoMigrate = shouldRunMigrations(),
            migrate = migrate,
            connect = connect,
        )

    fun initializeDatabase(
        dataSource: DataSource,
        autoMigrate: Boolean,
        migrate: (DataSource) -> Unit = ::runMigrations,
        connect: (DataSource) -> Database = Database::connect,
    ): Database {
        if (autoMigrate) {
            migrate(dataSource)
        } else {
            Napier.i("Database migrations skipped because AUTO_MIGRATE=false")
        }
        return connect(dataSource).also { initializedDataSource = dataSource }
    }

    private fun runMigrations(dataSource: DataSource) {
        try {
            Napier.i("Running database migrations...")

            val flyway =
                Flyway
                    .configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .load()

            val migrationResult = flyway.migrate()

            Napier.i("Database migrations completed. Applied ${migrationResult.migrationsExecuted} migrations.")
        } catch (e: Exception) {
            Napier.e("Failed to run database migrations", e)
            throw e
        }
    }

    private fun buildCloudSqlConfig(
        database: String,
        username: String,
        password: String,
        instanceConnectionName: String,
    ): HikariConfig =
        buildConfig("jdbc:postgresql:///$database", username, password).apply {
            addDataSourceProperty("socketFactory", "com.google.cloud.sql.postgres.SocketFactory")
            addDataSourceProperty("cloudSqlInstance", instanceConnectionName)
            addDataSourceProperty("ipTypes", "PUBLIC,PRIVATE")
            addDataSourceProperty("cloudSqlRefreshStrategy", "lazy")
        }

    private fun buildConfig(
        jdbcUrl: String,
        username: String,
        password: String,
    ): HikariConfig =
        HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            this.username = username
            this.password = password
            driverClassName = "org.postgresql.Driver"

            // Connection pool settings, tuned for serverless Postgres (Neon) on Cloud Run.
            // No idle floor: releasing idle connections is what lets Neon autosuspend its compute
            // to zero when traffic stops, keeping idle cost near zero.
            //
            // The pool matches Cloud Run's per-instance request concurrency deliberately. It used
            // to be five against a concurrency of sixteen, on the reasoning that a handful of
            // connections is ample - but every request here holds its connection for the whole of
            // its blocking JDBC work, so eleven of sixteen would queue instead of run. Waiting for
            // a connection took precedence over answering, health checks included: the liveness
            // probe allows five seconds and three failures, so a thirty-second wait for a
            // connection did not fail the request, it killed the instance. One device syncing was
            // enough to do it.
            //
            // `connectionTimeout` is now shorter than the pool is likely to make anyone wait, but
            // still long enough to cover a Neon wake from autosuspend. A request that cannot get a
            // connection should fail and say so rather than sit on a worker thread.
            maximumPoolSize = CLOUD_RUN_REQUEST_CONCURRENCY
            minimumIdle = 0
            connectionTimeout = 10000
            idleTimeout = 300000
            maxLifetime = 1800000
            initializationFailTimeout = -1

            // Validation settings
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_READ_COMMITTED"

            // Additional PostgreSQL optimizations
            addDataSourceProperty("cachePrepStmts", "true")
            addDataSourceProperty("prepStmtCacheSize", "250")
            addDataSourceProperty("prepStmtCacheSqlLimit", "2048")
        }

    private fun normalizeJdbcUrl(rawUrl: String): String =
        when {
            rawUrl.startsWith("jdbc:") -> rawUrl
            rawUrl.startsWith("postgres://") -> "jdbc:postgresql://${rawUrl.removePrefix("postgres://")}"
            rawUrl.startsWith("postgresql://") -> "jdbc:$rawUrl"
            else -> rawUrl
        }

    private data class ParsedDatabaseUrl(
        val jdbcUrl: String,
        val username: String?,
        val password: String?,
    )

    private fun parseDatabaseUrl(rawUrl: String): ParsedDatabaseUrl? {
        return try {
            val sanitized = rawUrl.removePrefix("jdbc:")
            val uri = java.net.URI(sanitized)
            val scheme = if (uri.scheme == "postgres") "postgresql" else uri.scheme
            val host = uri.host ?: return null
            val port = if (uri.port == -1) "" else ":${uri.port}"
            val path = uri.path ?: ""
            val query = if (uri.query.isNullOrBlank()) "" else "?${uri.query}"
            val userInfo = uri.userInfo?.split(":", limit = 2)
            val username = userInfo?.getOrNull(0)
            val password = userInfo?.getOrNull(1)
            val jdbcUrl = "jdbc:$scheme://$host$port$path$query"
            ParsedDatabaseUrl(jdbcUrl, username, password)
        } catch (_: Exception) {
            null
        }
    }
}
