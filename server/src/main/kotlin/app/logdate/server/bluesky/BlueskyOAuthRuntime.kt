package app.logdate.server.bluesky

import app.logdate.server.bluesky.connection.AtprotoConnectionMaintenance
import app.logdate.server.bluesky.connection.AtprotoConnectionRuntime
import app.logdate.server.bluesky.connection.AtprotoOAuthConnectionProvider
import app.logdate.server.config.AtprotoSigningKeyKek
import app.logdate.server.config.AtprotoSigningKeyKekDecryptOnly
import app.logdate.server.database.DatabaseConfig
import app.logdate.server.database.PostgreSQLAtprotoConnectionCleanup
import app.logdate.server.database.PostgreSQLAtprotoConnectionRepository
import app.logdate.server.database.PostgreSQLBlueskyAccountEnrollmentRepository
import app.logdate.server.database.PostgreSQLBlueskyAccountHandoffStore
import app.logdate.server.database.PostgreSQLBlueskyOAuthSessionLock
import app.logdate.server.database.PostgreSQLBlueskyOAuthSessionStore
import app.logdate.server.database.PostgreSQLBlueskyOAuthStateStore
import app.logdate.server.database.PostgreSQLBlueskySignInAttemptStore
import app.logdate.server.database.requireAtprotoConnectionSchema
import app.logdate.server.database.requireBlueskyOAuthSchema
import app.logdate.server.identity.AtRestKeyCipher
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import studio.hypertext.atproto.oauth.client.AtprotoOAuthClient
import studio.hypertext.atproto.oauth.client.JvmOAuthCrypto
import studio.hypertext.atproto.oauth.client.JvmOAuthHttp
import studio.hypertext.atproto.oauth.client.OAuthClientConfig
import studio.hypertext.atproto.oauth.client.OAuthSessionLock
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource
import kotlin.time.Duration.Companion.minutes

internal class BlueskyOAuthRuntime(
    val client: AtprotoOAuthClient,
    val signIn: BlueskySignInService,
    val handoffs: BlueskyAccountHandoffStore,
    val enrollments: BlueskyAccountEnrollmentRepository,
    private val http: HttpClient,
    private val lockSource: HikariDataSource,
    val connection: AtprotoConnectionRuntime? = null,
    val sessionLock: OAuthSessionLock,
) : AutoCloseable {
    private val closed = AtomicBoolean()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            connection?.maintenance?.close()
        } finally {
            try {
                client.close()
            } finally {
                try {
                    http.close()
                } finally {
                    lockSource.close()
                }
            }
        }
    }
}

internal fun createBlueskyOAuthRuntime(
    isDatabaseAvailable: Boolean,
    readEnv: (String) -> String? = System::getenv,
    sourceProvider: () -> DataSource? = { DatabaseConfig.initializedDataSource },
    lockFactory: () -> HikariDataSource = { DatabaseConfig.createDataSource() as HikariDataSource },
    httpFactory: () -> HttpClient = ::blueskyHttpClient,
    checkSchema: (DataSource) -> Unit = ::requireBlueskyOAuthSchema,
    checkConnectionSchema: (DataSource) -> Unit = ::requireAtprotoConnectionSchema,
): BlueskyOAuthRuntime? {
    val config = BlueskyOAuthConfig.fromEnvironment(readEnv) ?: return null
    require(isDatabaseAvailable) { "Bluesky sign-in requires the application database" }
    val source = requireNotNull(sourceProvider()) { "Bluesky sign-in requires the initialized application database" }
    checkSchema(source)
    val connectionEnabled = readEnv("LOGDATE_ATPROTO_CONNECTION_ENABLED") == "true"
    if (connectionEnabled) checkConnectionSchema(source)
    check(runCatching { Class.forName("com.sun.jndi.dns.DnsContextFactory") }.isSuccess) {
        "Bluesky sign-in requires the JVM DNS provider"
    }
    val cipher =
        AtRestKeyCipher(
            AtprotoSigningKeyKek.resolve(readEnv),
            listOfNotNull(AtprotoSigningKeyKekDecryptOnly.resolve(readEnv)),
        )
    val locks = lockFactory()
    try {
        require(!locks.isClosed)
        locks.minimumIdle = 0
        locks.maximumPoolSize = 4
        locks.connectionTimeout = 5000
        locks.validationTimeout = 2000
        val http = httpFactory()
        try {
            return createBlueskyServices(config, source, cipher, locks, http, connectionEnabled)
        } catch (failure: Throwable) {
            http.close()
            throw failure
        }
    } catch (failure: Throwable) {
        locks.close()
        throw failure
    }
}

private fun createBlueskyServices(
    config: OAuthClientConfig,
    source: DataSource,
    cipher: AtRestKeyCipher,
    locks: HikariDataSource,
    http: HttpClient,
    connectionEnabled: Boolean,
): BlueskyOAuthRuntime {
    val sessions = PostgreSQLBlueskyOAuthSessionStore(source, cipher)
    val sessionLock = PostgreSQLBlueskyOAuthSessionLock(locks)
    val client =
        AtprotoOAuthClient(
            config = config,
            identityResolver = BlueskyIdentityResolver(http, JndiBlueskyTxtLookup()),
            httpClient = http,
            crypto = JvmOAuthCrypto,
            stateStore = PostgreSQLBlueskyOAuthStateStore(source, cipher),
            sessionStore = sessions,
            sessionLock = sessionLock,
            stateLifetime = 5.minutes,
            strictDpopNonces = true,
            requireAuthorizationAfterPdsChange = true,
        )
    try {
        val authorization = AtprotoBlueskySignInProvider(client)
        val attempts = PostgreSQLBlueskySignInAttemptStore(source, cipher)
        val signIn =
            BlueskySignInService(
                authorization,
                attempts,
            )
        return BlueskyOAuthRuntime(
            client,
            signIn,
            PostgreSQLBlueskyAccountHandoffStore(source, cipher),
            PostgreSQLBlueskyAccountEnrollmentRepository(source),
            http,
            locks,
            if (connectionEnabled) {
                val provider = AtprotoOAuthConnectionProvider(client, sessions)
                val cleanup = PostgreSQLAtprotoConnectionCleanup(source, sessionLock, provider = provider)
                AtprotoConnectionRuntime(
                    PostgreSQLAtprotoConnectionRepository(source),
                    provider,
                    attempts,
                    authorization,
                    AtprotoConnectionMaintenance(cleanup::expireDecisions, cleanup::removeUnclaimed),
                )
            } else {
                null
            },
            sessionLock,
        )
    } catch (failure: Throwable) {
        client.close()
        throw failure
    }
}

private fun blueskyHttpClient(): HttpClient =
    JvmOAuthHttp.publicInternetClient {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 5000
            requestTimeoutMillis = 15_000
            socketTimeoutMillis = 15_000
        }
    }
