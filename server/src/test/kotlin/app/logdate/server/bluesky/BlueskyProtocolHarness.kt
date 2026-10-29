package app.logdate.server.bluesky

import app.logdate.server.database.PostgreSQLBlueskyOAuthSessionLock
import app.logdate.server.database.PostgreSQLBlueskyOAuthSessionStore
import app.logdate.server.database.PostgreSQLBlueskyOAuthStateStore
import app.logdate.server.database.PostgreSQLBlueskySignInAttemptStore
import app.logdate.server.database.support.localTestPostgresDataSource
import app.logdate.server.identity.AtRestKeyCipher
import com.zaxxer.hikari.HikariDataSource
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import studio.hypertext.atproto.oauth.client.AtprotoOAuthClient
import studio.hypertext.atproto.oauth.client.JvmOAuthCrypto
import studio.hypertext.atproto.oauth.client.MockAtmosphere
import studio.hypertext.atproto.oauth.client.OAuthClientConfig
import studio.hypertext.atproto.oauth.client.OAuthSessionStore
import kotlin.time.Duration.Companion.minutes

/** The production stores and client against the independent, in-memory protocol provider. */
internal class BlueskyProtocolHarness(
    private val source: HikariDataSource,
) : AutoCloseable {
    val atmosphere = MockAtmosphere()
    private val cipher = AtRestKeyCipher("fixture-only-confidential-client-encryption-key")
    private val locks = localTestPostgresDataSource(source.jdbcUrl).apply { maximumPoolSize = 2 }
    val sessions = PostgreSQLBlueskyOAuthSessionStore(source, cipher)
    private val clients = mutableListOf<AtprotoOAuthClient>()
    val config =
        OAuthClientConfig(
            clientId = "https://cloud.logdate.app/oauth/client-metadata.json",
            redirectUris = listOf("https://cloud.logdate.app/oauth/bluesky/callback"),
            keys = listOf(JvmOAuthCrypto.generateClientKey("fixture-client-key")),
            scope = "atproto",
        )

    init {
        atmosphere.clientMetadataJson = Json.encodeToString(config.toClientMetadata())
    }

    fun newClient(
        configuration: OAuthClientConfig = config,
        sessionStore: OAuthSessionStore = PostgreSQLBlueskyOAuthSessionStore(source, cipher),
        requireAuthorizationAfterPdsChange: Boolean = false,
    ): AtprotoOAuthClient =
        AtprotoOAuthClient(
            config = configuration,
            identityResolver = atmosphere.identityResolver,
            httpClient = atmosphere.httpClient,
            crypto = JvmOAuthCrypto,
            stateStore = PostgreSQLBlueskyOAuthStateStore(source, cipher, atmosphere.clock),
            sessionStore = sessionStore,
            sessionLock = PostgreSQLBlueskyOAuthSessionLock(locks),
            clock = atmosphere.clock,
            stateLifetime = 5.minutes,
            strictDpopNonces = true,
            requireAuthorizationAfterPdsChange = requireAuthorizationAfterPdsChange,
        ).also { clients += it }

    fun newAttempts(): BlueskySignInAttemptStore = PostgreSQLBlueskySignInAttemptStore(source, cipher, atmosphere.clock)

    fun service(
        client: AtprotoOAuthClient = newClient(),
        attempts: BlueskySignInAttemptStore = newAttempts(),
    ) = BlueskySignInService(
        AtprotoBlueskySignInProvider(client),
        attempts,
        atmosphere.clock,
    )

    fun stop(client: AtprotoOAuthClient) {
        check(clients.remove(client))
        client.close()
    }

    override fun close() {
        clients.forEach { it.close() }
        locks.close()
        atmosphere.httpClient.close()
    }
}
