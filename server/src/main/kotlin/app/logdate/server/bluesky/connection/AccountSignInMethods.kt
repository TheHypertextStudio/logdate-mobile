@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.bluesky.connection

import app.logdate.server.auth.AccountIdentityRepository
import app.logdate.server.auth.GoogleIdTokenVerifier
import app.logdate.server.auth.IdentityProvider
import app.logdate.server.passkeys.WebAuthnPasskeyService
import kotlin.uuid.Uuid

internal class AccountSignInMethods(
    private val passkeys: WebAuthnPasskeyService,
    private val identities: AccountIdentityRepository,
    private val google: GoogleIdTokenVerifier,
) {
    suspend fun hasUsableMethod(
        accountId: Uuid,
        excludingCredentialId: String? = null,
    ): Boolean {
        if (passkeys.getUserCredentials(accountId).any { it != excludingCredentialId }) return true
        if (!google.isConfigured()) return false
        return identities.findByAccountId(accountId).any { identity ->
            identity.accountId == accountId &&
                identity.provider == IdentityProvider.GOOGLE &&
                identity.providerSubject.isNotBlank() &&
                identities.findByProviderSubject(IdentityProvider.GOOGLE, identity.providerSubject)?.accountId == accountId
        }
    }
}
