@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.client.networking.atproto.AtprotoConnectionApiClient
import app.logdate.server.auth.Account
import app.logdate.server.auth.GoogleIdTokenClaims
import app.logdate.server.auth.GoogleIdTokenVerifier
import app.logdate.server.auth.InMemoryAccountIdentityRepository
import app.logdate.server.bluesky.connection.AccountSignInMethods
import app.logdate.server.bluesky.connection.AtprotoConnectionFailure
import app.logdate.server.bluesky.connection.AtprotoConnectionProvider
import app.logdate.server.bluesky.connection.AtprotoConnectionService
import app.logdate.server.bluesky.connection.AtprotoDisconnectPasskeys
import app.logdate.server.bluesky.connection.ConnectionFailureKind
import app.logdate.server.identity.AtRestKeyCipher
import app.logdate.server.oauth.PasskeyTestAuthenticator
import app.logdate.server.passkeys.PasskeyStepUpProof
import app.logdate.server.passkeys.WebAuthnPasskeyService
import app.logdate.shared.model.AccountIdentityKind
import app.logdate.shared.model.AccountStorageMode
import app.logdate.shared.model.AtprotoConnectionSnapshot
import app.logdate.shared.model.AtprotoConnectionState
import app.logdate.shared.model.AtprotoDisconnectChallenge
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.oauth.client.InProcessOAuthSessionLock
import studio.hypertext.atproto.oauth.client.JvmOAuthCrypto
import studio.hypertext.atproto.oauth.client.OAuthConditionalRevocation
import studio.hypertext.atproto.oauth.client.OAuthRevocationException
import studio.hypertext.atproto.oauth.client.OAuthStoredSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

class AtprotoConnectionServicePostgresTest {
    @Test
    fun `hosted accounts never restore or mutate an outbound provider connection`() =
        withBlueskyOAuthDatabase { source ->
            val f = fixture(source, hosted = true)
            val status = f.service.status(f.account.id)
            assertFalse(status.eligible)
            assertFalse(status.canDisconnect)
            assertEquals(AtprotoConnectionState.DISCONNECTED, status.state)
            assertEquals(0, f.provider.restores)
            assertEquals(
                ConnectionFailureKind.INELIGIBLE,
                assertFailsWith<AtprotoConnectionFailure> {
                    f.service.beginDisconnect(f.account.id, 0)
                }.kind,
            )
            assertEquals(0, f.provider.revocations)
        }

    @Test
    fun `missing external history stays reconnect required with the zero sentinel`() =
        withBlueskyOAuthDatabase { source ->
            val f = fixture(source)
            val status = f.service.status(f.account.id)
            assertEquals(AtprotoConnectionState.RECONNECT_REQUIRED, status.state)
            assertEquals(0L, status.revision)
            assertFalse(status.canDisconnect)
            assertNull(f.connections.snapshot(f.session.did))
        }

    @Test
    fun `a retained grant without lifecycle history remains readable by the real client`() =
        withBlueskyOAuthDatabase { source ->
            val f = fixture(source)
            f.tokens.save(f.session)
            source.connection.use { connection ->
                connection.prepareStatement("DELETE FROM atproto_connections WHERE did = ?").use { statement ->
                    statement.setString(1, f.session.did.toString())
                    assertEquals(1, statement.executeUpdate())
                }
                connection.commit()
            }
            assertNotNull(f.tokens.get(f.session.did))
            assertNull(f.connections.snapshot(f.session.did))
            val status = f.service.status(f.account.id)
            assertClientReads(status)
            assertEquals(AtprotoConnectionState.RECONNECT_REQUIRED, status.state)
            assertEquals(0L, status.revision)
            assertFalse(status.canDisconnect)
        }

    @Test
    fun `a disconnect after restoration returns a coherent snapshot the real client accepts`() =
        withBlueskyOAuthDatabase { source ->
            val f = fixture(source)
            f.tokens.save(f.session)
            val challenge = f.service.beginDisconnect(f.account.id, 1)
            f.provider.afterRestore = {
                f.provider.afterRestore = null
                f.service.disconnect(f.account.id, challenge.decisionId, f.proof(challenge))
            }
            val status = f.service.status(f.account.id)
            assertClientReads(status)
            assertEquals(AtprotoConnectionState.DISCONNECTED, status.state)
            assertEquals(2L, status.revision)
            assertFalse(status.canDisconnect)
            assertEquals(1, f.provider.revocations)
        }

