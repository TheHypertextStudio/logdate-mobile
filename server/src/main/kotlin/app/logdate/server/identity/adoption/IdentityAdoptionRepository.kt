package app.logdate.server.identity.adoption

import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
internal interface IdentityAdoptionRepository {
    /** Only the verified adoption handoff may supply its original provider proof deadline. */
    suspend fun beginForProposal(
        binding: IdentityAdoptionBinding,
        proposalId: Uuid,
        now: Instant,
        proofExpiresAt: Instant? = null,
    ): IdentityAdoptionOutcome<IdentityAdoptionOperation> = IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.SOURCE_INELIGIBLE)

    suspend fun begin(
        binding: IdentityAdoptionBinding,
        now: Instant,
    ): IdentityAdoptionOutcome<IdentityAdoptionOperation>

    suspend fun get(
        accountId: Uuid,
        operationId: String,
    ): IdentityAdoptionOperation?

    /** One coherent observation; reads do not renew or release holds. */
    suspend fun status(
        accountId: Uuid,
        operationId: String,
    ): IdentityAdoptionStatus? = null

    /** Committed authority matching the current persisted account, for other-device recovery. */
    suspend fun currentCommitted(accountId: Uuid): IdentityAdoptionStatus? = null

    suspend fun transition(
        accountId: Uuid,
        operationId: String,
        expectedRevision: Long,
        nextPhase: IdentityAdoptionPhase,
        now: Instant,
    ): IdentityAdoptionOutcome<IdentityAdoptionOperation>

    suspend fun receipt(
        accountId: Uuid,
        operationId: String,
    ): IdentityAdoptionReceipt?
}
