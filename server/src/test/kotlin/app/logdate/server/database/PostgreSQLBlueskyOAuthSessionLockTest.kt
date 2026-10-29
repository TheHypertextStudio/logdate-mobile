package app.logdate.server.database

import app.logdate.server.database.support.localTestPostgresDataSource
import app.logdate.server.identity.AtRestKeyCipher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.oauth.client.JvmOAuthCrypto
import studio.hypertext.atproto.oauth.client.OAuthStoredSession
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class PostgreSQLBlueskyOAuthSessionLockTest {
    @Test
    fun `two server instances never refresh the same session concurrently`() =
        withBlueskyOAuthDatabase { source ->
            localTestPostgresDataSource(source.jdbcUrl).use { lockSource ->
                lockSource.maximumPoolSize = 2
                val locks = listOf(PostgreSQLBlueskyOAuthSessionLock(lockSource), PostgreSQLBlueskyOAuthSessionLock(lockSource))
                val sessions = PostgreSQLBlueskyOAuthSessionStore(source, AtRestKeyCipher("fixture-lock-test-key"))
                sessions.save(session())
                val active = AtomicInteger()
                val maximum = AtomicInteger()
                coroutineScope {
                    (0 until 6)
                        .map { index ->
                            async(Dispatchers.Default) {
                                locks[index % locks.size].withLock(DID) {
                                    maximum.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                                    try {
                                        val previous = requireNotNull(sessions.get(DID))
                                        delay(25)
                                        sessions.save(previous.copy(accessToken = (previous.accessToken.toInt() + 1).toString()))
                                    } finally {
                                        active.decrementAndGet()
                                    }
                                }
                            }
                        }.awaitAll()
                }
                assertEquals(1, maximum.get())
                assertEquals("6", sessions.get(DID)?.accessToken)
            }
        }

    @Test
    fun `cancellation and failure release the database lock for another instance`() =
        withBlueskyOAuthDatabase { source ->
            val one = PostgreSQLBlueskyOAuthSessionLock(source)
            val two = PostgreSQLBlueskyOAuthSessionLock(source)
            coroutineScope {
                val entered = CompletableDeferred<Unit>()
                val owner =
                    launch {
                        one.withLock(DID) {
                            entered.complete(Unit)
                            awaitCancellation()
                        }
                    }
                try {
                    entered.await()
                    owner.cancelAndJoin()
                    assertEquals("released", withTimeout(2_000) { two.withLock(DID) { "released" } })
                    assertFailsWith<IllegalStateException> { one.withLock(DID) { error("fixture failure") } }
                    assertEquals("released", withTimeout(2_000) { two.withLock(DID) { "released" } })
                } finally {
                    owner.cancelAndJoin()
                }
            }
        }

    @Test
    fun `a cancelled waiter never runs and another identity is not blocked`() =
        withBlueskyOAuthDatabase { source ->
            val one = PostgreSQLBlueskyOAuthSessionLock(source)
            val two = PostgreSQLBlueskyOAuthSessionLock(source)
            coroutineScope {
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val ran = AtomicBoolean()
                val owner =
                    launch {
                        one.withLock(DID) {
                            entered.complete(Unit)
                            release.await()
                        }
                    }
                entered.await()
                val waiter = launch { two.withLock(DID) { ran.set(true) } }
                try {
                    delay(150)
                    assertFalse(ran.get())
                    val other = AtprotoDid.require("did:web:other.example.com")
                    assertEquals("independent", withTimeout(2_000) { two.withLock(other) { "independent" } })
                    waiter.cancelAndJoin()
                    assertFalse(ran.get())
                } finally {
                    waiter.cancelAndJoin()
                    release.complete(Unit)
                    owner.cancelAndJoin()
                }
                assertEquals("released", withTimeout(2_000) { two.withLock(DID) { "released" } })
            }
        }

    private fun session(): OAuthStoredSession =
        OAuthStoredSession(
            did = DID,
            issuer = "https://provider.example.com",
            pdsUrl = "https://pds.example.com",
            scope = "atproto",
            accessToken = "0",
            refreshToken = "fixture-refresh-token",
            expiresAt = null,
            dpopKey = JvmOAuthCrypto.generateEs256Key(),
            clientKeyId = "fixture-client-key",
        )

    private companion object {
        val DID = AtprotoDid.require("did:plc:ewvi7nxzyoun6zhxrhs64oiz")
    }
}
