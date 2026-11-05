@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.database.support.createTestAccount
import app.logdate.server.identity.adoption.IdentityAdoptionBinding
import app.logdate.server.identity.adoption.IdentityAdoptionDecision
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionOutcome
import app.logdate.server.identity.adoption.IdentityAdoptionPasskeys
import app.logdate.server.identity.adoption.IdentityAdoptionProofs
import app.logdate.server.oauth.PasskeyTestAuthenticator
import app.logdate.server.passkeys.PasskeyStepUpProof
import app.logdate.server.passkeys.WebAuthnPasskeyService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.sql.Connection
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

class IdentityAdoptionDecisionsPostgresTest {
    @Test
    fun exactDecisionSurvivesRestartAndStoresOnlyTheChallengeDigest() =
        fixture {
            val saved = decision()
            assertEquals(saved, PostgreSQLIdentityAdoptionDecisions(source).get(id, operation, now))
            assertEquals(64, saved.challengeHash.length)
            assertFalse(saved.challengeHash == challenge)
            assertNull(saved.authorizedAt)
            assertNull(saved.consumedAt)
            assertEquals(now + 300.seconds, saved.expiresAt)
        }

    @Test
    fun aDecisionIdentifierCannotBeReboundOrHaveItsExpiryExtended() =
        fixture {
            val saved = decision()
            assertEquals(saved, repository.create(id, operation, challenge, now + 2.seconds))
            assertNull(repository.create(id, operation, "another-challenge", now))
            val changed = operation.copy(binding = operation.binding.copy(targetDid = "did:web:other.example"))
            assertNull(repository.create(id, changed, challenge, now))
            assertEquals(saved, repository.get(id, operation, now))
        }

    @Test
    fun everyOperationAuthorityFieldIsRequired() =
        fixture {
            val saved = decision()
            val b = operation.binding
            val alternatives =
                listOf(
                    operation.copy(id = Uuid.random().toString()),
                    operation.copy(revision = 2),
                    operation.copy(binding = b.copy(accountId = Uuid.random())),
                    operation.copy(binding = b.copy(sourceEpoch = 1)),
                    operation.copy(binding = b.copy(sourceDid = "did:web:other.example")),
                    operation.copy(binding = b.copy(targetDid = "did:web:other.example")),
                    operation.copy(binding = b.copy(deviceId = "another-device")),
                )
            for (other in alternatives) {
                assertNull(repository.get(id, other, now))
                assertFalse(repository.authorize(id, other, challenge, now))
                assertNull(repository.consume(id, other, now) { error("A different context reached mutation") })
            }
            assertEquals(saved, repository.get(id, operation, now))
        }

    @Test
    fun authorizationRequiresTheIssuedChallengeAndDoesNotRenewTheDecision() =
        fixture {
            decision()
            assertFalse(repository.authorize(id, operation, "wrong-challenge", now))
            assertTrue(repository.authorize(id, operation, challenge, now + 1.seconds))
            val saved = assertNotNull(repository.get(id, operation, now + 2.seconds))
            assertEquals(now + 1.seconds, saved.authorizedAt)
            assertTrue(repository.authorize(id, operation, challenge, now + 2.seconds))
            assertEquals(saved, repository.get(id, operation, now + 2.seconds))
        }

    @Test
    fun anUnconfirmedDecisionCannotRunTheMutation() =
        fixture {
            decision()
            assertNull(repository.consume(id, operation, now) { error("Unconfirmed decision reached mutation") })
            assertEquals("Test owner", ownerName())
        }

    @Test
    fun expiryIsExactAndCannotBecomeAConsumedReceipt() =
        fixture {
            decision()
            assertTrue(repository.authorize(id, operation, challenge, now))
            assertFalse(repository.authorize(id, operation, challenge, now + 300.seconds))
            assertNull(repository.get(id, operation, now + 300.seconds))
            assertNull(repository.consume(id, operation, now + 300.seconds) { error("Expired decision reached mutation") })
        }

    @Test
    fun theDatabaseRevisionInvalidatesAnOldDecisionEvenWhenTheCallerRepeatsItsOldSnapshot() =
        fixture {
            decision()
            source.blueskyTransaction {
                prepareStatement("UPDATE identity_adoption_operations SET revision = revision + 1 WHERE id = ?").use {
                    it.setString(1, operation.id)
                    assertEquals(1, it.executeUpdate())
                }
            }
            assertFalse(repository.authorize(id, operation, challenge, now))
            assertNull(repository.get(id, operation, now))
            assertNull(repository.consume(id, operation, now) { error("Stale revision reached mutation") })
        }

