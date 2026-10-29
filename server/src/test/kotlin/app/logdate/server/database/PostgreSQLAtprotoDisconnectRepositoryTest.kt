@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.auth.Account
import app.logdate.server.bluesky.connection.ConnectionChanged
import app.logdate.server.bluesky.connection.DisconnectDecision
import app.logdate.server.identity.AtRestKeyCipher
import app.logdate.shared.model.AccountIdentityKind
import app.logdate.shared.model.AccountStorageMode
import app.logdate.shared.model.AtprotoConnectionState
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.oauth.client.InProcessOAuthSessionLock
import studio.hypertext.atproto.oauth.client.JvmOAuthCrypto
import studio.hypertext.atproto.oauth.client.OAuthSessionLock
import studio.hypertext.atproto.oauth.client.OAuthStoredSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

class PostgreSQLAtprotoDisconnectRepositoryTest {
    @Test
    fun `pending decision lookup is account bound and retains expiry evidence without authorizing it`() =
        withBlueskyOAuthDatabase { source ->
            val fixture = fixture(source)
            fixture.repository.createDecision(fixture.decision)
            assertEquals(fixture.decision, fixture.repository.pendingDecision(fixture.owner, "decision-one"))
            assertNull(fixture.repository.pendingDecision(Uuid.random(), "decision-one"))
            assertNull(fixture.repository.authorizedDecision(fixture.owner, "decision-one", fixture.clock.now()))
            fixture.clock.time += 5.minutes
            assertEquals(fixture.decision, fixture.repository.pendingDecision(fixture.owner, "decision-one"))
            assertNull(fixture.repository.authorizedDecision(fixture.owner, "decision-one", fixture.clock.now()))
        }

    @Test
    fun `proof authorization binds account challenge and exact connection generation`() =
        withBlueskyOAuthDatabase { source ->
            val fixture = fixture(source)
            val repository = fixture.repository
            repository.createDecision(fixture.decision)
            assertNull(repository.authorizeDecision(Uuid.random(), "decision-one", fixture.decision.challengeHash, fixture.clock.now()))
            assertNull(repository.authorizeDecision(fixture.owner, "decision-one", "b".repeat(64), fixture.clock.now()))
            assertNull(repository.authorizedDecision(fixture.owner, "decision-one", fixture.clock.now()))
            assertEquals(
                fixture.decision,
                repository.authorizeDecision(fixture.owner, "decision-one", fixture.decision.challengeHash, fixture.clock.now()),
            )
            assertEquals(
                fixture.decision,
                PostgreSQLAtprotoConnectionRepository(
                    source,
                    fixture.clock,
                ).authorizedDecision(fixture.owner, "decision-one", fixture.clock.now()),
            )
        }

    @Test
    fun `expired unfinished decisions cannot start or resume at the five minute boundary`() =
        withBlueskyOAuthDatabase { source ->
            val fixture = fixture(source)
            fixture.repository.createDecision(fixture.decision)
            assertNotNull(
                fixture.repository.authorizeDecision(fixture.owner, "decision-one", fixture.decision.challengeHash, fixture.clock.now()),
            )
            fixture.clock.time += 5.minutes
            assertNull(
                fixture.repository.authorizeDecision(fixture.owner, "decision-one", fixture.decision.challengeHash, fixture.clock.now()),
            )
            assertNull(fixture.repository.authorizedDecision(fixture.owner, "decision-one", fixture.clock.now()))
            assertNotNull(fixture.tokens.get(fixture.session.did))
        }

    @Test
    fun `unauthorized completion cannot remove tokens`() =
        withBlueskyOAuthDatabase { source ->
            val fixture = fixture(source)
            fixture.repository.createDecision(fixture.decision)
            assertFailsWith<ConnectionChanged> { fixture.repository.completeDisconnect("decision-one") }
            assertEquals(fixture.session, fixture.tokens.get(fixture.session.did))
            assertEquals(1L, fixture.repository.snapshot(fixture.session.did)?.revision)
        }

