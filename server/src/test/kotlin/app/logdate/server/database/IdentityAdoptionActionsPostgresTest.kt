@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionAction
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionOutcome
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityAdoptionRefusal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class IdentityAdoptionActionsPostgresTest {
    @Test
    fun acceptedIntentAndAFreshDeviceSignatureAuthorizeOnlyOneMutation() =
        fixture {
            assertNull(actions.issue(operation, IdentityAdoptionAction.HOLD, "a".repeat(64), clock.now()))
            authorize()
            val ticket = ticket()
            val restarted = PostgreSQLIdentityAdoptionActions(source)
            assertEquals(
                1,
                restarted.consume(operation, ticket, actionSignature(ticket), clock.now()) {
                    prepareStatement("UPDATE identity_adoption_operations SET revision = revision + 1 WHERE id = ?").use {
                        it.setString(1, operation.id)
                        it.executeUpdate()
                    }
                },
            )
            var repeated = false
            assertNull(restarted.consume(operation, ticket, actionSignature(ticket), clock.now()) { repeated = true })
            assertFalse(repeated)
            assertEquals(2L, operations.get(operation.binding.accountId, operation.id)?.revision)
            assertEquals(1L, countConsumed())
        }

    @Test
    fun failedSqlRollsBackTheMutationAndNonceTogether() =
        fixture {
            authorize()
            val ticket = ticket()
            assertFailsWith<SQLException> {
                actions.consume(operation, ticket, actionSignature(ticket), clock.now()) {
                    createStatement().use { it.execute("UPDATE identity_adoption_operations SET revision = 99") }
                    createStatement().use { it.execute("SELECT 1 / 0") }
                }
            }
            assertEquals(1L, operations.get(operation.binding.accountId, operation.id)?.revision)
            assertEquals(0L, countConsumed())
            assertEquals("accepted", actions.consume(operation, ticket, actionSignature(ticket), clock.now()) { "accepted" })
            assertEquals(1L, countConsumed())
        }

    @Test
    fun wrongBodyActionDeviceAndSignatureNeverEnterMutation() =
        fixture {
            authorize()
            val ticket = ticket()
            var entered = false
            for (other in listOf(
                ticket.copy(bodyDigest = "b".repeat(64)),
                ticket.copy(action = IdentityAdoptionAction.CANCEL),
                ticket.copy(nonce = "b".repeat(43)),
                ticket.copy(id = Uuid.random()),
            )) {
                assertNull(actions.consume(operation, other, actionSignature(other), clock.now()) { entered = true })
            }
            assertNull(actions.consume(operation, ticket, "a".repeat(86), clock.now()) { entered = true })
            val foreign = operation.copy(binding = operation.binding.copy(deviceId = "other-device"))
            assertNull(actions.consume(foreign, ticket, actionSignature(ticket, foreign), clock.now()) { entered = true })
            assertFalse(entered)
            assertEquals(0L, countConsumed())
            assertEquals(true, actions.consume(operation, ticket, actionSignature(ticket), clock.now()) { true })
        }

    @Test
    fun expiryAndCurrentRevisionRefuseWithoutConsuming() =
        fixture {
            authorize()
            val ticket = ticket()
            clock.time += 31.seconds
            var entered = false
            assertNull(actions.consume(operation, ticket, actionSignature(ticket), clock.now()) { entered = true })
            val fresh = ticket()
            operations.transition(operation.binding.accountId, operation.id, operation.revision, IdentityAdoptionPhase.FENCED, clock.now())
            assertNull(actions.consume(operation, fresh, actionSignature(fresh), clock.now()) { entered = true })
            assertFalse(entered)
            assertEquals(0L, countConsumed())
        }

    @Test
    fun heldOperationsUseOriginalKeyButCannotRenewAnExpiredHold() =
        fixture {
            authorize()
            val held =
                (
                    operations.transition(
                        operation.binding.accountId,
                        operation.id,
                        operation.revision,
                        IdentityAdoptionPhase.FENCED,
                        clock.now(),
                    ) as IdentityAdoptionOutcome.Accepted
                ).value
            val ticket = assertNotNull(actions.issue(held, IdentityAdoptionAction.RENEW, "a".repeat(64), clock.now()))
            assertEquals(true, actions.consume(held, ticket, actionSignature(ticket, held), clock.now()) { true })
            clock.time += 121.seconds
            assertNull(actions.issue(held, IdentityAdoptionAction.RENEW, "a".repeat(64), clock.now()))
        }

    @Test
    fun publicationOrDeletionRevokesAnAlreadyIssuedChallenge() =
        fixture {
            authorize()
            val ticket = ticket()
            val history = PostgreSQLHostedPlcOperationRepository()
            history.markPublicationAttempted(history.listByAccountId(operation.binding.accountId).single().id)
            var entered = false
            assertNull(actions.consume(operation, ticket, actionSignature(ticket), clock.now()) { entered = true })
            PostgreSQLAccountRepository().deleteAccount(operation.binding.accountId)
            assertNull(actions.consume(operation, ticket, actionSignature(ticket), clock.now()) { entered = true })
            assertFalse(entered)
        }

    @Test
    fun competingInstancesConsumeOneNonceOnce() =
        fixture {
            authorize()
            val ticket = ticket()
            val signature = actionSignature(ticket)
            val results =
                coroutineScope {
                    (1..8)
                        .map {
                            async(Dispatchers.IO) {
                                PostgreSQLIdentityAdoptionActions(source).consume(operation, ticket, signature, clock.now()) { true }
                            }
                        }.awaitAll()
                }
            assertEquals(1, results.count { it == true })
            assertEquals(1L, countConsumed())
        }

    @Test
    fun passiveChallengesAreBoundedAndCannotRewriteTheirBinding() =
        fixture {
            authorize()
            val ticket = ticket()
            val failure =
                assertFailsWith<SQLException> {
                    source.blueskyTransaction {
                        createStatement().use { it.execute("UPDATE identity_adoption_action_challenges SET body_digest = repeat('b',64)") }
                    }
                }
            assertEquals("23514", failure.sqlState)
            repeat(31) { ticket() }
            assertNull(actions.issue(operation, IdentityAdoptionAction.HOLD, "a".repeat(64), clock.now()))
            assertEquals(1L, operations.get(operation.binding.accountId, operation.id)?.revision)
            assertEquals(0L, countConsumed())
            assertEquals(true, actions.consume(operation, ticket, actionSignature(ticket), clock.now()) { true })
        }

    @Test
    fun signedHoldRenewAndCancelUseTheOriginalDeviceWithoutChangingIdentity() =
        fixture {
            authorize()
            val digest = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

            suspend fun move(
                value: IdentityAdoptionOperation,
                action: IdentityAdoptionAction,
            ): IdentityAdoptionOperation {
                val proof = assertNotNull(actions.issue(value, action, digest, clock.now()))
                val result = operations.transitionWithProof(value, proof, actionSignature(proof, value), clock.now())
                return (result as IdentityAdoptionOutcome.Accepted).value
            }
            val held = move(operation, IdentityAdoptionAction.HOLD)
            assertEquals(IdentityAdoptionPhase.FENCED, held.phase)
            assertEquals(2L, held.revision)
            clock.time += 20.seconds
            val renewed = move(held, IdentityAdoptionAction.RENEW)
            assertEquals(3L, renewed.revision)
            assertEquals(clock.now() + 120.seconds, renewed.leaseExpiresAt)
            val canceled = move(renewed, IdentityAdoptionAction.CANCEL)
            assertEquals(IdentityAdoptionPhase.CANCELED, canceled.phase)
            assertEquals(4L, canceled.revision)
            val owner = assertNotNull(PostgreSQLAccountRepository().findById(operation.binding.accountId))
            assertEquals(operation.binding.sourceDid, owner.did)
            assertEquals(0L, owner.identityEpoch)
            assertEquals(3L, countConsumed())
        }

    @Test
    fun signedLifecycleRefusesWrongPayloadWithoutConsumingTheNonce() =
        fixture {
            authorize()
            val ticket = ticket()
            val result = operations.transitionWithProof(operation, ticket, actionSignature(ticket), clock.now())
            assertEquals(IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.PROOF_INVALID), result)
            assertEquals(0L, countConsumed())
            assertEquals(operation, operations.get(operation.binding.accountId, operation.id))
        }

    @Test
    fun anExpiredHoldCanResumeOnlyAfterARefreshedRevisionAndFreshSignedAction() =
        fixture {
            recoverExpiredHold(IdentityAdoptionAction.HOLD, IdentityAdoptionPhase.FENCED)
        }

    @Test
    fun anExpiredHoldCanBeCanceledWithoutWaitingForAnUnrelatedCloudWrite() =
        fixture {
            recoverExpiredHold(IdentityAdoptionAction.CANCEL, IdentityAdoptionPhase.CANCELED)
        }

    private suspend fun IdentityAdoptionAuthorizedFixture.recoverExpiredHold(
        action: IdentityAdoptionAction,
        expectedPhase: IdentityAdoptionPhase,
    ) {
        authorize()
        val held =
            (
                operations.transition(
                    operation.binding.accountId,
                    operation.id,
                    operation.revision,
                    IdentityAdoptionPhase.FENCED,
                    clock.now(),
                ) as IdentityAdoptionOutcome.Accepted
            ).value
        val digest = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        clock.time += 121.seconds
        assertNull(actions.issue(held, action, digest, clock.now()))
        val current = assertNotNull(operations.get(held.binding.accountId, held.id))
        assertEquals(IdentityAdoptionPhase.PREPARING, current.phase)
        assertEquals(held.revision + 1, current.revision)
        assertEquals(held.leaseExpiresAt, current.leaseExpiresAt)
        assertEquals(0L, countConsumed())
        val ticket = assertNotNull(actions.issue(current, action, digest, clock.now()))
        val outcome = operations.transitionWithProof(current, ticket, actionSignature(ticket, current), clock.now())
        val next = (outcome as IdentityAdoptionOutcome.Accepted).value
        assertEquals(expectedPhase, next.phase)
        assertEquals(current.revision + 1, next.revision)
        val owner = assertNotNull(PostgreSQLAccountRepository().findById(operation.binding.accountId))
        assertEquals(operation.binding.sourceDid, owner.did)
        assertEquals(0L, owner.identityEpoch)
        assertEquals(1L, countConsumed())
        if (action == IdentityAdoptionAction.HOLD) assertEquals(clock.now() + 120.seconds, next.leaseExpiresAt)
    }

    private fun fixture(block: suspend IdentityAdoptionAuthorizedFixture.() -> Unit) = withIdentityAdoptionAuthorization(block)
}
