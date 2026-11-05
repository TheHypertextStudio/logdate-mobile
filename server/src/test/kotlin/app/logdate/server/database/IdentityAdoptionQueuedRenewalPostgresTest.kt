@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IDENTITY_ADOPTION_EMPTY_BODY_DIGEST
import app.logdate.server.identity.adoption.IdentityAdoptionAction
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionOutcome
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

class IdentityAdoptionQueuedRenewalPostgresTest {
    @Test fun aRenewalQueuedBeforeExpiryCannotReviveTheElapsedGeneration() =
        withIdentityAdoptionAuthorization {
            authorize()
            val held =
                (
                    operations.transition(
                        operation.binding.accountId,
                        operation.id,
                        operation.revision,
                        IdentityAdoptionPhase.FENCED,
                        clock.now(),
                    ) as IdentityAdoptionOutcome.Accepted<IdentityAdoptionOperation>
                ).value
            clock.time = held.leaseExpiresAt - 1.seconds
            val proof = assertNotNull(actions.issue(held, IdentityAdoptionAction.RENEW, IDENTITY_ADOPTION_EMPTY_BODY_DIGEST, clock.now()))
            val signature = actionSignature(proof, held)
            val time = AtomicReference(clock.now())
            val runtimeClock =
                object : Clock {
                    override fun now() = time.get()
                }
            val lifecycle = PostgreSQLIdentityAdoptionLifecycle(source, runtimeClock)
            val result =
                queued(advance = {
                    time.set(held.leaseExpiresAt + 1.seconds)
                    clock.time = time.get()
                }) {
                    lifecycle.apply(held, proof, signature)
                }
            assertIs<IdentityAdoptionOutcome.Refused>(result)
            source.blueskyTransaction {
                prepareStatement("SELECT lease_expires_at<=? FROM identity_adoption_operations WHERE id=?").use {
                    it.setTimestamp(1, clock.now().timestamp())
                    it.setString(2, held.id)
                    it.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        assertTrue(rows.getBoolean(1))
                    }
                }
            }
            assertEquals(0L, countConsumed())
        }

    @Test fun anExpiredPreparingChallengeCannotAuthorizeAQueuedMutation() =
        withIdentityAdoptionAuthorization {
            authorize()
            val proof = assertNotNull(actions.issue(operation, IdentityAdoptionAction.READ_STAGED, "a".repeat(64), clock.now()))
            val signature = actionSignature(proof)
            val time = AtomicReference(clock.now())
            val runtimeClock =
                object : Clock {
                    override fun now() = time.get()
                }
            val runtime = PostgreSQLIdentityAdoptionActions(source, runtimeClock)
            var changed = false
            val result =
                queued(advance = {
                    time.set(time.get() + 31.seconds)
                    clock.time = time.get()
                }) {
                    runtime.consume(operation, proof, signature, runtimeClock.now()) {
                        changed = true
                        true
                    }
                }
            assertNull(result)
            assertFalse(changed)
            assertEquals(0L, countConsumed())
        }

    @Test fun challengeIssuanceQueuedPastHoldExpiryCannotKeepTheOldGeneration() =
        withIdentityAdoptionAuthorization {
            authorize()
            val held =
                (
                    operations.transition(
                        operation.binding.accountId,
                        operation.id,
                        operation.revision,
                        IdentityAdoptionPhase.FENCED,
                        clock.now(),
                    ) as IdentityAdoptionOutcome.Accepted<IdentityAdoptionOperation>
                ).value
            clock.time = held.leaseExpiresAt - 1.seconds
            val time = AtomicReference(clock.now())
            val runtimeClock =
                object : Clock {
                    override fun now() = time.get()
                }
            val lifecycle = PostgreSQLIdentityAdoptionLifecycle(source, runtimeClock)
            val proof =
                queued(advance = {
                    time.set(held.leaseExpiresAt + 1.seconds)
                    clock.time = time.get()
                }) {
                    lifecycle.issue(held, IdentityAdoptionAction.RENEW)
                }
            assertNull(proof)
            val current = assertNotNull(operations.get(held.binding.accountId, held.id))
            assertEquals(IdentityAdoptionPhase.PREPARING, current.phase)
            assertEquals(held.revision + 1, current.revision)
        }

    private suspend fun <T> IdentityAdoptionAuthorizedFixture.queued(
        advance: () -> Unit,
        action: suspend () -> T,
    ): T =
        coroutineScope {
            source.connection.use { blocker ->
                blocker.autoCommit = false
                val pid =
                    blocker.createStatement().use { statement ->
                        statement.executeQuery("SELECT pg_backend_pid()").use { rows ->
                            check(rows.next())
                            rows.getInt(1)
                        }
                    }
                blocker.prepareStatement("SELECT id FROM accounts WHERE id=? FOR UPDATE").use {
                    it.setObject(1, operation.binding.accountId.toJavaUUID())
                    it.executeQuery().use { rows -> assertTrue(rows.next()) }
                }
                val pending = async(Dispatchers.IO) { action() }
                try {
                    withContext(Dispatchers.IO) {
                        withTimeout(10000) {
                            while (true) {
                                val blocked =
                                    source.connection.use { observer ->
                                        observer
                                            .prepareStatement(
                                                "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE ?=ANY(pg_blocking_pids(pid)))",
                                            ).use {
                                                it.setInt(1, pid)
                                                it.executeQuery().use { rows ->
                                                    check(rows.next())
                                                    rows.getBoolean(1)
                                                }
                                            }
                                    }
                                if (blocked) break
                                delay(10)
                            }
                        }
                    }
                    advance()
                    blocker.commit()
                    pending.await()
                } finally {
                    blocker.rollback()
                    pending.cancel()
                }
            }
        }
}
