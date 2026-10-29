@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.routes

import app.logdate.server.auth.Account
import app.logdate.server.auth.AuthMetricsRegistry
import app.logdate.server.auth.FakeGoogleIdTokenVerifier
import app.logdate.server.auth.JwtTokenService
import app.logdate.server.bluesky.AtprotoBlueskySignInProvider
import app.logdate.server.bluesky.BlueskyProtocolHarness
import app.logdate.server.bluesky.connection.AccountCredentialMutationLock
import app.logdate.server.bluesky.connection.AccountSignInMethods
import app.logdate.server.bluesky.connection.AtprotoConnectionService
import app.logdate.server.bluesky.connection.AtprotoDisconnectPasskeys
import app.logdate.server.bluesky.connection.AtprotoOAuthConnectionProvider
import app.logdate.server.bluesky.connection.AtprotoReconnectService
import app.logdate.server.configureAuthV1TestApp
import app.logdate.server.database.PostgreSQLAccountCredentialMutationLock
import app.logdate.server.database.PostgreSQLAccountIdentityRepository
import app.logdate.server.database.PostgreSQLAccountRepository
import app.logdate.server.database.PostgreSQLAtprotoConnectionRepository
import app.logdate.server.database.PostgreSQLPasskeyChallengeRepository
import app.logdate.server.database.PostgreSQLPasskeyRepository
import app.logdate.server.database.support.localTestPostgresDataSource
import app.logdate.server.database.withBlueskyOAuthDatabase
import app.logdate.server.oauth.PasskeyTestAuthenticator
import app.logdate.server.passkeys.WebAuthnPasskeyService
import app.logdate.shared.model.AccountIdentityKind
import app.logdate.shared.model.AccountStorageMode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import studio.hypertext.atproto.oauth.client.AtprotoOAuthClient
import javax.sql.DataSource
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

internal fun withAtprotoConnectionHttp(
    hosted: Boolean = false,
    onRemovalLockAttempt: suspend () -> Unit = {},
    onRemovalLockAcquired: suspend () -> Unit = {},
    block: suspend ApplicationTestBuilder.(AtprotoConnectionHttpFixture) -> Unit,
) = withBlueskyOAuthDatabase { source ->
    BlueskyProtocolHarness(source).use { provider ->
        localTestPostgresDataSource(source.jdbcUrl).use { lockSource ->
            lockSource.maximumPoolSize = 2
            val accounts = PostgreSQLAccountRepository()
            val identities = PostgreSQLAccountIdentityRepository()
            val oauth = provider.newClient(requireAuthorizationAfterPdsChange = true)
            val start = oauth.authorize(provider.atmosphere.alice.handle)
            val did = oauth.callback(provider.atmosphere.approve(start.url)).session.did
            val account =
                Account(
                    id = Uuid.random(),
                    username = "test_owner",
                    displayName = "Test owner",
                    did = did.toString(),
                    createdAt = Clock.System.now(),
                    storageMode = AccountStorageMode.PERSONAL_SPACE_V1,
                    identityKind = if (hosted) AccountIdentityKind.HOSTED else AccountIdentityKind.EXTERNAL,
                )
            assertTrue(accounts.create(account))
            val webAuthn =
                WebAuthnPasskeyService(
                    PostgreSQLPasskeyRepository(),
                    relyingPartyId = "logdate.app",
                    origins = setOf("https://logdate.app"),
                    strictVerificationEnabled = true,
                    challengeRepository = PostgreSQLPasskeyChallengeRepository(),
                )
            val key = PasskeyTestAuthenticator()
            key.registerWith(webAuthn, account.id, "owner")
            val google = FakeGoogleIdTokenVerifier(emptyMap())
            val removalDelegate = PostgreSQLAccountCredentialMutationLock(lockSource)
            val removalLock =
                object : AccountCredentialMutationLock {
                    override suspend fun <T> withLock(
                        accountId: Uuid,
                        block: suspend () -> T,
                    ): T {
                        onRemovalLockAttempt()
                        return removalDelegate.withLock(accountId) {
                            onRemovalLockAcquired()
                            block()
                        }
                    }
                }
            val service =
                AtprotoConnectionService(
                    accounts,
                    PostgreSQLAtprotoConnectionRepository(source),
                    AtprotoDisconnectPasskeys(webAuthn),
                    AccountSignInMethods(webAuthn, identities, google),
                    PostgreSQLAccountCredentialMutationLock(lockSource),
                    AtprotoOAuthConnectionProvider(oauth, provider.sessions),
                )
            val tokens = JwtTokenService("atproto-connection-http-test-secret")
            val metrics = AuthMetricsRegistry()
            val fixture =
                AtprotoConnectionHttpFixture(
                    account,
                    accounts,
                    webAuthn,
                    key,
                    provider,
                    oauth,
                    did,
                    tokens,
                    tokens.generateAccessToken(account.id.toString(), account.did),
                    source,
                    service,
                )
            testApplication {
                configureAuthV1TestApp(
                    tokenService = tokens,
                    accountRepository = accounts,
                    identityRepository = identities,
                    webAuthnPasskeyService = webAuthn,
                    googleIdTokenVerifier = google,
                    personalSpaceEnabled = true,
                    credentialMutationLock = removalLock,
                    additionalAccountRoutes = {
                        atprotoConnectionRoutes(
                            accounts,
                            tokens,
                            service,
                            metrics,
                            AtprotoReconnectService(
                                accounts,
                                provider.newAttempts(),
                                AtprotoBlueskySignInProvider(oauth),
                                service,
                                provider.atmosphere.clock,
                            ),
                        )
                    },
                )
                block(fixture)
            }
        }
    }
}

internal data class AtprotoConnectionHttpFixture(
    val account: Account,
    val accounts: PostgreSQLAccountRepository,
    val webAuthn: WebAuthnPasskeyService,
    val key: PasskeyTestAuthenticator,
    val provider: BlueskyProtocolHarness,
    val oauth: AtprotoOAuthClient,
    val oauthDid: studio.hypertext.atproto.identity.AtprotoDid,
    val tokens: JwtTokenService,
    val token: String,
    val source: DataSource,
    val connectionService: AtprotoConnectionService,
)