    @Test
    fun sourceEpochChangesRefuseBothAuthorizationAndConsumption() =
        fixture {
            decision()
            assertTrue(repository.authorize(id, operation, challenge, now))
            source.blueskyTransaction {
                prepareStatement("UPDATE accounts SET identity_epoch = identity_epoch + 1 WHERE id = ?").use {
                    it.setObject(1, operation.binding.accountId.toJavaUUID())
                    assertEquals(1, it.executeUpdate())
                }
            }
            assertFalse(repository.authorize(id, operation, challenge, now))
            assertNull(repository.consume(id, operation, now) { error("Changed identity reached mutation") })
        }

    @Test
    fun missingTargetReservationCannotCreateOrAuthorizeADecision() =
        fixture {
            source.blueskyTransaction {
                prepareStatement("DELETE FROM identity_adoption_reservations WHERE operation_id = ?").use {
                    it.setString(1, operation.id)
                    assertEquals(1, it.executeUpdate())
                }
            }
            assertNull(repository.create(id, operation, challenge, now))
        }

    @Test
    fun aFailedMutationRollsBackConsumptionAndItsDatabaseChanges() =
        fixture {
            decision()
            assertTrue(repository.authorize(id, operation, challenge, now))
            assertFailsWith<Probe> {
                repository.consume(id, operation, now) { connection ->
                    changeName(connection, "must roll back")
                    throw Probe()
                }
            }
            assertNull(assertNotNull(repository.get(id, operation, now)).consumedAt)
            assertEquals("Test owner", ownerName())
            assertEquals(
                "saved",
                repository.consume(id, operation, now) { connection ->
                    changeName(connection, "saved")
                    "saved"
                },
            )
            assertNull(repository.consume(id, operation, now) { error("Consumed decision reached mutation twice") })
            assertEquals("saved", ownerName())
        }

    @Test
    fun competingInstancesCanCommitTheDecisionOnlyOnce() =
        fixture {
            decision()
            assertTrue(repository.authorize(id, operation, challenge, now))
            val results =
                coroutineScope {
                    (1..8)
                        .map {
                            async(Dispatchers.IO) {
                                PostgreSQLIdentityAdoptionDecisions(source).consume(id, operation, now) { connection ->
                                    changeName(connection, "one winner")
                                    true
                                }
                            }
                        }.awaitAll()
                }
            assertEquals(1, results.count { it == true })
            assertEquals("one winner", ownerName())
        }

    @Test
    fun accountDeletionCannotLeaveAUsableDecision() =
        fixture {
            decision()
            assertTrue(repository.authorize(id, operation, challenge, now))
            assertTrue(PostgreSQLAccountRepository().deleteAccount(operation.binding.accountId))
            assertNull(repository.get(id, operation, now))
            assertNull(repository.consume(id, operation, now) { error("Deleted account reached mutation") })
        }

    @Test
    fun realPasskeyProofBecomesDurableAuthorizationAndSurvivesALostResponse() =
        fixture {
            val web = webAuthn()
            val key = PasskeyTestAuthenticator().also { it.registerWith(web, operation.binding.accountId, "synthetic") }
            val service = proofs(web)
            val started = assertNotNull(service.begin(operation))
            assertEquals(operation.id, started.operationId)
            assertEquals(operation.revision, started.expectedRevision)
            val proof = PasskeyStepUpProof(started.options.challenge, key.assert(started.options.challenge))
            val authorized = assertNotNull(service.authorize(operation, started.decisionId, proof))
            assertNotNull(authorized.authorizedAt)
            assertEquals(authorized, proofs(webAuthn()).authorize(operation, started.decisionId, proof))
            assertEquals(true, repository.consume(started.decisionId, operation, now) { true })
            assertNull(service.authorize(operation, started.decisionId, proof))
        }