    @Test
    fun `authorized completion removes tokens with a durable unconfirmed receipt`() =
        withBlueskyOAuthDatabase { source ->
            val fixture = fixture(source)
            fixture.authorize()
            val receipt = fixture.repository.completeDisconnect("decision-one")
            assertEquals(2L, receipt.disconnectedRevision)
            assertNull(fixture.repository.pendingDecision(fixture.owner, "decision-one"))
            assertFalse(receipt.remoteConfirmed)
            assertEquals(fixture.clock.now(), receipt.completedAt)
            assertNull(fixture.tokens.get(fixture.session.did))
            assertEquals(AtprotoConnectionState.DISCONNECTED, fixture.repository.snapshot(fixture.session.did)?.state)
            assertEquals(receipt, PostgreSQLAtprotoConnectionRepository(source, fixture.clock).receipt(fixture.owner, "decision-one"))
            assertNull(fixture.repository.receipt(Uuid.random(), "decision-one"))
        }

    @Test
    fun `an operation authorized inside the lock may finish local cleanup after expiry`() =
        withBlueskyOAuthDatabase { source ->
            val fixture = fixture(source)
            fixture.authorize()
            fixture.clock.time += 6.minutes
            assertEquals(2L, fixture.repository.completeDisconnect("decision-one").disconnectedRevision)
            assertNull(fixture.tokens.get(fixture.session.did))
        }

    @Test
    fun `a replacement grant invalidates an unfinished authorized decision`() =
        withBlueskyOAuthDatabase { source ->
            val fixture = fixture(source)
            fixture.authorize()
            val newer = fixture.session.copy(accessToken = "newer-access")
            fixture.tokens.save(newer)
            assertNull(fixture.repository.authorizedDecision(fixture.owner, "decision-one", fixture.clock.now()))
            assertFailsWith<ConnectionChanged> { fixture.repository.completeDisconnect("decision-one") }
            assertEquals(newer, fixture.tokens.get(newer.did))
            assertEquals(2L, fixture.repository.snapshot(newer.did)?.revision)
        }

    @Test
    fun `receipt replay and late remote confirmation preserve a replacement grant`() =
        withBlueskyOAuthDatabase { source ->
            val fixture = fixture(source)
            fixture.authorize()
            val receipt = fixture.repository.completeDisconnect("decision-one")
            val newer = fixture.session.copy(accessToken = "newer-access")
            fixture.tokens.save(newer)
            assertEquals(receipt, fixture.repository.completeDisconnect("decision-one"))
            assertTrue(fixture.repository.confirmRemote("decision-one").remoteConfirmed)
            assertEquals(newer, fixture.tokens.get(newer.did))
            assertEquals(3L, fixture.repository.snapshot(newer.did)?.revision)
            assertEquals(AtprotoConnectionState.CONNECTED, fixture.repository.snapshot(newer.did)?.state)
        }

    @Test
    fun `receipt write failure rolls back token removal and connection advance`() =
        withBlueskyOAuthDatabase { source ->
            val fixture = fixture(source)
            fixture.authorize()
            source.blueskyTransaction {
                createStatement().use {
                    it.execute(
                        "ALTER TABLE atproto_disconnect_decisions ADD CONSTRAINT test_no_receipt CHECK (completed_at IS NULL)",
                    )
                }
            }
            assertFailsWith<java.sql.SQLException> { fixture.repository.completeDisconnect("decision-one") }
            assertEquals(fixture.session, fixture.tokens.get(fixture.session.did))
            assertEquals(1L, fixture.repository.snapshot(fixture.session.did)?.revision)
            assertNull(fixture.repository.receipt(fixture.owner, "decision-one"))
        }

