@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.identity.adoption

import app.logdate.server.passkeys.PasskeyStepUpProof
import java.security.MessageDigest
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal data class IdentityAdoptionAuthorization(
    val operationId: String,
    val accountId: Uuid,
    val revision: Long,
    val decisionId: Uuid,
    val bindingHash: String,
    val challengeHash: String,
    val deviceKey: IdentityAdoptionDeviceKey,
    val authorizedAt: Instant,
)

internal interface IdentityAdoptionAuthorizationRepository {
    suspend fun canAuthorize(
        operation: IdentityAdoptionOperation,
        now: Instant,
    ): Boolean

    suspend fun get(
        operation: IdentityAdoptionOperation,
        now: Instant,
    ): IdentityAdoptionAuthorization?

    suspend fun authorize(
        operation: IdentityAdoptionOperation,
        decisionId: Uuid,
        now: Instant,
    ): IdentityAdoptionAuthorization?
}

/** Durable operation authority is separate from the expiring confirmation decision. */
internal class IdentityAdoptionAuthorizations(
    private val proofs: IdentityAdoptionProofs,
    private val repository: IdentityAdoptionAuthorizationRepository,
    private val clock: Clock = Clock.System,
) {
    suspend fun confirm(
        operation: IdentityAdoptionOperation,
        decisionId: Uuid,
        proof: PasskeyStepUpProof,
        deviceSignature: String,
    ): IdentityAdoptionAuthorization? = confirm(operation, decisionId, proof.adoptionProof(), deviceSignature)

    suspend fun confirm(
        operation: IdentityAdoptionOperation,
        decisionId: Uuid,
        proof: IdentityAdoptionPasskeyProof,
        deviceSignature: String,
    ): IdentityAdoptionAuthorization? {
        repository.get(operation, clock.now())?.let {
            return it.takeIf { saved -> saved.matches(operation, decisionId, proof.challenge, deviceSignature) }
        }
        if (!repository.canAuthorize(operation, clock.now())) {
            return repository.get(operation, clock.now())?.takeIf { it.matches(operation, decisionId, proof.challenge, deviceSignature) }
        }
        val confirmed = proofs.authorize(operation, decisionId, proof, deviceSignature)
        if (confirmed == null || confirmed.deviceKey == null) {
            return repository.get(operation, clock.now())?.takeIf { it.matches(operation, decisionId, proof.challenge, deviceSignature) }
        }
        return repository.authorize(operation, decisionId, clock.now())
    }

    private fun IdentityAdoptionAuthorization.matches(
        operation: IdentityAdoptionOperation,
        decisionId: Uuid,
        challenge: String,
        signature: String,
    ): Boolean =
        this.decisionId == decisionId &&
            revision == operation.revision &&
            bindingHash == identityAdoptionDecisionBindingDigest(operation, deviceKey) &&
            MessageDigest.isEqual(challengeHash.encodeToByteArray(), identityAdoptionChallengeDigest(challenge).encodeToByteArray()) &&
            IdentityAdoptionDeviceProof.verify(operation, decisionId, challenge, deviceKey, signature)
}
