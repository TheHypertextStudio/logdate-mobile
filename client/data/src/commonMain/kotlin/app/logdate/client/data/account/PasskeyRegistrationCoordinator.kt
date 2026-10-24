package app.logdate.client.data.account

import app.logdate.client.datastore.OriginBoundSession
import app.logdate.client.datastore.SessionInstallationGuard
import app.logdate.client.datastore.SessionStorage
import app.logdate.client.datastore.UserSession
import app.logdate.client.device.PlatformAccountManager
import app.logdate.client.device.addAccountIfCurrent
import app.logdate.client.device.identity.CanonicalOwnerProvider
import app.logdate.client.device.identity.sameOwnerId
import app.logdate.client.networking.PasskeyApiClientContract
import app.logdate.client.permissions.PasskeyManager
import app.logdate.client.repository.account.AccountCreationRequest
import app.logdate.client.repository.account.LocalDataAdoptionRequiredException
import app.logdate.shared.config.LogDateConfigRepository
import app.logdate.shared.model.BeginAccountCreationRequest
import app.logdate.shared.model.BeginAuthenticationData
import app.logdate.shared.model.BeginAuthenticationRequest
import app.logdate.shared.model.CompleteAccountCreationRequest
import app.logdate.shared.model.CompleteAuthenticationData
import app.logdate.shared.model.CompleteAuthenticationRequest
import app.logdate.shared.model.LogDateAccount
import app.logdate.shared.model.PasskeyAuthenticationOptions
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first

/**
 * Creates LogDate Cloud accounts and authenticates existing ones using WebAuthn passkeys.
 *
 * Owns the two passkey ceremonies -- registering a brand new credential during account creation,
 * and asserting an existing one during sign-in -- and the session/platform-account bookkeeping
 * that follows each.
 */