    @Test
    fun `receipt retention ends at exactly twenty four hours without changing terminal state`() =
        withBlueskyOAuthDatabase { source ->
            val fixture = fixture(source)
            fixture.authorize()
            val receipt = fixture.repository.completeDisconnect("decision-one")
            fixture.clock.time += 24.hours - 1.milliseconds
            val cleanup = PostgreSQLAtprotoConnectionCleanup(source, InProcessOAuthSessionLock(), fixture.clock)
            cleanup.expireDecisions()
            assertEquals(receipt, fixture.repository.receipt(fixture.owner, "decision-one"))
            fixture.clock.time += 1.milliseconds
            cleanup.expireDecisions()
            assertNull(fixture.repository.receipt(fixture.owner, "decision-one"))
            assertEquals(AtprotoConnectionState.DISCONNECTED, fixture.repository.snapshot(fixture.session.did)?.state)
            assertFailsWith<ConnectionChanged> { fixture.repository.completeDisconnect("decision-one") }
            source.blueskyTransaction {
                createStatement().use { statement ->
                    statement.executeQuery("SELECT COUNT(*) FROM atproto_disconnect_decisions").use {
                        assertTrue(it.next())
                        assertEquals(0, it.getInt(1))
                    }
                }
            }
        }

    @Test
    fun `expired decision cleanup removes only decisions and preserves the current grant`() =
        withBlueskyOAuthDatabase { source ->
            val fixture = fixture(source)
            fixture.authorize()
            fixture.clock.time += 5.minutes
            PostgreSQLAtprotoConnectionCleanup(source, InProcessOAuthSessionLock(), fixture.clock).expireDecisions()
            assertFailsWith<ConnectionChanged> { fixture.repository.completeDisconnect("decision-one") }
            assertEquals(fixture.session, fixture.tokens.get(fixture.session.did))
            assertEquals(1L, fixture.repository.snapshot(fixture.session.did)?.revision)
        }

    @Test
    fun `a changed canonical account identity invalidates an authorized operation`() =
        withBlueskyOAuthDatabase { source ->
            val fixture = fixture(source)
            fixture.authorize()
            source.blueskyTransaction {
                prepareStatement("UPDATE accounts SET did = ? WHERE id = ?").use {
                    it.setString(1, "did:plc:" + "b".repeat(24))
                    it.setObject(2, fixture.owner.toJavaUUID())
                    it.executeUpdate()
                }
            }
            assertNull(fixture.repository.authorizedDecision(fixture.owner, "decision-one", fixture.clock.now()))
            assertFailsWith<ConnectionChanged> { fixture.repository.completeDisconnect("decision-one") }
            assertEquals(fixture.session, fixture.tokens.get(fixture.session.did))
        }

    @Test
    fun `unknown history may be disconnected using only the zero revision sentinel`() =
        withBlueskyOAuthDatabase { source ->
            val fixture = fixture(source)
            source.blueskyTransaction { createStatement().use { it.executeUpdate("DELETE FROM atproto_connections") } }
            val decision = fixture.decision.copy(expectedRevision = 0L)
            fixture.repository.createDecision(decision)
            assertNotNull(fixture.repository.authorizeDecision(fixture.owner, decision.id, decision.challengeHash, fixture.clock.now()))
            assertEquals(1L, fixture.repository.completeDisconnect(decision.id).disconnectedRevision)
            assertNull(fixture.tokens.get(fixture.session.did))
        }

    @Test
    fun `concurrent completion across instances advances once and returns the same receipt`() =
        withBlueskyOAuthDatabase { source ->
            val fixture = fixture(source)
            fixture.authorize()
            val receipts =
                coroutineScope {
                    (1..4)
                        .map {
                            async(Dispatchers.IO) {
                                PostgreSQLAtprotoConnectionRepository(source, fixture.clock).completeDisconnect("decision-one")
                            }
                        }.awaitAll()
                }
            assertEquals(1, receipts.distinct().size)
            assertEquals(2L, fixture.repository.snapshot(fixture.session.did)?.revision)
            assertNull(fixture.tokens.get(fixture.session.did))
        }

