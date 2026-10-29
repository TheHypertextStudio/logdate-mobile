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
import app.logdate.shared.model.AccountStorageMode
import app.logdate.shared.model.AccountTokens
import app.logdate.shared.model.BeginAccountCreationRequest
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
    private val accountKeyUnlockCoordinator: AccountKeyAccess? = null,
    /** Receives the server's proof that the person just confirmed with a passkey, when it sends one. */
    private val onPasskeyConfirmed: (accountId: String, grant: String) -> Unit = { _, _ -> },
) {
    suspend fun createAccountWithPasskey(
        request: AccountCreationRequest,
        ceremony: PasskeyCeremonyApi? = null,
    ): Result<LogDateAccount> {
        return try {
            val origin = configRepository.getCurrentBackendUrl()
            check(ceremony == null || ceremony.backendOrigin == origin) { "Authentication server changed" }
            val authenticationGuard = sessionStorage.captureAuthenticationGuard()
            check(!sessionStorage.supportsGuardedAuthentication || authenticationGuard != null) { "Account access is not ready" }
            check(authenticationGuard == null || authenticationGuard.origin == origin) { "Authentication server changed" }
            bindingGuard.requireCanonicalOwnerBinding()
            // Step 1: Begin account creation
            val canonicalOwnerId = canonicalOwnerProvider.getCanonicalOwnerId()
            requireOriginalOwner(ceremony, canonicalOwnerId)
            val beginRequest =
                BeginAccountCreationRequest(
                    username = request.username,
                    displayName = request.displayName,
                    bio = request.bio,
                    originalBio = request.originalBio,
                    requestedOwnerId = canonicalOwnerId,
                )

            val beginData = (ceremony?.beginRegistration(beginRequest) ?: apiClient.beginAccountCreation(beginRequest)).getOrThrow()

            requireCeremonyCurrent(origin, authenticationGuard, canonicalOwnerId)

            // Step 2: Create passkey using platform authenticator
            val credentialJson = passkeyManager.registerPasskey(beginData.registrationOptions).getOrThrow()
            val credential = credentialCodec.parseCredentialResponse(credentialJson)

            requireCeremonyCurrent(origin, authenticationGuard, canonicalOwnerId)

            // Step 3: Complete account creation
            val completeRequest =
                CompleteAccountCreationRequest(
                    sessionToken = beginData.sessionToken,
                    credential = credential,
                    nickname = deviceName(),
                )

            val completeData =
                (
                    ceremony?.completeRegistration(
                        completeRequest,
                    ) ?: apiClient.completeAccountCreation(completeRequest)
                ).getOrThrow()
            requireCeremonyCurrent(origin, authenticationGuard, canonicalOwnerId)
            if (!sameOwnerId(completeData.account.id.toString(), canonicalOwnerId)) {
                return Result.failure(CanonicalOwnerMismatchException())
            }

            if (completeData.account.storageMode == AccountStorageMode.PERSONAL_SPACE_V1 &&
                accountKeyUnlockCoordinator?.bindLocalKeysForNewAccount(completeData.account.id.toString()) != true
            ) {
                throw AccountKeysNotReadyException()
            }
            finishSession(completeData.account, completeData.tokens, origin, authenticationGuard, createPlatformAccount = true)
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
            Napier.w("Failed to create account with passkey")
            Result.failure(e)
        }
    }

    suspend fun authenticateWithPasskey(
        username: String?,
        adoptLocalData: Boolean,
        ceremony: PasskeyCeremonyApi? = null,
    ): Result<LogDateAccount> {
        var unlockSecret: ByteArray? = null
        return try {
            val origin = configRepository.getCurrentBackendUrl()
            check(ceremony == null || ceremony.backendOrigin == origin) { "Authentication server changed" }
            val authenticationGuard = sessionStorage.captureAuthenticationGuard()
            check(!sessionStorage.supportsGuardedAuthentication || authenticationGuard != null) { "Account access is not ready" }
            check(authenticationGuard == null || authenticationGuard.origin == origin) { "Authentication server changed" }
            bindingGuard.requireCanonicalOwnerBinding()
            if (!adoptLocalData && bindingGuard.localDataConsentRequired()) throw LocalDataAdoptionRequiredException()
            val originalOwner = canonicalOwnerProvider.getCanonicalOwnerId()
            requireOriginalOwner(ceremony, originalOwner)
            val apiBaseUrl = configRepository.apiBaseUrl.first()
            // Step 1: Begin authentication
            val beginRequest = BeginAuthenticationRequest(username = username)
            val beginData = (ceremony?.beginAuthentication(beginRequest) ?: apiClient.beginAuthentication(beginRequest)).getOrThrow()

            // Step 2: Convert to PasskeyAuthenticationOptions
            val authOptions =
                PasskeyAuthenticationOptions(
                    challenge = beginData.challenge,
                    rpId = beginData.rpId,
                    allowCredentials = beginData.allowCredentials.map { it.id },
                    timeout = beginData.timeout,
                )

            requireCeremonyCurrent(origin, authenticationGuard, originalOwner)

            // Step 3: Authenticate with passkey
            val localAssertion = passkeyManager.authenticateWithUnlock(authOptions).getOrThrow()
            unlockSecret = localAssertion.unlockSecret
            val assertion = credentialCodec.parseAssertionResponse(localAssertion.credentialJson)

            requireCeremonyCurrent(origin, authenticationGuard, originalOwner)

            // Step 4: Complete authentication
            val completeRequest = CompleteAuthenticationRequest(assertion, beginData.challenge)
            val completeData =
                (
                    ceremony?.completeAuthentication(
                        completeRequest,
                    ) ?: apiClient.completeAuthentication(completeRequest)
                ).getOrThrow()
            requireCeremonyCurrent(origin, authenticationGuard, originalOwner)
            prepareAccountAccess(completeData, assertion.id, unlockSecret, originalOwner, apiBaseUrl, adoptLocalData)

            finishSession(completeData.account, completeData.tokens, origin, authenticationGuard, createPlatformAccount = false)
            completeData.stepUpGrant?.let { onPasskeyConfirmed(completeData.account.id.toString(), it) }

            Napier.i("Authentication successful for user: ${completeData.account.username}")
            Result.success(completeData.account)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Napier.w("Failed to authenticate with passkey")
            Result.failure(e)
        } finally {
            unlockSecret?.fill(0)
        }
    }

    private suspend fun prepareAccountAccess(
        data: CompleteAuthenticationData,
        credentialId: String,
        secret: ByteArray?,
        originalOwner: String,
        apiBaseUrl: String,
        adoptLocalData: Boolean,
    ) {
        val personal = data.account.storageMode == AccountStorageMode.PERSONAL_SPACE_V1
        if (personal) {
            requireOwnerAllowed(bindingGuard.inspectOwnerBindingFor(data.account, adoptLocalData))
            if (unlockAccountKeys(data, credentialId, secret, originalOwner, apiBaseUrl) != AccountKeyUnlockStatus.UNLOCKED) {
                throw AccountKeysNotReadyException()
            }
        }
        requireOwnerAllowed(bindingGuard.ownerBindingFor(data.account, adoptLocalData))
        if (!personal) unlockAccountKeys(data, credentialId, secret, originalOwner, apiBaseUrl)
    }

    private fun requireOwnerAllowed(binding: CanonicalOwnerBindingGuard.OwnerBinding) {
        when (binding) {
            CanonicalOwnerBindingGuard.OwnerBinding.ALLOWED -> Unit
            CanonicalOwnerBindingGuard.OwnerBinding.NEEDS_LOCAL_DATA_CONSENT -> throw LocalDataAdoptionRequiredException()
            CanonicalOwnerBindingGuard.OwnerBinding.REFUSED -> throw CanonicalOwnerMismatchException()
        }
    }

    private suspend fun finishSession(
        account: LogDateAccount,
        tokens: AccountTokens,
        origin: String,
        authenticationGuard: SessionInstallationGuard?,
        createPlatformAccount: Boolean,
    ) {
        persistSession(tokens.accessToken, tokens.refreshToken, account.id.toString(), origin, authenticationGuard)
        if (createPlatformAccount) {
            registerPlatformAccount(account, tokens.accessToken, tokens.refreshToken, origin)
        } else {
            updateTokensOrRegisterPlatformAccount(account, tokens.accessToken, tokens.refreshToken, origin)
        }
        publishAccount(account, tokens.accessToken, tokens.refreshToken, origin)
    }

    private fun requireOriginalOwner(
        ceremony: PasskeyCeremonyApi?,
        currentOwner: String,
    ) {
        check(ceremony?.originalOwnerId?.let { sameOwnerId(it, currentOwner) } != false) { "Journal ownership changed" }
    }

    private suspend fun requireCeremonyCurrent(
        origin: String,
        authenticationGuard: SessionInstallationGuard?,
        ownerId: String,
    ) {
        currentCoroutineContext().ensureActive()
        check(configRepository.getCurrentBackendUrl() == origin) { "Authentication server changed" }
        check(sameOwnerId(canonicalOwnerProvider.getCanonicalOwnerId(), ownerId)) { "Journal ownership changed" }
        check(authenticationGuard == null || sessionStorage.isAuthenticationGuardCurrent(authenticationGuard)) { "Account access changed" }
    }

    private suspend fun unlockAccountKeys(
        completeData: CompleteAuthenticationData,
        credentialId: String,
        unlockSecret: ByteArray?,
        originalOwner: String?,
        apiBaseUrl: String,
    ): AccountKeyUnlockStatus {
        require(configRepository.apiBaseUrl.first() == apiBaseUrl) { "The connected server changed during sign-in" }
        val access = accountKeyUnlockCoordinator ?: return AccountKeyUnlockStatus.UNAVAILABLE
        val status =
            access.unlockOrProvision(
                apiBaseUrl = apiBaseUrl,
                accountId = completeData.account.id.toString(),
                accessToken = completeData.tokens.accessToken,
                credentialId = credentialId,
                unlockSecret = unlockSecret,
                mayPublishLocalKeys = sameOwnerId(originalOwner, completeData.account.id.toString()),
            )
        return if (status != AccountKeyUnlockStatus.UNLOCKED && access.hasLocalAccountKeys(completeData.account.id.toString())) {
            AccountKeyUnlockStatus.UNLOCKED
        } else {
            status
        }
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