    @Test
    fun `connected status uses current grant evidence and an actual registered passkey`() =
        withBlueskyOAuthDatabase { source ->
            val f = fixture(source)
            f.tokens.save(f.session)
            val status = f.service.status(f.account.id)
            assertEquals(AtprotoConnectionState.CONNECTED, status.state)
            assertEquals(1L, status.revision)
            assertTrue(status.eligible)
            assertTrue(status.canDisconnect)
            f.webAuthn.deletePasskey(f.key.credentialIdB64, f.account.id)
            assertFalse(f.service.status(f.account.id).canDisconnect)
        }

    @Test
    fun `observation failure preserves durable grant state and exposes a bounded failure`() =
        withBlueskyOAuthDatabase { source ->
            val f = fixture(source)
            f.tokens.save(f.session)
            f.provider.restoreFailure = IllegalStateException("private-provider-detail")
            val failure = assertFailsWith<AtprotoConnectionFailure> { f.service.status(f.account.id) }
            assertEquals(ConnectionFailureKind.UNAVAILABLE, failure.kind)
            assertFalse(failure.toString().contains("private-provider-detail"))
            assertEquals(AtprotoConnectionState.CONNECTED, f.connections.snapshot(f.session.did)?.state)
            assertEquals(f.session, f.tokens.get(f.session.did))
        }

    @Test
    fun `disconnect preserves the account DID passkey and first party account while completing locally`() =
        withBlueskyOAuthDatabase { source ->
            val f = fixture(source)
            f.tokens.save(f.session)
            val challenge = f.service.beginDisconnect(f.account.id, 1)
            val response = f.service.disconnect(f.account.id, challenge.decisionId, f.proof(challenge))
            assertEquals(2L, response.receipt.disconnectedRevision)
            assertTrue(response.receipt.remoteConfirmed)
            assertEquals(AtprotoConnectionState.DISCONNECTED, response.current.state)
            assertFalse(response.current.canDisconnect)
            assertNull(f.tokens.get(f.session.did))
            assertEquals(f.account.did, PostgreSQLAccountRepository().findById(f.account.id)?.did)
            assertEquals(listOf(f.key.credentialIdB64), f.webAuthn.getUserCredentials(f.account.id))
        }

    @Test
    fun `a failed remote revocation returns the durable local receipt without false confirmation`() =
        withBlueskyOAuthDatabase { source ->
            val f = fixture(source)
            f.tokens.save(f.session)
            f.provider.remoteFailure = true
            val challenge = f.service.beginDisconnect(f.account.id, 1)
            val response = f.service.disconnect(f.account.id, challenge.decisionId, f.proof(challenge))
            assertFalse(response.receipt.remoteConfirmed)
            assertEquals(AtprotoConnectionState.DISCONNECTED, response.current.state)
            assertNull(f.tokens.get(f.session.did))
        }

    @Test
    fun `a replacement grant between decision and confirmation is not revoked`() =
        withBlueskyOAuthDatabase { source ->
            val f = fixture(source)
            f.tokens.save(f.session)
            val challenge = f.service.beginDisconnect(f.account.id, 1)
            val replacement = f.session.copy(accessToken = "replacement-access")
            f.tokens.save(replacement)
            assertEquals(
                ConnectionFailureKind.CHANGED,
                assertFailsWith<AtprotoConnectionFailure> {
                    f.service.disconnect(f.account.id, challenge.decisionId, f.proof(challenge))
                }.kind,
            )
            assertEquals(replacement, f.tokens.get(f.session.did))
            assertEquals(0, f.provider.revocations)
        }

