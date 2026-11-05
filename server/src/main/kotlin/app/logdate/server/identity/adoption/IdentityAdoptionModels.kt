package app.logdate.server.identity.adoption

import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal enum class IdentityAdoptionPhase { PREPARING, FENCED, VERIFIED, COMMITTED, CANCELED }

@OptIn(ExperimentalUuidApi::class)
internal data class IdentityAdoptionBinding(
    val accountId: Uuid,
    val sourceDid: String,
    val targetDid: String,
    val deviceId: String,
    val sourceEpoch: Long,
)

internal data class IdentityAdoptionOperation(
    val id: String,
    val binding: IdentityAdoptionBinding,
    val revision: Long,
    val phase: IdentityAdoptionPhase,
    val leaseExpiresAt: Instant,
)

@OptIn(ExperimentalUuidApi::class)
internal data class IdentityAdoptionReceipt(
    val operationId: String,
    val accountId: Uuid,
    val sourceDid: String,
    val targetDid: String,
    val identityEpoch: Long,
    val committedAt: Instant,
)

internal data class IdentityAdoptionStatus(
    val operation: IdentityAdoptionOperation,
    val receipt: IdentityAdoptionReceipt?,
    val authorization: IdentityAdoptionAuthorizationStatus? = null,
)

internal enum class IdentityAdoptionRefusal {
    NOT_FOUND,
    SOURCE_INELIGIBLE,
    TARGET_CLAIMED,
    ACTIVE_OPERATION,
    STALE_REVISION,
    LEASE_EXPIRED,
    INVALID_TRANSITION,
    MUTATION_PENDING,
    PROOF_INVALID,
    PROOF_EXPIRED,
}

internal sealed interface IdentityAdoptionOutcome<out T> {
    data class Accepted<T>(
        val value: T,
    ) : IdentityAdoptionOutcome<T>

    data class Refused(
        val reason: IdentityAdoptionRefusal,
    ) : IdentityAdoptionOutcome<Nothing>
}

internal data class IdentityAdoptionAuthorizationStatus(
    val revision: Long,
    val deviceKey: IdentityAdoptionDeviceKey,
    val authorizedAt: Instant,
) {
    override fun toString(): String = "IdentityAdoptionAuthorizationStatus()"
}
