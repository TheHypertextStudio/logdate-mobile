@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.database.support.createTestAccount
import app.logdate.server.identity.AtRestKeyCipher
import app.logdate.server.identity.adoption.IdentityAdoptionAuthorizations
import app.logdate.server.identity.adoption.IdentityAdoptionDeviceKey
import app.logdate.server.identity.adoption.IdentityAdoptionDeviceProof
import app.logdate.server.identity.adoption.IdentityAdoptionOAuthBinding
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionOutcome
import app.logdate.server.identity.adoption.IdentityAdoptionPasskeys
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityAdoptionProofs
import app.logdate.server.identity.adoption.IdentityAdoptionProposals
import app.logdate.server.oauth.PasskeyTestAuthenticator
import app.logdate.server.passkeys.PasskeyStepUpProof
import app.logdate.server.passkeys.WebAuthnPasskeyService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import studio.hypertext.atproto.crypto.EcCurve
import studio.hypertext.atproto.crypto.EcKeySupport
import studio.hypertext.atproto.identity.AtprotoDid
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.sql.SQLException
import java.util.Base64
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

class IdentityAdoptionAuthorizationsPostgresTest {
    @Test
    fun theVerifiedProviderDeadlineIsStoredAndCannotBeRewritten() =
        fixture {
            val expiry =
                source.blueskyTransaction {
                    prepareStatement("SELECT expires_at FROM identity_adoption_oauth_proofs WHERE operation_id = ?").use {
                        it.setString(1, operation.id)
                        it.executeQuery().use { rows -> if (rows.next()) rows.getTimestamp(1).connectionInstant() else null }
                    }
                }
            assertEquals(clock.now() + 300.seconds, assertNotNull(expiry))
            val failure =
                assertFailsWith<SQLException> {
                    source.blueskyTransaction {
                        val sql = "UPDATE identity_adoption_oauth_proofs SET binding_hash = repeat('0',64) WHERE operation_id = ?"
                        prepareStatement(sql).use {
                            it.setString(1, operation.id)
                            it.executeUpdate()
                        }
                    }
                }
            assertEquals("23514", failure.sqlState)
        }

    @Test
    fun anOldProviderProofCannotBeReplacedByOnlyAFreshPasskey() =
        fixture {
            clock.time += 301.seconds
            val started = assertNotNull(proofs.begin(operation, device))
            val proof = PasskeyStepUpProof(started.options.challenge, passkey.assert(started.options.challenge))
            assertNull(service.confirm(operation, started.decisionId, proof, sign(started.decisionId, proof.challenge)))
            assertNull(repository.get(operation, clock.now()))
            assertNull(assertNotNull(decisions.get(started.decisionId, operation, clock.now())).authorizedAt)
        }

    @Test
    fun aRawRepositoryProposalHasNoVerifiedProviderAuthority() =
        fixture {
            operations.transition(
                operation.binding.accountId,
                operation.id,
                operation.revision,
                IdentityAdoptionPhase.CANCELED,
                clock.now(),
            )
            val begun = operations.begin(operation.binding, clock.now())
            val raw = (begun as IdentityAdoptionOutcome.Accepted<IdentityAdoptionOperation>).value
            val started = assertNotNull(proofs.begin(raw, device))
            val proof = PasskeyStepUpProof(started.options.challenge, passkey.assert(started.options.challenge))
            assertNull(service.confirm(raw, started.decisionId, proof, sign(started.decisionId, proof.challenge, raw)))
            assertNull(assertNotNull(decisions.get(started.decisionId, raw, clock.now())).authorizedAt)
        }

    @Test
    fun aConfirmedOperationSurvivesRestartAndTheOriginalProofDeadline() =
        fixture {
            val started = assertNotNull(proofs.begin(operation, device))
            val proof = PasskeyStepUpProof(started.options.challenge, passkey.assert(started.options.challenge))
            val signature = sign(started.decisionId, proof.challenge)
            val saved = assertNotNull(service.confirm(operation, started.decisionId, proof, signature))
            assertEquals(device, saved.deviceKey)
            assertEquals(started.decisionId, saved.decisionId)
            assertNotNull(assertNotNull(decisions.get(started.decisionId, operation, clock.now())).consumedAt)
            clock.time += 301.seconds
            val reopened = PostgreSQLIdentityAdoptionAuthorizations(source)
            assertEquals(saved, reopened.get(operation, clock.now()))
            val resumed = IdentityAdoptionAuthorizations(proofs, reopened, clock)
            assertEquals(saved, resumed.confirm(operation, started.decisionId, proof, signature))
            assertNull(service.confirm(operation, started.decisionId, proof, "invalid"))
            assertEquals(operation.binding.sourceDid, PostgreSQLAccountRepository().findById(operation.binding.accountId)?.did)
        }

