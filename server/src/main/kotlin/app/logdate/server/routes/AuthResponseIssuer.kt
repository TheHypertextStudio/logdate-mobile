package app.logdate.server.routes

import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountIdentityRepository
import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.AccountSessionUnavailableException
import app.logdate.server.auth.TokenService
import app.logdate.server.auth.ensureLogDateSessionIdentity
import app.logdate.server.identity.AtprotoIdentityService
import app.logdate.server.passkeys.WebAuthnPasskeyService
import app.logdate.shared.model.AccountIdentityKind
import app.logdate.shared.model.AccountTokens
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
internal suspend fun issueAuthResponse(
    account: Account,
    accountRepository: AccountRepository,
    identityRepository: AccountIdentityRepository,
    webAuthnService: WebAuthnPasskeyService,
    atprotoIdentityService: AtprotoIdentityService,
    tokenService: TokenService,
    deviceSessionId: String? = null,
    passkeyConfirmed: Boolean = false,
): AuthResponse {
    val current = accountRepository.findById(account.id) ?: throw AccountSessionUnavailableException()
    val fresh = ensureLogDateSessionIdentity(current, atprotoIdentityService) ?: throw AccountSessionUnavailableException()
    val identities = identityRepository.findByAccountId(fresh.id)
    val linkedProviders = identities.map { it.provider.name.lowercase() }.distinct().sorted()
    val passkeyCredentialIds = webAuthnService.getPasskeysForUser(fresh.id).map { it.credentialId }

    val accountId = fresh.id.toString()
    // A linked device stays on its own session and scope, so this call can never turn it into a full sign-in.
    val tokens =
        if (deviceSessionId == null) {
            AccountTokens(
                accessToken = tokenService.generateAccessToken(accountId, fresh.did),
                refreshToken = tokenService.generateRefreshToken(accountId, fresh.did),
            )
        } else {
            AccountTokens(
                accessToken = tokenService.generateDeviceAccessToken(accountId, fresh.did, deviceSessionId),
                refreshToken = tokenService.generateDeviceRefreshToken(accountId, fresh.did, deviceSessionId),
            )
        }

    return AuthResponse(
        success = true,
        data =
            AuthResponseData(
                account = authAccountView(fresh, linkedProviders, passkeyCredentialIds),
                tokens = tokens,
                stepUpGrant =
                    if (passkeyConfirmed &&
                        fresh.identityKind == AccountIdentityKind.HOSTED &&
                        atprotoIdentityService.config.supportsRecoveryKeys
                    ) {
                        issueStepUpGrant(webAuthnService, fresh.id)
                    } else {
                        null
                    },
            ),
    )
}

/**
 * A step-up grant for [accountId], or `null` when one cannot be stored. The sign-in itself has
 * already succeeded, so it goes ahead without a grant; the app then asks for the passkey again
 * before registering a recovery key.
 */
@OptIn(ExperimentalUuidApi::class)
private fun issueStepUpGrant(
    webAuthnService: WebAuthnPasskeyService,
    accountId: Uuid,
): String? =
    try {
        webAuthnService.issueStepUpGrant(accountId)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Napier.e("Could not issue a step-up grant after a passkey sign-in", error)
        null
    }

@OptIn(ExperimentalUuidApi::class)
private fun authAccountView(
    account: Account,
    linkedProviders: List<String>,
    passkeyCredentialIds: List<String>,
): AuthAccountView =
    AuthAccountView(
        id = account.id.toString(),
        username = account.username,
        displayName = account.displayName,
        did = account.did,
        handle = account.handle,
        bio = account.bio,
        originalBio = account.originalBio,
        email = account.email,
        emailVerified = account.emailVerified,
        emailVerifiedAt = account.emailVerifiedAt?.toString(),
        linkedProviders = linkedProviders,
        passkeyCredentialIds = passkeyCredentialIds,
        createdAt = account.createdAt.toString(),
        updatedAt = (account.lastSignInAt ?: account.createdAt).toString(),
        storageMode = account.storageMode,
        identityKind = account.identityKind,
    )