internal class PasskeyRegistrationCoordinator(
    private val apiClient: PasskeyApiClientContract,
    private val passkeyManager: PasskeyManager,
    private val sessionStorage: SessionStorage,
    private val platformAccountManager: PlatformAccountManager,
    private val configRepository: LogDateConfigRepository,
    private val canonicalOwnerProvider: CanonicalOwnerProvider,
    private val bindingGuard: CanonicalOwnerBindingGuard,
    private val credentialCodec: PasskeyCredentialCodec,
    private val sessionState: PasskeyAccountSessionState,
    private val createRestoreKey: suspend () -> Result<Unit>,
    private val beforeSessionPersist: suspend (String, String) -> Unit = { _, _ -> },
    private val onSessionPersisted: () -> Unit = {},
    private val updateTokensOrRegisterPlatformAccount: suspend (
        account: LogDateAccount,
        accessToken: String,
        refreshToken: String,
        backendUrl: String,
    ) -> Unit,
    private val deviceName: () -> String?,
    private val accountKeyUnlockCoordinator: AccountKeyUnlockCoordinator? = null,
    /** Receives the server's proof that the person just confirmed with a passkey, when it sends one. */
    private val onPasskeyConfirmed: (accountId: String, grant: String) -> Unit = { _, _ -> },
) {
    suspend fun createAccountWithPasskey(request: AccountCreationRequest): Result<LogDateAccount> {
        return try {
            val origin = configRepository.getCurrentBackendUrl()
            val authenticationGuard = sessionStorage.captureAuthenticationGuard()
            check(!sessionStorage.supportsGuardedAuthentication || authenticationGuard != null) { "Account access is not ready" }
            check(authenticationGuard == null || authenticationGuard.origin == origin) { "Authentication server changed" }
            bindingGuard.requireCanonicalOwnerBinding()
            // Step 1: Begin account creation
            val canonicalOwnerId = canonicalOwnerProvider.getCanonicalOwnerId()
            val beginRequest =
                BeginAccountCreationRequest(
                    username = request.username,
                    displayName = request.displayName,
                    bio = request.bio,
                    originalBio = request.originalBio,
                    requestedOwnerId = canonicalOwnerId,
                )

            val beginData = apiClient.beginAccountCreation(beginRequest).getOrThrow()

            // Step 2: Create passkey using platform authenticator
            val credentialJson = passkeyManager.registerPasskey(beginData.registrationOptions).getOrThrow()
            val credential = credentialCodec.parseCredentialResponse(credentialJson)

            // Step 3: Complete account creation
            val completeRequest =
                CompleteAccountCreationRequest(
                    sessionToken = beginData.sessionToken,
                    credential = credential,
                    nickname = deviceName(),
                )

            val completeData = apiClient.completeAccountCreation(completeRequest).getOrThrow()
            if (!sameOwnerId(completeData.account.id.toString(), canonicalOwnerId)) {
                return Result.failure(CanonicalOwnerMismatchException())
            }

            // Step 4: Store session and account data
            persistSession(
                accessToken = completeData.tokens.accessToken,
                refreshToken = completeData.tokens.refreshToken,
                accountId = completeData.account.id.toString(),
                origin = origin,
                authenticationGuard = authenticationGuard,
            )

            // Step 5: Add account to platform account manager
            registerPlatformAccount(
                account = completeData.account,
                accessToken = completeData.tokens.accessToken,
                refreshToken = completeData.tokens.refreshToken,
                origin = origin,
            )

            publishAccount(completeData.account, completeData.tokens.accessToken, completeData.tokens.refreshToken, origin)
            completeData.stepUpGrant?.let { onPasskeyConfirmed(completeData.account.id.toString(), it) }

            // Android enrollment is durable and continues independently of this sign-up result.
            createRestoreKey().onFailure { error ->
                Napier.w("Account created but restore key registration failed — user has no recovery path", error)
            }

            Napier.i("Account created successfully for user: ${completeData.account.username}")
            Result.success(completeData.account)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Napier.w("Failed to create account with passkey", e)
            Result.failure(e)
        }
    }

    suspend fun authenticateWithPasskey(
        username: String?,
        adoptLocalData: Boolean,
    ): Result<LogDateAccount> {
        var unlockSecret: ByteArray? = null
        return try {
            val origin = configRepository.getCurrentBackendUrl()
            val authenticationGuard = sessionStorage.captureAuthenticationGuard()
            check(!sessionStorage.supportsGuardedAuthentication || authenticationGuard != null) { "Account access is not ready" }
            check(authenticationGuard == null || authenticationGuard.origin == origin) { "Authentication server changed" }
            bindingGuard.requireCanonicalOwnerBinding()
            val originalOwner = canonicalOwnerProvider.getCanonicalOwnerId()
            val apiBaseUrl = configRepository.apiBaseUrl.first()
            // Step 1: Begin authentication
            val beginRequest = BeginAuthenticationRequest(username = username)
            val beginData = apiClient.beginAuthentication(beginRequest).getOrThrow()

            // Step 2: Convert to PasskeyAuthenticationOptions
            val authOptions = beginData.toPasskeyAuthenticationOptions()

            // Step 3: Authenticate with passkey
            val localAssertion = passkeyManager.authenticateWithUnlock(authOptions).getOrThrow()
            unlockSecret = localAssertion.unlockSecret
            val assertion = credentialCodec.parseAssertionResponse(localAssertion.credentialJson)

            // Step 4: Complete authentication
            val completeData = apiClient.completeAuthentication(CompleteAuthenticationRequest(assertion, beginData.challenge)).getOrThrow()
            when (bindingGuard.ownerBindingFor(completeData.account, adoptLocalData)) {
                CanonicalOwnerBindingGuard.OwnerBinding.ALLOWED -> {
                    Unit
                }

                CanonicalOwnerBindingGuard.OwnerBinding.NEEDS_LOCAL_DATA_CONSENT -> {
                    return Result.failure(LocalDataAdoptionRequiredException())
                }

                CanonicalOwnerBindingGuard.OwnerBinding.REFUSED -> {
                    return Result.failure(CanonicalOwnerMismatchException())
                }
            }

            unlockAccountKeys(completeData, assertion.id, unlockSecret, originalOwner, apiBaseUrl)

            // Step 5: Store session and account data
            persistSession(
                accessToken = completeData.tokens.accessToken,
                refreshToken = completeData.tokens.refreshToken,
                accountId = completeData.account.id.toString(),
                origin = origin,
                authenticationGuard = authenticationGuard,
            )

            // Step 6: Update tokens in platform account manager (or register it, if this
            // device signed in to an existing LogDate Cloud account without ever running
            // the account-creation flow locally). Don't fail the entire operation since
            // authentication was already successful.
            updateTokensOrRegisterPlatformAccount(
                completeData.account,
                completeData.tokens.accessToken,
                completeData.tokens.refreshToken,
                origin,
            )

            publishAccount(completeData.account, completeData.tokens.accessToken, completeData.tokens.refreshToken, origin)
            completeData.stepUpGrant?.let { onPasskeyConfirmed(completeData.account.id.toString(), it) }

            Napier.i("Authentication successful for user: ${completeData.account.username}")
            Result.success(completeData.account)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Napier.w("Failed to authenticate with passkey", e)
            Result.failure(e)
        } finally {
            unlockSecret?.fill(0)
        }
    }

    private fun BeginAuthenticationData.toPasskeyAuthenticationOptions(): PasskeyAuthenticationOptions =
        PasskeyAuthenticationOptions(
            challenge = challenge,
            rpId = rpId,
            allowCredentials = allowCredentials.map { it.id },
            timeout = timeout,
        )

    private suspend fun unlockAccountKeys(
        completeData: CompleteAuthenticationData,
        credentialId: String,
        unlockSecret: ByteArray?,
        originalOwner: String?,
        apiBaseUrl: String,
    ) {
        require(configRepository.apiBaseUrl.first() == apiBaseUrl) { "The connected server changed during sign-in" }
        accountKeyUnlockCoordinator?.unlockOrProvision(
            apiBaseUrl = apiBaseUrl,
            accountId = completeData.account.id.toString(),
            accessToken = completeData.tokens.accessToken,
            credentialId = credentialId,
            unlockSecret = unlockSecret,
            mayPublishLocalKeys = sameOwnerId(originalOwner, completeData.account.id.toString()),
        )
    }

    private suspend fun registerPlatformAccount(
        account: LogDateAccount,
        accessToken: String,
        refreshToken: String,
        origin: String,
    ) {
        val platformResult =
            platformAccountManager.addAccountIfCurrent(
                account = account,
                accessToken = accessToken,
                refreshToken = refreshToken,
                backendUrl = origin,
                isCurrent = {
                    val current = sessionStorage.getOriginBoundSession()
                    configRepository.getCurrentBackendUrl() == origin &&
                        (current == null || current.origin == origin) &&
                        (current?.session ?: sessionStorage.getSession())?.let {
                            it.accountId == account.id.toString() && it.refreshToken == refreshToken
                        } == true
                },
            )

        if (platformResult.isFailure) {
            Napier.w("Failed to add account to platform account manager", platformResult.exceptionOrNull())
            // Don't fail the entire operation since the account was created successfully
        }
    }

    private suspend fun publishAccount(
        account: LogDateAccount,
        access: String,
        refresh: String,
        origin: String,
    ) {
        val expected = OriginBoundSession(origin, UserSession(access, refresh, account.id.toString()))
        check(sessionStorage.runIfSessionCurrent(expected) { sessionState.markAuthenticated(account, origin) }) { "Authentication changed" }
    }

    private suspend fun persistSession(
        accessToken: String,
        refreshToken: String,
        accountId: String,
        origin: String,
        authenticationGuard: SessionInstallationGuard?,
    ) {
        check(configRepository.getCurrentBackendUrl() == origin) { "Authentication server changed" }
        beforeSessionPersist(origin, accountId)
        currentCoroutineContext().ensureActive()
        check(configRepository.getCurrentBackendUrl() == origin) { "Authentication server changed" }
        val session = UserSession(accessToken, refreshToken, accountId)
        if (authenticationGuard == null) {
            sessionStorage.saveExplicitSession(session)
        } else {
            check(sessionStorage.installExplicitSessionIfCurrent(authenticationGuard, session)) { "Account access changed" }
        }
        onSessionPersisted()
        currentCoroutineContext().ensureActive()
    }
}
