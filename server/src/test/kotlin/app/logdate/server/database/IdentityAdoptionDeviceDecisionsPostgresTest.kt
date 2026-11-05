@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.database.support.createTestAccount
import app.logdate.server.identity.adoption.IdentityAdoptionBinding
import app.logdate.server.identity.adoption.IdentityAdoptionDeviceKey
import app.logdate.server.identity.adoption.IdentityAdoptionDeviceProof
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionOutcome
import app.logdate.server.identity.adoption.IdentityAdoptionPasskeys
import app.logdate.server.identity.adoption.IdentityAdoptionProofs
import app.logdate.server.oauth.PasskeyTestAuthenticator
import app.logdate.server.passkeys.PasskeyStepUpProof
import app.logdate.server.passkeys.WebAuthnPasskeyService
import studio.hypertext.atproto.crypto.EcCurve
import studio.hypertext.atproto.crypto.EcKeySupport
import java.security.KeyPair
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

class IdentityAdoptionDeviceDecisionsPostgresTest {
    @Test
    fun thePasskeyDecisionPersistsTheExactDeviceKeyAcrossInstances() =
        fixture {
            val started = assertNotNull(service.begin(operation, device))
            assertEquals(device, started.deviceKey)
            val saved = assertNotNull(repository.get(started.decisionId, operation, clock.now()))
            assertEquals(device, saved.deviceKey)
            assertEquals(saved, PostgreSQLIdentityAdoptionDecisions(source).get(started.decisionId, operation, clock.now()))
        }

    @Test
    fun missingOrWrongDeviceProofCannotSpendTheRealPasskeyAssertion() =
        fixture {
            val started = assertNotNull(service.begin(operation, device))
            val proof = PasskeyStepUpProof(started.options.challenge, passkey.assert(started.options.challenge))
            assertNull(service.authorize(operation, started.decisionId, proof))
            assertNull(service.authorize(operation, started.decisionId, proof, "invalid"))
            val signature = sign(started.decisionId, proof.challenge)
            val authorized = assertNotNull(service.authorize(operation, started.decisionId, proof, signature))
            assertEquals(device, authorized.deviceKey)
            assertNotNull(authorized.authorizedAt)
            assertNull(service.authorize(operation, started.decisionId, proof))
            assertEquals(authorized, service.authorize(operation, started.decisionId, proof, signature))
        }

    @Test
    fun anotherValidOperationKeyCannotConfirmTheDecision() =
        fixture {
            val started = assertNotNull(service.begin(operation, device))
            val proof = PasskeyStepUpProof(started.options.challenge, passkey.assert(started.options.challenge))
            val another = EcKeySupport.generateKeyPair(EcCurve.P256)
            val wrong = sign(started.decisionId, proof.challenge, another)
            assertNull(service.authorize(operation, started.decisionId, proof, wrong))
            assertNotNull(service.authorize(operation, started.decisionId, proof, sign(started.decisionId, proof.challenge)))
        }

    @Test
    fun aDecisionIdentifierCannotBeReboundToAnotherDeviceKeyOrBackend() =
        fixture {
            val id = Uuid.random()
            val challenge = "a".repeat(43)
            val saved = assertNotNull(repository.create(id, operation, challenge, clock.now(), device))
            assertEquals(saved, repository.create(id, operation, challenge, clock.now() + 3.seconds, device))
            assertNull(repository.create(id, operation, challenge, clock.now(), device.copy(origin = "https://other.example")))
            val another = key(EcKeySupport.generateKeyPair(EcCurve.P256))
            assertNull(repository.create(id, operation, challenge, clock.now(), another))
            assertEquals(device, assertNotNull(repository.get(id, operation, clock.now())).deviceKey)
        }