    @Test
    fun aPasskeyOnlyDecisionCannotGrantMigrationAuthority() =
        fixture {
            val started = assertNotNull(proofs.begin(operation))
            val proof = PasskeyStepUpProof(started.options.challenge, passkey.assert(started.options.challenge))
            assertNotNull(proofs.authorize(operation, started.decisionId, proof))
            assertNull(repository.authorize(operation, started.decisionId, clock.now()))
            assertNull(assertNotNull(decisions.get(started.decisionId, operation, clock.now())).consumedAt)
        }

    @Test
    fun anUnconfirmedOrExpiredDeviceDecisionCannotGrantAuthority() =
        fixture {
            val started = assertNotNull(proofs.begin(operation, device))
            assertNull(repository.authorize(operation, started.decisionId, clock.now()))
            val proof = PasskeyStepUpProof(started.options.challenge, passkey.assert(started.options.challenge))
            val signature = sign(started.decisionId, proof.challenge)
            clock.time += 301.seconds
            assertNull(service.confirm(operation, started.decisionId, proof, signature))
            assertNull(repository.get(operation, clock.now()))
        }

    @Test
    fun anInsertFailureRollsBackDecisionConsumptionAndCanResumeExactly() =
        fixture {
            val started = assertNotNull(proofs.begin(operation, device))
            val proof = PasskeyStepUpProof(started.options.challenge, passkey.assert(started.options.challenge))
            assertNotNull(proofs.authorize(operation, started.decisionId, proof, sign(started.decisionId, proof.challenge)))
            source.blueskyTransaction {
                createStatement().use {
                    it.execute(
                        "CREATE TRIGGER reject_test_authorization BEFORE INSERT ON identity_adoption_authorizations " +
                            "FOR EACH ROW EXECUTE FUNCTION preserve_identity_safety_history()",
                    )
                }
            }
            assertFailsWith<SQLException> { repository.authorize(operation, started.decisionId, clock.now()) }
            assertNull(assertNotNull(decisions.get(started.decisionId, operation, clock.now())).consumedAt)
            source.blueskyTransaction {
                createStatement().use {
                    it.execute("DROP TRIGGER reject_test_authorization ON identity_adoption_authorizations")
                }
            }
            assertNotNull(repository.authorize(operation, started.decisionId, clock.now()))
        }

    @Test
    fun competingInstancesKeepOneDurableAuthorization() =
        fixture {
            val started = assertNotNull(proofs.begin(operation, device))
            val proof = PasskeyStepUpProof(started.options.challenge, passkey.assert(started.options.challenge))
            assertNotNull(proofs.authorize(operation, started.decisionId, proof, sign(started.decisionId, proof.challenge)))
            val values =
                coroutineScope {
                    (1..8)
                        .map {
                            async(Dispatchers.IO) {
                                val instance = PostgreSQLIdentityAdoptionAuthorizations(source)
                                assertNotNull(instance.authorize(operation, started.decisionId, clock.now()))
                            }
                        }.awaitAll()
                }
            assertEquals(1, values.toSet().size)
            assertEquals(values.first(), repository.get(operation, clock.now()))
        }

    @Test
    fun cancellationOrSourcePublicationRevokesUseOfTheStamp() =
        fixture {
            authorize()
            val history = PostgreSQLHostedPlcOperationRepository()
            history.markPublicationAttempted(history.listByAccountId(operation.binding.accountId).single().id)
            assertNull(repository.get(operation, clock.now()))
            val result =
                operations.transition(
                    operation.binding.accountId,
                    operation.id,
                    operation.revision,
                    IdentityAdoptionPhase.CANCELED,
                    clock.now(),
                )
            val canceled = (result as IdentityAdoptionOutcome.Accepted<IdentityAdoptionOperation>).value
            assertNull(repository.get(canceled, clock.now()))
            assertEquals(IdentityAdoptionPhase.CANCELED, canceled.phase)
        }

    @Test
    fun aHoldAndItsExpiryDoNotDiscardAuthorizationOrPermitForeignBindings() =
        fixture {
            val saved = authorize()
            val result =
                operations.transition(
                    operation.binding.accountId,
                    operation.id,
                    operation.revision,
                    IdentityAdoptionPhase.FENCED,
                    clock.now(),
                )
            val held = (result as IdentityAdoptionOutcome.Accepted<IdentityAdoptionOperation>).value
            clock.time += 301.seconds
            assertEquals(saved, repository.get(held, clock.now()))
            assertEquals(held, operations.get(held.binding.accountId, held.id))
            for (other in listOf(
                held.copy(id = Uuid.random().toString()),
                held.copy(binding = held.binding.copy(deviceId = "other")),
                held.copy(binding = held.binding.copy(sourceEpoch = 1)),
                held.copy(binding = held.binding.copy(accountId = Uuid.random())),
            )) {
                assertNull(repository.get(other, clock.now()))
            }
        }

