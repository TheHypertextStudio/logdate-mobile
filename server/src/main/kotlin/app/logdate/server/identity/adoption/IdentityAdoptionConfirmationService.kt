@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.identity.adoption

import kotlin.time.Clock
import kotlin.uuid.Uuid

internal class IdentityAdoptionConfirmationService(
    private val proofs: IdentityAdoptionProofs,
    private val decisions: IdentityAdoptionDecisionRepository,
    private val authorizations: IdentityAdoptionAuthorizations,
    private val origin: String,
    private val clock: Clock = Clock.System,
) {
    suspend fun begin(
        operation: IdentityAdoptionOperation,
        key: IdentityAdoptionDeviceKey,
    ): IdentityAdoptionDecisionChallenge? {
        if (key.origin != origin) return null
        return proofs.begin(operation, key)
    }

    suspend fun confirm(
        operation: IdentityAdoptionOperation,
        decisionId: Uuid,
        proof: IdentityAdoptionPasskeyProof,
        signature: String,
    ): IdentityAdoptionAuthorization? {
        val saved = decisions.get(decisionId, operation, clock.now()) ?: return null
        if (saved.deviceKey?.origin != origin) return null
        return authorizations.confirm(operation, decisionId, proof, signature)
    }
}