    @Test
    fun sqlCannotReplaceEitherPartOfTheDeviceBinding() =
        fixture {
            val id = Uuid.random()
            val saved = assertNotNull(repository.create(id, operation, "a".repeat(43), clock.now(), device))
            assertEquals(device, saved.deviceKey)
            for ((column, value) in listOf(
                "device_origin" to "https://other.example",
                "device_public_key" to key(EcKeySupport.generateKeyPair(EcCurve.P256)).publicKey,
            )) {
                val failure =
                    assertFailsWith<SQLException> {
                        source.blueskyTransaction {
                            prepareStatement("UPDATE identity_adoption_decisions SET $column = ? WHERE id = ?").use {
                                it.setString(1, value)
                                it.setObject(2, id.toJavaUUID())
                                it.executeUpdate()
                            }
                        }
                    }
                assertEquals("23514", failure.sqlState)
            }
        }

    @Test
    fun anInvalidKeyOrNoncanonicalBackendCannotBeginConfirmation() =
        fixture {
            for (invalid in listOf(
                device.copy(origin = "http://cloud.example"),
                device.copy(origin = "https://cloud.example/"),
                device.copy(publicKey = "invalid"),
            )) {
                assertNull(service.begin(operation, invalid))
            }
            assertNotNull(service.begin(operation, device))
        }

    @Test
    fun aValidDeviceProofCannotExtendAnExpiredPasskeyDecision() =
        fixture {
            val started = assertNotNull(service.begin(operation, device))
            val proof = PasskeyStepUpProof(started.options.challenge, passkey.assert(started.options.challenge))
            val signature = sign(started.decisionId, proof.challenge)
            clock.time += 301.seconds
            assertNull(service.authorize(operation, started.decisionId, proof, signature))
        }

    @Test
    fun aValidDeviceProofDoesNotAuthorizeAnotherAccountsPasskey() =
        fixture {
            val foreign = createTestAccount(Uuid.random().toJavaUUID(), "did:web:foreign.example")
            val other = PasskeyTestAuthenticator().also { it.registerWith(web, foreign.id, "foreign") }
            val started = assertNotNull(service.begin(operation, device))
            val proof = PasskeyStepUpProof(started.options.challenge, other.assert(started.options.challenge))
            assertNull(service.authorize(operation, started.decisionId, proof, sign(started.decisionId, proof.challenge)))
            assertNull(assertNotNull(repository.get(started.decisionId, operation, clock.now())).authorizedAt)
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
            val binding = IdentityAdoptionBinding(owner.id, requireNotNull(owner.did), "did:web:target.example", "device-a", 0)
            val operation =
                (
                    PostgreSQLIdentityAdoptionRepository(
                        source,
                    ).begin(binding, clock.now()) as IdentityAdoptionOutcome.Accepted<IdentityAdoptionOperation>
                ).value
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
        val repository = PostgreSQLIdentityAdoptionDecisions(source)
        val web =
            WebAuthnPasskeyService(
                passkeyRepository = PostgreSQLPasskeyRepository(),
                relyingPartyId = "logdate.app",
                origins = setOf("https://logdate.app"),
                strictVerificationEnabled = true,
                challengeRepository = PostgreSQLPasskeyChallengeRepository(),
            )
        val passkey = PasskeyTestAuthenticator().also { it.registerWith(web, operation.binding.accountId, "synthetic") }
        val service = IdentityAdoptionProofs(repository, IdentityAdoptionPasskeys(web), clock)
        val pair = EcKeySupport.generateKeyPair(EcCurve.P256)
        val device = key(pair)

        fun sign(
            id: Uuid,
            challenge: String,
            signingKey: KeyPair = pair,
        ): String {
            val bytes = assertNotNull(IdentityAdoptionDeviceProof.message(operation, id, challenge, device))
            return Base64.getUrlEncoder().withoutPadding().encodeToString(EcKeySupport.signSha256(signingKey.private, EcCurve.P256, bytes))
        }
    }

    companion object {
        private fun key(pair: KeyPair): IdentityAdoptionDeviceKey {
            val point = pair.public as ECPublicKey

            fun fixed(value: java.math.BigInteger): ByteArray =
                value.toByteArray().takeLast(32).toByteArray().let {
                    ByteArray(32 - it.size) +
                        it
                }
            val bytes = byteArrayOf(4) + fixed(point.w.affineX) + fixed(point.w.affineY)
            return IdentityAdoptionDeviceKey("https://cloud.example", Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))
        }
    }
}
