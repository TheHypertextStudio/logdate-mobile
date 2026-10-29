package app.logdate.server.auth

import app.logdate.server.identity.AtprotoIdentityService
import app.logdate.shared.model.AccountIdentityKind
import app.logdate.shared.model.AccountStorageMode
import studio.hypertext.atproto.identity.AtprotoDid

/** A LogDate session may use an external DID without owning its public repository or signing keys. */
internal fun Account.hasSupportedSessionIdentity(): Boolean =
    isActive &&
        when (identityKind) {
            AccountIdentityKind.HOSTED -> true
            AccountIdentityKind.EXTERNAL ->
                storageMode == AccountStorageMode.PERSONAL_SPACE_V1 &&
                    did?.let { AtprotoDid.parse(it).isSuccess } == true &&
                    signingKeyPublic == null &&
                    plcRecoveryDidKey == null &&
                    publicIdentityDid == null
            AccountIdentityKind.UNRECOGNIZED -> false
        }

internal suspend fun ensureLogDateSessionIdentity(
    account: Account,
    hostedIdentities: AtprotoIdentityService,
): Account? {
    if (!account.hasSupportedSessionIdentity()) return null
    return when (account.identityKind) {
        AccountIdentityKind.HOSTED -> hostedIdentities.ensureIdentity(account).takeIf { it.hasSupportedSessionIdentity() }
        AccountIdentityKind.EXTERNAL -> account
        AccountIdentityKind.UNRECOGNIZED -> null
    }
}

internal class AccountSessionUnavailableException : IllegalStateException("This account cannot start a LogDate session")
