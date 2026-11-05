package app.logdate.server.identity.adoption

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal class AccountWriteOrigin(
    val expectation: IdentityWriteExpectation,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AccountWriteOrigin>
}

@OptIn(ExperimentalUuidApi::class)
internal suspend fun <T> withAccountWriteOrigin(
    accountId: Uuid,
    identityEpoch: Long = 0,
    action: suspend () -> T,
): T {
    val origin = IdentityWriteExpectation(accountId, identityEpoch)
    val current = currentCoroutineContext()[AccountWriteOrigin]?.expectation
    if (current != null && current != origin) throw IdentityWriteRefusedException(IdentityFenceOutcome.CHANGED)
    return withContext(AccountWriteOrigin(origin)) { action() }
}
