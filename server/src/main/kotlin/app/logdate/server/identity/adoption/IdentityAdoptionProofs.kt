@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.identity.adoption

import app.logdate.server.passkeys.PasskeyStepUpProof
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Combines real passkey verification and durable decisions without holding SQL locks during crypto. */
internal class IdentityAdoptionProofs(
    private val decisions: IdentityAdoptionDecisionRepository,
    private val passkeys: IdentityAdoptionPasskeys,
    private val clock: Clock = Clock.System,
) {
    suspend fun begin(
        operation: IdentityAdoptionOperation,
        deviceKey: IdentityAdoptionDeviceKey? = null,
    ): IdentityAdoptionDecisionChallenge? {
        val id = Uuid.random()
        if (deviceKey != null && IdentityAdoptionDeviceProof.message(operation, id, "A".repeat(43), deviceKey) == null) return null
        val options =
            try {
                passkeys.begin(operation.binding.accountId, id)
            } catch (_: IdentityAdoptionPasskeyUnavailable) {
                return null
            }
        val decision = decisions.create(id, operation, options.challenge, clock.now(), deviceKey) ?: return null
        return IdentityAdoptionDecisionChallenge(id, operation.id, operation.revision, options, decision.expiresAt, decision.deviceKey)
    }

    suspend fun authorize(
        operation: IdentityAdoptionOperation,
        decisionId: Uuid,
        proof: PasskeyStepUpProof,
        deviceSignature: String? = null,
    ): IdentityAdoptionDecision? = authorize(operation, decisionId, proof.adoptionProof(), deviceSignature)

    suspend fun authorize(
        operation: IdentityAdoptionOperation,
        decisionId: Uuid,
        proof: IdentityAdoptionPasskeyProof,
        deviceSignature: String? = null,
    ): IdentityAdoptionDecision? {
        val saved = decisions.get(decisionId, operation, clock.now()) ?: return null
        if (saved.consumedAt != null || !saved.matchesChallenge(proof.challenge)) return null
        if (saved.deviceKey != null) {
            if (deviceSignature == null ||
                !IdentityAdoptionDeviceProof.verify(operation, decisionId, proof.challenge, saved.deviceKey, deviceSignature)
            ) {
                return null
            }
        } else if (deviceSignature != null) {
            return null
        }
        // An interrupted response reuses the already confirmed decision, never a new WebAuthn assertion.
        if (saved.authorizedAt != null) return saved
        if (!passkeys.verify(operation.binding.accountId, decisionId, proof)) return null
        if (!decisions.authorize(decisionId, operation, proof.challenge, clock.now())) return null
        return decisions.get(decisionId, operation, clock.now())
    }
}
