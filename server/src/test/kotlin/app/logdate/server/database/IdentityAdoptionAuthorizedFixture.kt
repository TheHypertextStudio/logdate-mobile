@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.database.support.createTestAccount
import app.logdate.server.identity.AtRestKeyCipher
import app.logdate.server.identity.adoption.IdentityAdoptionAction
import app.logdate.server.identity.adoption.IdentityAdoptionActionChallenge
import app.logdate.server.identity.adoption.IdentityAdoptionActionProof
import app.logdate.server.identity.adoption.IdentityAdoptionAuthorizations
import app.logdate.server.identity.adoption.IdentityAdoptionDeviceKey
import app.logdate.server.identity.adoption.IdentityAdoptionDeviceProof
import app.logdate.server.identity.adoption.IdentityAdoptionOAuthBinding
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionOutcome
import app.logdate.server.identity.adoption.IdentityAdoptionPasskeys
import app.logdate.server.identity.adoption.IdentityAdoptionProofs
import app.logdate.server.identity.adoption.IdentityAdoptionProposals
import app.logdate.server.oauth.PasskeyTestAuthenticator
import app.logdate.server.passkeys.PasskeyStepUpProof
import app.logdate.server.passkeys.WebAuthnPasskeyService
import studio.hypertext.atproto.crypto.EcCurve
import studio.hypertext.atproto.crypto.EcKeySupport
import studio.hypertext.atproto.identity.AtprotoDid
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.util.Base64
import javax.sql.DataSource
import kotlin.test.assertNotNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal fun withIdentityAdoptionAuthorization(block: suspend IdentityAdoptionAuthorizedFixture.() -> Unit) =
    withBlueskyOAuthDatabase { source ->
        val clock = IdentityAdoptionTestClock()
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
        IdentityAdoptionAuthorizedFixture(source, clock, operation).block()
    }

internal class IdentityAdoptionTestClock(
    var time: Instant = Instant.parse("2026-10-30T00:00:00Z"),
) : Clock {
    override fun now(): Instant = time
}

internal class IdentityAdoptionAuthorizedFixture(
    val source: DataSource,
    val clock: IdentityAdoptionTestClock,
    val operation: IdentityAdoptionOperation,
) {
    val actions = PostgreSQLIdentityAdoptionActions(source)
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
    val device = AdoptionFixtureKeys.key(pair.public as ECPublicKey)

    fun sign(
        id: Uuid,
        challenge: String,
        operationToSign: IdentityAdoptionOperation = operation,
    ): String {
        val bytes = assertNotNull(IdentityAdoptionDeviceProof.message(operationToSign, id, challenge, device))
        val signature = EcKeySupport.signSha256(pair.private, EcCurve.P256, bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(signature)
    }

    fun actionSignature(
        challenge: IdentityAdoptionActionChallenge,
        value: IdentityAdoptionOperation = operation,
    ): String {
        val payload = assertNotNull(IdentityAdoptionActionProof.message(value, challenge, device))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(EcKeySupport.signSha256(pair.private, EcCurve.P256, payload))
    }

    suspend fun ticket() = assertNotNull(actions.issue(operation, IdentityAdoptionAction.HOLD, "a".repeat(64), clock.now()))

    suspend fun countConsumed(): Long =
        source.blueskyTransaction {
            createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM identity_adoption_action_challenges WHERE consumed_at IS NOT NULL").use {
                    check(it.next())
                    it.getLong(1)
                }
            }
        }

    suspend fun authorize() =
        assertNotNull(proofs.begin(operation, device)).let { started ->
            val proof = PasskeyStepUpProof(started.options.challenge, passkey.assert(started.options.challenge))
            assertNotNull(service.confirm(operation, started.decisionId, proof, sign(started.decisionId, proof.challenge)))
        }
}

private object AdoptionFixtureKeys {
    fun key(point: ECPublicKey): IdentityAdoptionDeviceKey {
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
