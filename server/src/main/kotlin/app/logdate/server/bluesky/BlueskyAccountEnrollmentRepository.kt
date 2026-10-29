package app.logdate.server.bluesky

import app.logdate.server.auth.Account
import app.logdate.server.passkeys.WebAuthnPasskeyService

internal fun interface BlueskyAccountEnrollmentRepository {
    /** Atomically creates the account, verified passkey and passkey identity; conflicts change nothing. */
    suspend fun create(
        account: Account,
        passkey: WebAuthnPasskeyService.VerifiedRegistration,
    ): Boolean
}
