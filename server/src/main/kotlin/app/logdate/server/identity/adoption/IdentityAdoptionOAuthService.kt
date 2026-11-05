package app.logdate.server.identity.adoption

import app.logdate.server.bluesky.BlueskySignInAttemptStore
import app.logdate.server.bluesky.BlueskySignInProvider
import studio.hypertext.atproto.oauth.client.JvmOAuthCrypto
import studio.hypertext.atproto.syntax.Handle
import java.util.Base64
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

internal data class IdentityAdoptionOAuthStart(
    val binding: IdentityAdoptionOAuthBinding,
    val attemptId: String,
    val authorizationUrl: String,
    val expiresAt: Instant,
) {
    override fun toString(): String = "IdentityAdoptionOAuthStart()"
}

/** OAuth proposes an external identity; it grants no journal, passkey or cutover authority. */
internal class IdentityAdoptionOAuthService(
    private val provider: BlueskySignInProvider,
    private val attempts: BlueskySignInAttemptStore,
    private val operations: IdentityAdoptionRepository,
    private val cancellations: IdentityAdoptionBrowserCancellations,
    private val clock: Clock = Clock.System,
) {
    suspend fun start(
        binding: IdentityAdoptionOAuthBinding,
        handle: Handle,
        challenge: String,
    ): IdentityAdoptionOAuthStart? {
        if (!challenge.matches(Regex("[A-Za-z0-9_-]{43}"))) return null
        val expiresAt = clock.now() + 5.minutes
        val id = Base64.getUrlEncoder().withoutPadding().encodeToString(JvmOAuthCrypto.randomBytes(32))
        attempts.createAdoption(id, challenge, expiresAt, binding)
        return IdentityAdoptionOAuthStart(binding, id, provider.authorize(handle, id), expiresAt)
    }

    suspend fun cancelBrowser(
        binding: IdentityAdoptionOAuthBinding,
        attemptId: String,
        verifier: String,
    ): IdentityAdoptionOutcome<IdentityAdoptionBrowserCancellation> = cancellations.cancel(binding, attemptId, verifier, clock.now())

    suspend fun exchange(
        binding: IdentityAdoptionOAuthBinding,
        attemptId: String,
        verifier: String,
    ): IdentityAdoptionOutcome<IdentityAdoptionOperation> =
        IdentityAdoptionProposals(attempts, operations, clock).exchange(binding, attemptId, verifier)
}