    @Test
    fun `a challenge cannot authorize two independently retained decisions`() =
        withBlueskyOAuthDatabase { source ->
            val fixture = fixture(source)
            fixture.repository.createDecision(fixture.decision)
            assertFailsWith<java.sql.SQLException> {
                fixture.repository.createDecision(fixture.decision.copy(id = "decision-two"))
            }
            assertNull(
                fixture.repository.authorizeDecision(fixture.owner, "decision-two", fixture.decision.challengeHash, fixture.clock.now()),
            )
            assertNotNull(
                fixture.repository.authorizeDecision(fixture.owner, "decision-one", fixture.decision.challengeHash, fixture.clock.now()),
            )
        }

    @Test
    fun `cleanup waits for another instance to complete an authorized operation inside the DID lock`() =
        withBlueskyOAuthDatabase { source ->
            val fixture = fixture(source)
            fixture.authorize()
            val ownerLock = PostgreSQLBlueskyOAuthSessionLock(source)
            val waitingLock = PostgreSQLBlueskyOAuthSessionLock(source)
            val attempted = CompletableDeferred<Unit>()
            val cleanupLock =
                object : OAuthSessionLock {
                    override suspend fun <T> withLock(
                        did: AtprotoDid,
                        block: suspend () -> T,
                    ): T {
                        attempted.complete(Unit)
                        return waitingLock.withLock(did, block)
                    }
                }
            coroutineScope {
                val cleanup =
                    ownerLock.withLock(fixture.session.did) {
                        fixture.clock.time += 6.minutes
                        val job =
                            async(Dispatchers.IO) {
                                PostgreSQLAtprotoConnectionCleanup(source, cleanupLock, fixture.clock).expireDecisions()
                            }
                        withTimeout(5_000) { attempted.await() }
                        assertNull(withTimeoutOrNull(100) { job.await() })
                        fixture.repository.completeDisconnect("decision-one")
                        job
                    }
                withTimeout(5_000) { cleanup.await() }
            }
            assertNotNull(fixture.repository.receipt(fixture.owner, "decision-one"))
            assertEquals(AtprotoConnectionState.DISCONNECTED, fixture.repository.snapshot(fixture.session.did)?.state)
        }

    private suspend fun fixture(source: HikariDataSource): Fixture {
        val clock = TestClock()
        val owner = Uuid.random()
        val did = AtprotoDid.require("did:plc:" + "a".repeat(24))
        assertTrue(
            PostgreSQLAccountRepository().create(
                Account(
                    id = owner,
                    username = "test_owner",
                    displayName = "Test owner",
                    did = did.toString(),
                    createdAt = clock.now(),
                    identityKind = AccountIdentityKind.EXTERNAL,
                    storageMode = AccountStorageMode.PERSONAL_SPACE_V1,
                ),
            ),
        )
        val session =
            OAuthStoredSession(
                did = did,
                issuer = "https://issuer.example.com",
                pdsUrl = "https://pds.example.com",
                scope = "atproto",
                accessToken = "original-access",
                refreshToken = "original-refresh",
                expiresAt = clock.now() + 5.minutes,
                dpopKey = JvmOAuthCrypto.generateEs256Key(),
                clientKeyId = "test-client-key",
            )
        val tokens = PostgreSQLBlueskyOAuthSessionStore(source, AtRestKeyCipher("test-disconnect-key"), clock)
        tokens.save(session)
        return Fixture(
            clock,
            owner,
            session,
            tokens,
            PostgreSQLAtprotoConnectionRepository(source, clock),
            DisconnectDecision("decision-one", owner, did, 1L, "a".repeat(64), clock.now() + 5.minutes),
        )
    }

    private data class Fixture(
        val clock: TestClock,
        val owner: Uuid,
        val session: OAuthStoredSession,
        val tokens: PostgreSQLBlueskyOAuthSessionStore,
        val repository: PostgreSQLAtprotoConnectionRepository,
        val decision: DisconnectDecision,
    ) {
        suspend fun authorize() {
            repository.createDecision(decision)
            assertNotNull(repository.authorizeDecision(owner, decision.id, decision.challengeHash, clock.now()))
        }
    }

    private class TestClock(
        var time: Instant = Instant.parse("2026-10-26T00:00:00Z"),
    ) : Clock {
        override fun now(): Instant = time
    }
}