    @Test
    fun anotherOperationCannotSpendTheRealPasskeyBeforeItsOwnerCompletes() =
        fixture {
            val web = webAuthn()
            val key = PasskeyTestAuthenticator().also { it.registerWith(web, operation.binding.accountId, "synthetic") }
            val service = proofs(web)
            val started = assertNotNull(service.begin(operation))
            val proof = PasskeyStepUpProof(started.options.challenge, key.assert(started.options.challenge))
            assertNull(service.authorize(operation.copy(revision = 2), started.decisionId, proof))
            assertNotNull(service.authorize(operation, started.decisionId, proof))
        }

    @Test
    fun aRealForeignCredentialCannotAuthorizeTheOwnersDecision() =
        fixture {
            val web = webAuthn()
            val ownerKey = PasskeyTestAuthenticator().also { it.registerWith(web, operation.binding.accountId, "synthetic") }
            val foreign = createTestAccount(Uuid.random().toJavaUUID(), "did:web:foreign.example")
            val foreignKey = PasskeyTestAuthenticator().also { it.registerWith(web, foreign.id, "foreign") }
            val service = proofs(web)
            val started = assertNotNull(service.begin(operation))
            val challenge = started.options.challenge
            assertNull(service.authorize(operation, started.decisionId, PasskeyStepUpProof(challenge, foreignKey.assert(challenge))))
            assertNull(service.authorize(operation, started.decisionId, PasskeyStepUpProof(challenge, ownerKey.assert(challenge))))
            assertNull(assertNotNull(repository.get(started.decisionId, operation, now)).authorizedAt)
        }

    @Test
    fun ordinaryAccountProofDoesNotAuthorizeTheDecision() =
        fixture {
            val web = webAuthn()
            val key = PasskeyTestAuthenticator().also { it.registerWith(web, operation.binding.accountId, "synthetic") }
            val service = proofs(web)
            val started = assertNotNull(service.begin(operation))
            val ordinary = web.generateStepUpOptions(operation.binding.accountId).challenge
            assertNull(service.authorize(operation, started.decisionId, PasskeyStepUpProof(ordinary, key.assert(ordinary))))
            val challenge = started.options.challenge
            assertNotNull(service.authorize(operation, started.decisionId, PasskeyStepUpProof(challenge, key.assert(challenge))))
        }

    @Test
    fun missingPasskeysNeverProduceAUsableDecisionChallenge() =
        fixture {
            assertNull(proofs(webAuthn()).begin(operation))
        }

    @Test
    fun publicationHistoryInvalidatesAnAlreadyAuthorizedDecision() =
        fixture {
            decision()
            assertTrue(repository.authorize(id, operation, challenge, now))
            val history = PostgreSQLHostedPlcOperationRepository()
            val genesis = history.listByAccountId(operation.binding.accountId).single()
            assertTrue(history.markPublicationAttempted(genesis.id))
            assertFalse(repository.authorize(id, operation, challenge, now))
            assertNull(repository.consume(id, operation, now) { error("Publication history reached adoption mutation") })
        }

    @Test
    fun losingTheSourceGenesisDoesNotTurnUnknownHistoryIntoAuthorization() =
        fixture {
            decision()
            source.blueskyTransaction {
                prepareStatement("DELETE FROM hosted_plc_operations WHERE account_id = ?").use {
                    it.setObject(1, operation.binding.accountId.toJavaUUID())
                    assertEquals(1, it.executeUpdate())
                }
            }
            assertFalse(repository.authorize(id, operation, challenge, now))
            assertNull(repository.consume(id, operation, now) { error("Unknown publication history reached mutation") })
        }

    @Test
    fun sqlCannotRebindExtendOrRecycleAnAuthorizedDecision() =
        fixture {
            decision()
            assertTrue(repository.authorize(id, operation, challenge, now))
            for (assignment in listOf(
                "binding_hash = repeat('0', 64)",
                "challenge_hash = repeat('0', 64)",
                "expires_at = expires_at + INTERVAL '1 second'",
                "authorized_at = NULL",
            )) {
                val failure =
                    assertFailsWith<java.sql.SQLException> {
                        source.blueskyTransaction {
                            prepareStatement("UPDATE identity_adoption_decisions SET $assignment WHERE id = ?").use {
                                it.setObject(1, id.toJavaUUID())
                                it.executeUpdate()
                            }
                        }
                    }
                assertEquals("23514", failure.sqlState)
            }
            assertEquals(true, repository.consume(id, operation, now) { true })
            val failure =
                assertFailsWith<java.sql.SQLException> {
                    source.blueskyTransaction {
                        prepareStatement("UPDATE identity_adoption_decisions SET consumed_at = NULL WHERE id = ?").use {
                            it.setObject(1, id.toJavaUUID())
                            it.executeUpdate()
                        }
                    }
                }
            assertEquals("23514", failure.sqlState)
        }