    @Test
    fun accountDeletionRemovesAuthorizationWithoutRecreatingIt() =
        fixture {
            authorize()
            PostgreSQLAccountRepository().deleteAccount(operation.binding.accountId)
            assertNull(repository.get(operation, clock.now()))
        }

    private fun fixture(block: suspend Fixture.() -> Unit) =
        withBlueskyOAuthDatabase { source ->
            val clock = TestClock()
            val owner = createTestAccount(Uuid.random().toJavaUUID(), "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa")
            source.blueskyTransaction {
                prepareStatement(
                    "INSERT INTO hosted_plc_operations(id, account_id, did, operation_type, operation_json, " +
                        "created_at, publication_attempted) VALUES (?, ?, ?, 'plc_operation', '{}', ?, FALSE)",
                ).use {
                    it.setObject(1, Uuid.random().toJavaUUID())
                    it.setObject(2, owner.id.toJavaUUID())
                    it.setString(3, owner.did)
                    it.setTimestamp(4, clock.now().timestamp())
                    check(it.executeUpdate() == 1)
                }
            }
            val oauth = PostgreSQLBlueskySignInAttemptStore(source, AtRestKeyCipher("synthetic-adoption-secret"), clock)
            val binding = IdentityAdoptionOAuthBinding(owner.id, requireNotNull(owner.did), 0, "device-a", Uuid.random())
            val verifier = "v".repeat(64)
            val challenge =
                Base64
                    .getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.encodeToByteArray()))
            val id = "a".repeat(43)
            oauth.createAdoption(id, challenge, clock.now() + 300.seconds, binding)
            check(oauth.complete(id, AtprotoDid.require("did:web:target.example")))
            val proposals = IdentityAdoptionProposals(oauth, PostgreSQLIdentityAdoptionRepository(source), clock)
            val begun = proposals.exchange(binding, id, verifier)
            val operation = (begun as IdentityAdoptionOutcome.Accepted<IdentityAdoptionOperation>).value
            Fixture(source, clock, operation).block()
        }

    private class TestClock(
        var time: Instant = Instant.parse("2026-10-30T00:00:00Z"),
    ) : Clock {
        override fun now(): Instant = time
    }

    private class Fixture(
        val source: DataSource,
        val clock: TestClock,
        val operation: IdentityAdoptionOperation,
    ) {
        val operations = PostgreSQLIdentityAdoptionRepository(source)
        val decisions = PostgreSQLIdentityAdoptionDecisions(source)
        val repository = PostgreSQLIdentityAdoptionAuthorizations(source)
        val web =
            WebAuthnPasskeyService(
                passkeyRepository = PostgreSQLPasskeyRepository(),
                relyingPartyId = "logdate.app",
                origins = setOf("https://logdate.app"),
                strictVerificationEnabled = true,
                challengeRepository = PostgreSQLPasskeyChallengeRepository(),
            )
        val passkey = PasskeyTestAuthenticator().also { it.registerWith(web, operation.binding.accountId, "synthetic") }
        val proofs = IdentityAdoptionProofs(decisions, IdentityAdoptionPasskeys(web), clock)
        val service = IdentityAdoptionAuthorizations(proofs, repository, clock)
        val pair = EcKeySupport.generateKeyPair(EcCurve.P256)
        val device = key(pair.public as ECPublicKey)

        fun sign(
            id: Uuid,
            challenge: String,
            operationToSign: IdentityAdoptionOperation = operation,
        ): String {
            val bytes = assertNotNull(IdentityAdoptionDeviceProof.message(operationToSign, id, challenge, device))
            val signature = EcKeySupport.signSha256(pair.private, EcCurve.P256, bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(signature)
        }

        suspend fun authorize() =
            assertNotNull(proofs.begin(operation, device)).let { started ->
                val proof = PasskeyStepUpProof(started.options.challenge, passkey.assert(started.options.challenge))
                assertNotNull(service.confirm(operation, started.decisionId, proof, sign(started.decisionId, proof.challenge)))
            }
    }

    companion object {
        private fun key(point: ECPublicKey): IdentityAdoptionDeviceKey {
            fun fixed(value: java.math.BigInteger): ByteArray {
                val bytes = value.toByteArray().takeLast(32).toByteArray()
                return ByteArray(32 - bytes.size) + bytes
            }
            return IdentityAdoptionDeviceKey(
                "https://cloud.example",
                Base64.getUrlEncoder().withoutPadding().encodeToString(
                    byteArrayOf(4) + fixed(point.w.affineX) + fixed(point.w.affineY),
                ),
            )
        }
    }
}
