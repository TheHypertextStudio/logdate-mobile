package app.logdate.server.identity.adoption

import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
internal data class IdentityWriteExpectation(
    val accountId: Uuid,
    val identityEpoch: Long,
    val operationId: String? = null,
)

internal enum class IdentityFenceOutcome { ALLOWED, HELD, CHANGED, DELETED }

internal interface AccountIdentityFence {
    fun lockAndCheck(
        expectation: IdentityWriteExpectation,
        now: Instant,
    ): IdentityFenceOutcome
}

internal class IdentityWriteRefusedException(
    val outcome: IdentityFenceOutcome,
) : RuntimeException("Identity write refused")