    @Test
    fun differentDecisionIdentifiersCannotAuthorizeTheSameRevisionTwice() =
        fixture {
            decision()
            val other = Uuid.random()
            val anotherChallenge = "another-synthetic-challenge"
            assertNotNull(repository.create(other, operation, anotherChallenge, now))
            assertTrue(repository.authorize(id, operation, challenge, now))
            assertTrue(repository.authorize(other, operation, anotherChallenge, now))
            assertEquals(
                "first",
                repository.consume(id, operation, now) { connection ->
                    changeName(connection, "first")
                    "first"
                },
            )
            assertNull(
                repository.consume(other, operation, now) { connection ->
                    changeName(connection, "second")
                    "second"
                },
            )
            assertEquals("first", ownerName())
            val failure =
                assertFailsWith<java.sql.SQLException> {
                    source.blueskyTransaction {
                        prepareStatement("UPDATE identity_adoption_decisions SET consumed_at = ? WHERE id = ?").use {
                            it.setTimestamp(1, now.timestamp())
                            it.setObject(2, other.toJavaUUID())
                            it.executeUpdate()
                        }
                    }
                }
            assertEquals("23505", failure.sqlState)
        }

    private class Probe : IllegalStateException()

    private fun fixture(block: suspend Fixture.() -> Unit) =
        withBlueskyOAuthDatabase { source ->
            val owner = createTestAccount(Uuid.random().toJavaUUID(), "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa")
            source.blueskyTransaction {
                prepareStatement(
                    "INSERT INTO hosted_plc_operations(id, account_id, did, operation_type, operation_json, " +
                        "created_at, publication_attempted) VALUES (?, ?, ?, 'plc_operation', '{}', ?, FALSE)",
                ).use {
                    it.setObject(1, Uuid.random().toJavaUUID())
                    it.setObject(2, owner.id.toJavaUUID())
                    it.setString(3, owner.did)
                    it.setTimestamp(4, now.timestamp())
                    check(it.executeUpdate() == 1)
                }
            }
            val binding = IdentityAdoptionBinding(owner.id, requireNotNull(owner.did), "did:web:target.example", "device-a", 0)
            val operation =
                assertNotNull(
                    (PostgreSQLIdentityAdoptionRepository(source).begin(binding, now) as? IdentityAdoptionOutcome.Accepted)?.value,
                )
            Fixture(source, operation).block()
        }

    private class Fixture(
        val source: DataSource,
        val operation: IdentityAdoptionOperation,
    ) {
        val id = Uuid.random()
        val challenge = "synthetic-challenge"
        val repository = PostgreSQLIdentityAdoptionDecisions(source)

        fun webAuthn() =
            WebAuthnPasskeyService(
                passkeyRepository = PostgreSQLPasskeyRepository(),
                relyingPartyId = "logdate.app",
                origins = setOf("https://logdate.app"),
                strictVerificationEnabled = true,
                challengeRepository = PostgreSQLPasskeyChallengeRepository(),
            )

        fun proofs(web: WebAuthnPasskeyService) =
            IdentityAdoptionProofs(
                repository,
                IdentityAdoptionPasskeys(web),
                object : Clock {
                    override fun now(): Instant = now
                },
            )

        suspend fun decision(): IdentityAdoptionDecision = assertNotNull(repository.create(id, operation, challenge, now))

        suspend fun ownerName(): String = assertNotNull(PostgreSQLAccountRepository().findById(operation.binding.accountId)).displayName

        fun changeName(
            connection: Connection,
            name: String,
        ) {
            connection.prepareStatement("UPDATE accounts SET display_name = ? WHERE id = ?").use {
                it.setString(1, name)
                it.setObject(2, operation.binding.accountId.toJavaUUID())
                check(it.executeUpdate() == 1)
            }
        }
    }

    companion object {
        private val now = Instant.parse("2026-01-01T00:00:00Z")
    }
}
