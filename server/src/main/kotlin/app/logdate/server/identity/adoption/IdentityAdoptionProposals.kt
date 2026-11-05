@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.identity.adoption

import app.logdate.server.bluesky.BlueskySignInAttemptStore
import kotlin.time.Clock

/** A verified browser return allocates only a proposal; passkey/device authorization is separate. */
internal class IdentityAdoptionProposals(
    private val attempts: BlueskySignInAttemptStore,
    private val operations: IdentityAdoptionRepository,
    private val clock: Clock = Clock.System,
) {
    suspend fun exchange(
        binding: IdentityAdoptionOAuthBinding,
        attemptId: String,
        verifier: String,
    ): IdentityAdoptionOutcome<IdentityAdoptionOperation> {
        val proof =
            attempts.exchangeAdoption(attemptId, verifier, binding)
                ?: return IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.PROOF_INVALID)
        if (proof.binding != binding) return IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.PROOF_INVALID)
        val now = clock.now()
        if (proof.expiresAt <= now) return IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.PROOF_EXPIRED)
        return operations.beginForProposal(
            IdentityAdoptionBinding(binding.accountId, binding.sourceDid, proof.did.toString(), binding.deviceId, binding.sourceEpoch),
            binding.proposalId,
            now,
            proof.expiresAt,
        )
    }
}
