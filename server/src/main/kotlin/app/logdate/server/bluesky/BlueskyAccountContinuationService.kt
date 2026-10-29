package app.logdate.server.bluesky

import app.logdate.server.auth.AccountRepository
import app.logdate.shared.model.AccountIdentityKind
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.ExperimentalUuidApi

internal enum class BlueskyAccountAction { CREATE_ACCOUNT, CONFIRM_PASSKEY }

internal data class BlueskyAccountContinuation(
    val token: String,
    val action: BlueskyAccountAction,
) {
    override fun toString(): String = "BlueskyAccountContinuation(action=$action)"
}

/** Provider proof chooses an account path; it never grants a LogDate session or journal keys. */
@OptIn(ExperimentalUuidApi::class)
internal class BlueskyAccountContinuationService(
    private val signIn: BlueskySignInService,
    private val accounts: AccountRepository,
    private val handoffs: BlueskyAccountHandoffStore,
    private val clock: Clock = Clock.System,
) {
    suspend fun exchange(
        attemptId: String,
        verifier: String,
    ): BlueskyAccountContinuation? =
        try {
            val proof = signIn.exchange(attemptId, verifier)
            if (proof == null || proof.accountId != null) {
                null
            } else {
                val account = accounts.findByDid(proof.did.toString())
                if (account != null &&
                    (!account.isActive || account.moderation != null || account.identityKind == AccountIdentityKind.UNRECOGNIZED)
                ) {
                    null
                } else {
                    val handoff = BlueskyAccountHandoff.Identity(proof.did, account?.id?.toString())
                    val token = handoffs.issue(handoff, clock.now() + 5.minutes)
                    val action = if (account == null) BlueskyAccountAction.CREATE_ACCOUNT else BlueskyAccountAction.CONFIRM_PASSKEY
                    BlueskyAccountContinuation(token, action)
                }
            }
        } catch (canceled: CancellationException) {
            throw canceled
        } catch (failure: BlueskySignInFailure) {
            throw BlueskySignInFailure(failure.kind)
        } catch (_: Exception) {
            throw BlueskySignInFailure(BlueskySignInFailureKind.UNAVAILABLE)
        }
}
