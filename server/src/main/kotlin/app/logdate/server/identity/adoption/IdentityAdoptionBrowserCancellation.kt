package app.logdate.server.identity.adoption

import kotlin.time.Instant

internal data class IdentityAdoptionBrowserCancellation(
    val binding: IdentityAdoptionOAuthBinding,
    val canceledAt: Instant,
) {
    override fun toString(): String = "IdentityAdoptionBrowserCancellation()"
}

internal interface IdentityAdoptionBrowserCancellations {
    suspend fun cancel(
        binding: IdentityAdoptionOAuthBinding,
        attemptId: String,
        verifier: String,
        now: Instant,
    ): IdentityAdoptionOutcome<IdentityAdoptionBrowserCancellation>
}
