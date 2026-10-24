package app.logdate.client.permissions

import app.logdate.client.networking.EmailVerificationCompletion

/**
 * Pure mapping from the wire-level [EmailVerificationCompletion] returned by
 * `EmailVerificationApiClient.complete` to the platform-neutral [EmailVerificationOutcome]
 * surface exposed to UI.
 *
 * Lives in commonMain so it can be unit-tested without Robolectric — the rest of
 * `AndroidEmailVerificationManager` reaches into Credential Manager and is not portable.
 */
internal fun mapCompletionToOutcome(completion: EmailVerificationCompletion): EmailVerificationOutcome =
    when (completion) {
        is EmailVerificationCompletion.Success -> {
            EmailVerificationOutcome.Success(completion.email, completion.verifiedAt)
        }

        is EmailVerificationCompletion.Conflict -> {
            EmailVerificationOutcome.Conflict(completion.message)
        }

        is EmailVerificationCompletion.Failed -> {
            EmailVerificationOutcome.Failed(completion.reason)
        }
    }
