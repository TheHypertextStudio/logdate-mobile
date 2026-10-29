@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.uuid.Uuid

class PostgreSQLAccountCredentialMutationLockTest {
    @Test
    fun `a cancelled waiter never enters the operation or leaves a stuck local lock`() =
        withBlueskyOAuthDatabase { source ->
            val one = PostgreSQLAccountCredentialMutationLock(source)
            val account = Uuid.random()
            val invoked = AtomicBoolean()
            coroutineScope {
                val entered = CompletableDeferred<Unit>()
                val owner =
                    launch {
                        one.withLock(account) {
                            entered.complete(Unit)
                            awaitCancellation()
                        }
                    }
                try {
                    withTimeout(5_000) { entered.await() }
                    val waiting = CompletableDeferred<Unit>()
                    val waiter =
                        launch {
                            waiting.complete(Unit)
                            one.withLock(account) { invoked.set(true) }
                        }
                    withTimeout(5_000) { waiting.await() }
                    waiter.cancelAndJoin()
                    assertFalse(invoked.get())
                    owner.cancelAndJoin()
                    assertEquals("released", withTimeout(5_000) { one.withLock(account) { "released" } })
                } finally {
                    owner.cancelAndJoin()
                }
            }
        }

    @Test
    fun `credential changes for one account are serialized across server instances`() =
        withBlueskyOAuthDatabase { source ->
            val locks = listOf(PostgreSQLAccountCredentialMutationLock(source), PostgreSQLAccountCredentialMutationLock(source))
            val owner = Uuid.random()
            val active = AtomicInteger()
            val peak = AtomicInteger()
            coroutineScope {
                (1..6)
                    .map { index ->
                        async(Dispatchers.IO) {
                            locks[index % 2].withLock(owner) {
                                peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                                try {
                                    delay(20)
                                } finally {
                                    active.decrementAndGet()
                                }
                            }
                        }
                    }.awaitAll()
            }
            assertEquals(1, peak.get())
        }

    @Test
    fun `cancelled or failed credential mutation releases authority for another instance`() =
        withBlueskyOAuthDatabase { source ->
            val one = PostgreSQLAccountCredentialMutationLock(source)
            val two = PostgreSQLAccountCredentialMutationLock(source)
            val account = Uuid.random()
            coroutineScope {
                val entered = CompletableDeferred<Unit>()
                val owner =
                    launch {
                        one.withLock(account) {
                            entered.complete(Unit)
                            awaitCancellation()
                        }
                    }
                try {
                    withTimeout(5_000) { entered.await() }
                    owner.cancelAndJoin()
                    assertEquals("released", withTimeout(5_000) { two.withLock(account) { "released" } })
                    assertFailsWith<IllegalStateException> { one.withLock(account) { error("fixture failure") } }
                    assertEquals("released", withTimeout(5_000) { two.withLock(account) { "released" } })
                } finally {
                    owner.cancelAndJoin()
                }
            }
        }

    @Test
    fun `an account credential operation does not block unrelated accounts`() =
        withBlueskyOAuthDatabase { source ->
            val one = PostgreSQLAccountCredentialMutationLock(source)
            val two = PostgreSQLAccountCredentialMutationLock(source)
            coroutineScope {
                val entered = CompletableDeferred<Unit>()
                val owner =
                    launch {
                        one.withLock(Uuid.random()) {
                            entered.complete(Unit)
                            awaitCancellation()
                        }
                    }
                try {
                    withTimeout(5_000) { entered.await() }
                    assertEquals("independent", withTimeout(5_000) { two.withLock(Uuid.random()) { "independent" } })
                } finally {
                    owner.cancelAndJoin()
                }
            }
        }
}