    @Test
    fun `the final SDK guard rejects a replacement that arrives after the initial generation check`() =
        withBlueskyOAuthDatabase { source ->
            val f = fixture(source)
            f.tokens.save(f.session)
            val challenge = f.service.beginDisconnect(f.account.id, 1)
            val replacement = f.session.copy(accessToken = "replacement-access")
            f.provider.beforeGuard = { f.tokens.save(replacement) }
            assertEquals(
                ConnectionFailureKind.CHANGED,
                assertFailsWith<AtprotoConnectionFailure> {
                    f.service.disconnect(f.account.id, challenge.decisionId, f.proof(challenge))
                }.kind,
            )
            assertEquals(replacement, f.tokens.get(f.session.did))
            assertEquals(0, f.provider.revocations)
        }

    @Test
    fun `completed receipt replay preserves a newer connection without spending the assertion again`() =
        withBlueskyOAuthDatabase { source ->
            val f = fixture(source)
            f.tokens.save(f.session)
            val challenge = f.service.beginDisconnect(f.account.id, 1)
            val proof = f.proof(challenge)
            val first = f.service.disconnect(f.account.id, challenge.decisionId, proof)
            val replacement = f.session.copy(accessToken = "replacement-access")
            f.tokens.save(replacement)
            val retry = f.service.disconnect(f.account.id, challenge.decisionId, proof)
            assertEquals(first.receipt, retry.receipt)
            assertEquals(3L, retry.current.revision)
            assertEquals(AtprotoConnectionState.CONNECTED, retry.current.state)
            assertEquals(replacement, f.tokens.get(f.session.did))
            assertEquals(1, f.provider.revocations)
        }

    @Test
    fun `expired decisions are refused before provider work`() =
        withBlueskyOAuthDatabase { source ->
            val f = fixture(source)
            f.tokens.save(f.session)
            val challenge = f.service.beginDisconnect(f.account.id, 1)
            f.clock.time += 5.minutes
            assertEquals(
                ConnectionFailureKind.PROOF_EXPIRED,
                assertFailsWith<AtprotoConnectionFailure> {
                    f.service.disconnect(f.account.id, challenge.decisionId, f.proof(challenge))
                }.kind,
            )
            assertEquals(0, f.provider.revocations)
            assertNotNull(f.tokens.get(f.session.did))
        }

    @Test
    fun `another accounts valid registered passkey cannot authorize disconnect`() =
        withBlueskyOAuthDatabase { source ->
            val f = fixture(source)
            f.tokens.save(f.session)
            val foreign = PasskeyTestAuthenticator()
            foreign.registerWith(f.webAuthn, Uuid.random(), "foreign")
            val challenge = f.service.beginDisconnect(f.account.id, 1)
            val proof =
                PasskeyStepUpProof(challenge.authenticationOptions.challenge, foreign.assert(challenge.authenticationOptions.challenge))
            assertEquals(
                ConnectionFailureKind.PROOF_INVALID,
                assertFailsWith<AtprotoConnectionFailure> {
                    f.service.disconnect(f.account.id, challenge.decisionId, proof)
                }.kind,
            )
            assertEquals(0, f.provider.revocations)
            assertEquals(f.session, f.tokens.get(f.session.did))
        }

    @Test
    fun `a freshly inactive account cannot begin or finish a decision`() =
        withBlueskyOAuthDatabase { source ->
            val f = fixture(source)
            f.tokens.save(f.session)
            val challenge = f.service.beginDisconnect(f.account.id, 1)
            PostgreSQLAccountRepository().deactivateAccount(f.account.id)
            assertEquals(
                ConnectionFailureKind.INELIGIBLE,
                assertFailsWith<AtprotoConnectionFailure> {
                    f.service.disconnect(f.account.id, challenge.decisionId, f.proof(challenge))
                }.kind,
            )
            assertEquals(0, f.provider.revocations)
        }

