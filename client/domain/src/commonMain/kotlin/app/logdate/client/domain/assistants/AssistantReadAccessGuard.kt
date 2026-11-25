package app.logdate.client.domain.assistants

import app.logdate.client.repository.assistants.AssistantAccessSnapshot
import app.logdate.client.repository.assistants.AssistantCapability
import app.logdate.client.repository.assistants.AssistantGrant
import app.logdate.client.repository.assistants.AssistantRequestAuthority
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.time.Instant
import kotlin.uuid.Uuid

sealed interface AssistantReadResult<out T> {
    class Released<T>(
        val value: T,
    ) : AssistantReadResult<T>

    data class Denied(
        val reason: AssistantAccessDenial,
    ) : AssistantReadResult<Nothing>
}

/** Read-result release only. This does not authorize or wrap persistence. */
class AssistantReadAccessGuard(
    private val snapshot: suspend (Uuid) -> AssistantAccessSnapshot,
    private val now: () -> Instant?,
) {
    private val policy = AssistantAccessPolicy()

    suspend fun <T> read(
        request: AssistantRequestAuthority,
        operation: suspend (AssistantGrant) -> T,
    ): AssistantReadResult<T> {
        currentCoroutineContext().ensureActive()
        val before = snapshot(request.grantId)
        val initial = policy.authorize(before, request, AssistantCapability.READ_MEMORIES, now())
        if (initial is AssistantAccessDecision.Denied) return AssistantReadResult.Denied(initial.reason)
        val grant = checkNotNull(before.grant).frozen()
        val value = operation(grant)
        currentCoroutineContext().ensureActive()
        val after = snapshot(request.grantId)
        val final = policy.authorize(after, request, AssistantCapability.READ_MEMORIES, now())
        if (final is AssistantAccessDecision.Denied) return AssistantReadResult.Denied(final.reason)
        if (after.grant != grant || after.device.accessEpoch != before.device.accessEpoch) {
            return AssistantReadResult.Denied(AssistantAccessDenial.INVALID_AUTHORITY)
        }
        currentCoroutineContext().ensureActive()
        return AssistantReadResult.Released(value)
    }

    private fun AssistantGrant.frozen() =
        copy(
            capabilities = capabilities.toSet(),
            contentScope =
                contentScope.copy(
                    journalIds = contentScope.journalIds.toSet(),
                    excludedEntryIds = contentScope.excludedEntryIds.toSet(),
                ),
        )
}
