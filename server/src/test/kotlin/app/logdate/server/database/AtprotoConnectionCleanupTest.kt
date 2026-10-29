@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.auth.Account
import app.logdate.server.bluesky.connection.AtprotoConnectionProvider
import app.logdate.server.identity.AtRestKeyCipher
import app.logdate.shared.model.AccountIdentityKind
import app.logdate.shared.model.AccountStorageMode
import app.logdate.shared.model.AtprotoConnectionState
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.oauth.client.InProcessOAuthSessionLock
import studio.hypertext.atproto.oauth.client.JvmOAuthCrypto
import studio.hypertext.atproto.oauth.client.OAuthConditionalRevocation
import studio.hypertext.atproto.oauth.client.OAuthRevocationException
import studio.hypertext.atproto.oauth.client.OAuthSessionEndReason
import studio.hypertext.atproto.oauth.client.OAuthStoredSession
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

class AtprotoConnectionCleanupTest {
    @Test
    fun `unclaimed grant and metadata are removed only after one inactive hour`() =
        withBlueskyOAuthDatabase { source ->
            val f = Fixture(source)
            f.tokens.save(f.session)
            f.clock.time += 59.minutes
            f.cleanup.removeUnclaimed()
            assertNotNull(f.tokens.get(f.did))
            assertEquals(0, f.provider.revocations)
            f.clock.time += 2.minutes
            f.cleanup.removeUnclaimed()
            assertNull(f.tokens.get(f.did))
            assertNull(f.connections.snapshot(f.did))
            assertEquals(1, f.provider.revocations)
        }

    @Test
    fun `active external account keeps its terminal connection evidence`() =
        withBlueskyOAuthDatabase { source ->
            val f = Fixture(source)
            f.claim()
            f.tokens.save(f.session)
            f.tokens.delete(f.did, OAuthSessionEndReason.REVOKED)
            f.clock.time += 2.hours
            f.cleanup.removeUnclaimed()
            assertEquals(AtprotoConnectionState.REVOKED, f.connections.snapshot(f.did)?.state)
            assertEquals(0, f.provider.revocations)
        }

    @Test
    fun `account claimed before locked comparison retains its grant`() =
        withBlueskyOAuthDatabase { source ->
            val f = Fixture(source)
            f.tokens.save(f.session)
            f.clock.time += 2.hours
            f.provider.beforeGuard = { f.claim() }
            f.cleanup.removeUnclaimed()
            assertNotNull(PostgreSQLAccountRepository().findByDid(f.did.toString()))
            assertNotNull(f.tokens.get(f.did))
            assertEquals(0, f.provider.revocations)
        }

    @Test
    fun `new grant before locked comparison survives an old cleanup candidate`() =
        withBlueskyOAuthDatabase { source ->
            val f = Fixture(source)
            f.tokens.save(f.session)
            f.clock.time += 2.hours
            f.provider.beforeGuard = { f.tokens.save(f.session.copy(accessToken = "replacement-access")) }
            f.cleanup.removeUnclaimed()
            assertEquals("replacement-access", f.tokens.get(f.did)?.accessToken)
            assertEquals(0, f.provider.revocations)
        }

    @Test
    fun `unconfirmed remote revocation does not retain local unclaimed credentials`() =
        withBlueskyOAuthDatabase { source ->
            val f = Fixture(source)
            f.tokens.save(f.session)
            f.clock.time += 2.hours
            f.provider.remoteFailure = true
            f.cleanup.removeUnclaimed()
            assertNull(f.tokens.get(f.did))
            assertNull(f.connections.snapshot(f.did))
        }

    private class Fixture(
        source: DataSource,
    ) {
        val clock = TestClock()
        val did = AtprotoDid.require("did:plc:" + "a".repeat(24))
        val tokens = PostgreSQLBlueskyOAuthSessionStore(source, AtRestKeyCipher("synthetic-unclaimed-cleanup-key"), clock)
        val connections = PostgreSQLAtprotoConnectionRepository(source, clock)
        val provider = Provider()
        val cleanup = PostgreSQLAtprotoConnectionCleanup(source, provider.lock, clock, provider)
        val session =
            OAuthStoredSession(
                did,
                "https://issuer.example.com",
                "https://pds.example.com",
                "atproto",
                "access",
                "refresh",
                clock.now() + 5.minutes,
                JvmOAuthCrypto.generateEs256Key(),
                "client-key",
            )

        suspend fun claim() {
            assertTrue(
                PostgreSQLAccountRepository().create(
                    Account(
                        Uuid.random(),
                        "cleanup_owner",
                        "Cleanup Owner",
                        did = did.toString(),
                        createdAt = clock.now(),
                        identityKind = AccountIdentityKind.EXTERNAL,
                        storageMode = AccountStorageMode.PERSONAL_SPACE_V1,
                    ),
                ),
            )
        }
    }

    private class Provider : AtprotoConnectionProvider {
        val lock = InProcessOAuthSessionLock()
        var beforeGuard: (suspend () -> Unit)? = null
        var revocations = 0
        var remoteFailure = false

        override suspend fun restore(did: AtprotoDid): Boolean = error("Cleanup must not renew a grant")

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
                if (remoteFailure) throw OAuthRevocationException(did, IllegalStateException("synthetic remote failure"))
                OAuthConditionalRevocation.CONFIRMED
            }
    }

    private class TestClock(
        var time: Instant = Instant.parse("2026-10-26T00:00:00Z"),
    ) : Clock {
        override fun now(): Instant = time
    }
}
