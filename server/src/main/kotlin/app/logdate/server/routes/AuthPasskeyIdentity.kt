@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.routes

import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountIdentity
import app.logdate.server.auth.AccountIdentityRepository
import app.logdate.server.auth.IdentityProvider
import kotlin.time.Clock
import kotlin.uuid.Uuid

internal suspend fun recordPasskeySignIn(
    account: Account,
    passkeySubject: String,
    identityRepository: AccountIdentityRepository,
) {
    val existingPasskeyIdentity =
        identityRepository.findByProviderSubject(IdentityProvider.PASSKEY, passkeySubject)
    if (existingPasskeyIdentity == null) {
        identityRepository.save(
            AccountIdentity(
                id = Uuid.random(),
                accountId = account.id,
                provider = IdentityProvider.PASSKEY,
                providerSubject = passkeySubject,
                email = account.email,
                emailVerified = account.emailVerified,
                createdAt = Clock.System.now(),
                lastSignInAt = Clock.System.now(),
            ),
        )
    } else {
        identityRepository.touchLastSignIn(existingPasskeyIdentity.id)
    }
}
