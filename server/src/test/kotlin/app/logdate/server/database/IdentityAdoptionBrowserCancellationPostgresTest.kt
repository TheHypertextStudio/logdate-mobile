@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.bluesky.BlueskySignInProvider
import app.logdate.server.bluesky.VerifiedBlueskyCallback
import app.logdate.server.database.support.createTestAccount
import app.logdate.server.identity.AtRestKeyCipher
import app.logdate.server.identity.adoption.IdentityAdoptionBinding
import app.logdate.server.identity.adoption.IdentityAdoptionOAuthBinding
import app.logdate.server.identity.adoption.IdentityAdoptionOAuthService
import app.logdate.server.identity.adoption.IdentityAdoptionOutcome
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityAdoptionProposals
import app.logdate.server.identity.adoption.VerifiedIdentityAdoptionOAuth
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.syntax.Handle
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class IdentityAdoptionBrowserCancellationPostgresTest {
    @Test fun replayUsesThePersistedCancellationTimestampExactly() =
        fixture {
            f.clock.time += 123.nanoseconds
            val first = accepted(cancel())
            assertEquals(first, accepted(cancel()))
        }

    @Test fun cancelBeforeCallbackIsDurableAndRejectsLateCompletion() =
        fixture {
            val canceled = accepted(cancel())
            assertEquals(binding, canceled.binding)
            assertEquals(f.clock.now(), canceled.canceledAt)
            assertFalse(attempts.complete(attempt, target))
            assertIs<IdentityAdoptionOutcome.Refused>(proposals.exchange(binding, attempt, verifier))
            assertNull(f.operations.get(binding.accountId, binding.proposalId.toString()))
            assertEquals(canceled, accepted(cancel()))
        }

    @Test fun aPreviouslyReadProviderProofCannotCreateAnOperationAfterCancellation() =
        fixture {
            val proof = verified()
            accepted(cancel())
            assertIs<IdentityAdoptionOutcome.Refused>(begin(proof))
            assertNull(f.operations.get(binding.accountId, binding.proposalId.toString()))
        }

    @Test fun anExchangeThatWonTheRaceIsAtomicallyAbandonedBeforeCancellationReturns() =
        fixture {
            val proof = verified()
            val operation = accepted(begin(proof))
            accepted(cancel())
            val after = assertNotNull(f.operations.get(binding.accountId, operation.id))
            assertEquals(IdentityAdoptionPhase.CANCELED, after.phase)
            assertEquals(operation.revision + 1, after.revision)
            assertIs<IdentityAdoptionOutcome.Refused>(begin(proof))
            assertNull(f.operations.receipt(binding.accountId, operation.id))
        }

    @Test fun aRestartCanCancelAfterTheProviderAttemptHasExpiredAndBeenRemoved() =
        fixture {
            f.clock.time += 601.seconds
            assertNull(attempts.consume(attempt, verifier))
            val first = accepted(cancel())
            f.clock.time += 20.seconds
            val restarted = PostgreSQLIdentityAdoptionBrowserCancellation(f.source)
            assertEquals(first, accepted(restarted.cancel(binding, attempt, verifier, f.clock.now())))
        }

    @Test fun aWrongVerifierCannotWriteCancellationOrConsumeTheRealProof() =
        fixture {
            assertIs<IdentityAdoptionOutcome.Refused>(cancellations.cancel(binding, attempt, "z".repeat(64), f.clock.now()))
            assertTrue(attempts.complete(attempt, target))
            accepted(proposals.exchange(binding, attempt, verifier))
        }

    @Test fun anotherDeviceCannotCancelThePendingBrowserFlow() =
        fixture {
            val wrongDevice = cancellations.cancel(binding.copy(deviceId = "other"), attempt, verifier, f.clock.now())
            assertIs<IdentityAdoptionOutcome.Refused>(wrongDevice)
            accepted(begin(verified()))
        }

    @Test fun anotherAccountCannotCancelThePendingBrowserFlow() =
        fixture {
            val other = createTestAccount(Uuid.random().toJavaUUID(), "did:plc:bbbbbbbbbbbbbbbbbbbbbbbb")
            val wrongAccount = cancellations.cancel(binding.copy(accountId = other.id), attempt, verifier, f.clock.now())
            assertIs<IdentityAdoptionOutcome.Refused>(wrongAccount)
            accepted(begin(verified()))
        }

    @Test fun aChangedSourceEpochCannotCancelOrAllocateUnderItsOldBinding() =
        fixture {
            val proof = verified()
            f.source.blueskyTransaction {
                prepareStatement("UPDATE accounts SET identity_epoch = identity_epoch + 1 WHERE id = ?").use {
                    it.setObject(1, binding.accountId.toJavaUUID())
                    it.executeUpdate()
                }
            }
            assertIs<IdentityAdoptionOutcome.Refused>(cancel())
            assertIs<IdentityAdoptionOutcome.Refused>(begin(proof))
        }

    @Test fun durableDeviceAuthorizationCannotBeCanceledWithOnlyTheBrowserProof() =
        fixture {
            val operation = accepted(begin(verified()))
            val authorized = IdentityAdoptionAuthorizedFixture(f.source, f.clock, operation)
            authorized.authorize()
            assertIs<IdentityAdoptionOutcome.Refused>(cancel())
            assertEquals(operation, f.operations.get(binding.accountId, operation.id))
            assertNotNull(authorized.repository.get(operation, f.clock.now()))
        }

    @Test fun ordinarySignInProofCannotCancelAnAdoptionProposal() =
        fixture {
            val other = "B".repeat(43)
            attempts.create(other, challenge, f.clock.now() + 300.seconds)
            assertIs<IdentityAdoptionOutcome.Refused>(cancellations.cancel(binding, other, verifier, f.clock.now()))
            assertTrue(attempts.complete(other, target))
            assertNotNull(attempts.consume(other, verifier))
            accepted(begin(verified()))
        }

    @Test fun aCanceledProposalIdentifierCannotBeReusedForANewProviderAttempt() =
        fixture {
            accepted(cancel())
            assertFails {
                attempts.createAdoption("C".repeat(43), challenge, f.clock.now() + 300.seconds, binding)
            }
            assertFalse(attempts.complete("C".repeat(43), target))
        }

    private fun fixture(block: suspend Setup.() -> Unit) =
        withIdentityAdoptionAuthorization {
            source.blueskyTransaction {
                prepareStatement("DELETE FROM identity_adoption_operations WHERE id = ?").use {
                    it.setString(1, operation.id)
                    assertEquals(1, it.executeUpdate())
                }
            }
            val setup = Setup(this)
            setup.start()
            setup.block()
            assertEquals(operation.binding.sourceDid, PostgreSQLAccountRepository().findById(operation.binding.accountId)?.did)
        }

    private class Setup(
        val f: IdentityAdoptionAuthorizedFixture,
    ) {
        val binding =
            IdentityAdoptionOAuthBinding(
                f.operation.binding.accountId,
                f.operation.binding.sourceDid,
                0,
                "device-a",
                Uuid.random(),
            )
        val attempts = PostgreSQLBlueskySignInAttemptStore(f.source, AtRestKeyCipher("synthetic-browser-cancel-secret"), f.clock)
        val cancellations = PostgreSQLIdentityAdoptionBrowserCancellation(f.source)
        val proposals = IdentityAdoptionProposals(attempts, f.operations, f.clock)
        val target = AtprotoDid.require("did:web:target.example")
        val verifier = "v".repeat(64)
        val challenge =
            Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()),
            )
        lateinit var attempt: String

        suspend fun start() {
            val provider =
                object : BlueskySignInProvider {
                    override suspend fun authorize(
                        handle: Handle,
                        attemptId: String,
                    ) = "https://provider.example/authorize"

                    override suspend fun callback(
                        parameters: Map<String, String>,
                        acceptIdentity: suspend (VerifiedBlueskyCallback) -> Unit,
                    ) { }
                }
            attempt =
                assertNotNull(
                    IdentityAdoptionOAuthService(provider, attempts, f.operations, cancellations, f.clock)
                        .start(binding, Handle.require("target.example"), challenge),
                ).attemptId
        }

        suspend fun verified(): VerifiedIdentityAdoptionOAuth {
            assertTrue(attempts.complete(attempt, target))
            return assertNotNull(attempts.exchangeAdoption(attempt, verifier, binding))
        }

        suspend fun begin(proof: VerifiedIdentityAdoptionOAuth) =
            f.operations.beginForProposal(
                IdentityAdoptionBinding(binding.accountId, binding.sourceDid, proof.did.toString(), binding.deviceId, binding.sourceEpoch),
                binding.proposalId,
                f.clock.now(),
                proof.expiresAt,
            )

        suspend fun cancel() = cancellations.cancel(binding, attempt, verifier, f.clock.now())
    }

    private fun <T> accepted(outcome: IdentityAdoptionOutcome<T>): T =
        when (outcome) {
            is IdentityAdoptionOutcome.Accepted -> outcome.value
            is IdentityAdoptionOutcome.Refused -> error("Expected acceptance, received ${outcome.reason}")
        }
}