    private suspend fun assertClientReads(status: AtprotoConnectionSnapshot) {
        val body = Json.encodeToString(status)
        val engine = MockEngine { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        HttpClient(engine).use { transport ->
            val client = AtprotoConnectionApiClient(transport, "https://cloud.logdate.app")
            assertEquals(status, client.snapshot("synthetic-account-token").getOrThrow())
        }
    }

    private suspend fun fixture(
        source: HikariDataSource,
        hosted: Boolean = false,
    ): Fixture {
        val clock = TestClock()
        val did = AtprotoDid.require("did:plc:" + "a".repeat(24))
        val account =
            Account(
                id = Uuid.random(),
                username = "test_owner",
                displayName = "Test owner",
                did = did.toString(),
                createdAt = clock.now(),
                identityKind = if (hosted) AccountIdentityKind.HOSTED else AccountIdentityKind.EXTERNAL,
                storageMode = AccountStorageMode.PERSONAL_SPACE_V1,
            )
        assertTrue(PostgreSQLAccountRepository().create(account))
        val webAuthn =
            WebAuthnPasskeyService(
                relyingPartyId = "logdate.app",
                origins = setOf("https://logdate.app"),
                strictVerificationEnabled = true,
            )
        val key = PasskeyTestAuthenticator()
        key.registerWith(webAuthn, account.id, "owner")
        val tokens = PostgreSQLBlueskyOAuthSessionStore(source, AtRestKeyCipher("synthetic-service-key"), clock)
        val connections = PostgreSQLAtprotoConnectionRepository(source, clock)
        val provider = Provider(tokens)
        val methods =
            AccountSignInMethods(
                webAuthn,
                InMemoryAccountIdentityRepository(),
                object : GoogleIdTokenVerifier {
                    override fun isConfigured(): Boolean = false

                    override suspend fun verify(
                        idToken: String,
                        nonce: String?,
                    ): GoogleIdTokenClaims? = null
                },
            )
        val service =
            AtprotoConnectionService(
                PostgreSQLAccountRepository(),
                connections,
                AtprotoDisconnectPasskeys(webAuthn),
                methods,
                PostgreSQLAccountCredentialMutationLock(source),
                provider,
                clock,
            )
        val session =
            OAuthStoredSession(
                did,
                "https://issuer.example.com",
                "https://pds.example.com",
                "atproto",
                "original-access",
                "original-refresh",
                clock.now() + 5.minutes,
                JvmOAuthCrypto.generateEs256Key(),
                "test-client-key",
            )
        return Fixture(clock, account, webAuthn, key, tokens, connections, provider, service, session)
    }

    private data class Fixture(
        val clock: TestClock,
        val account: Account,
        val webAuthn: WebAuthnPasskeyService,
        val key: PasskeyTestAuthenticator,
        val tokens: PostgreSQLBlueskyOAuthSessionStore,
        val connections: PostgreSQLAtprotoConnectionRepository,
        val provider: Provider,
        val service: AtprotoConnectionService,
        val session: OAuthStoredSession,
    ) {
        fun proof(challenge: AtprotoDisconnectChallenge): PasskeyStepUpProof {
            val value = challenge.authenticationOptions.challenge
            return PasskeyStepUpProof(value, key.assert(value))
        }
    }

    private class Provider(
        val tokens: PostgreSQLBlueskyOAuthSessionStore,
    ) : AtprotoConnectionProvider {
        val lock = InProcessOAuthSessionLock()
        var afterRestore: (suspend () -> Unit)? = null
        var restores = 0
        var revocations = 0
        var restoreFailure: Exception? = null
        var remoteFailure = false
        var beforeGuard: (suspend () -> Unit)? = null

        override suspend fun restore(did: AtprotoDid): Boolean {
            restores++
            restoreFailure?.let { throw it }
            val available = tokens.get(did) != null
            afterRestore?.invoke()
            return available
        }

        override suspend fun revokeIf(
            did: AtprotoDid,
            beforeRevoke: suspend () -> Boolean,
            removeLocal: suspend () -> Unit,
        ): OAuthConditionalRevocation =
            lock.withLock(did) {
                beforeGuard?.invoke()
                if (!beforeRevoke()) return@withLock OAuthConditionalRevocation.SKIPPED
                revocations++
                removeLocal()
                if (remoteFailure) throw OAuthRevocationException(did, IllegalStateException("synthetic-provider-failure"))
                OAuthConditionalRevocation.CONFIRMED
            }
    }

    private class TestClock(
        var time: Instant = Instant.parse("2026-10-26T00:00:00Z"),
    ) : Clock {
        override fun now(): Instant = time
    }
}
