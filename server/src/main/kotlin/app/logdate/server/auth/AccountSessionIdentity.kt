package app.logdate.server.auth

import app.logdate.server.identity.AtprotoIdentityService
import app.logdate.shared.model.AccountIdentityKind
import app.logdate.shared.model.AccountStorageMode
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.syntax.Handle
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

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

@OptIn(ExperimentalUuidApi::class)
internal suspend fun ensureLogDateSessionIdentity(
    accountId: Uuid,
    accounts: AccountRepository,
    hostedIdentities: AtprotoIdentityService,
): Account? {
    val account = accounts.findById(accountId) ?: return null
    if (!account.hasSupportedSessionIdentity()) return null
    if (account.identityKind == AccountIdentityKind.HOSTED && account.needsHostedMetadataRepair()) {
        return account.takeIf { it.moderation == null }
    }
    return when (account.identityKind) {
        AccountIdentityKind.HOSTED -> hostedIdentities.ensureIdentity(account).takeIf { it.hasSupportedSessionIdentity() }
        AccountIdentityKind.EXTERNAL -> account
        AccountIdentityKind.UNRECOGNIZED -> null
    }
}

/** Journal sessions need the owned DID, not public discovery metadata or a hosted signing key. */
private fun Account.needsHostedMetadataRepair(): Boolean {
    if (did?.let { AtprotoDid.parse(it).isSuccess } != true) return false
    val usableHandle =
        handle
            ?.trim()
            ?.trim('.')
            ?.lowercase()
            ?.let { Handle.parse(it).isSuccess } == true
    return !usableHandle || signingKeyPublic.isNullOrBlank()
}

internal class AccountSessionUnavailableException : IllegalStateException("This account cannot start a LogDate session")
