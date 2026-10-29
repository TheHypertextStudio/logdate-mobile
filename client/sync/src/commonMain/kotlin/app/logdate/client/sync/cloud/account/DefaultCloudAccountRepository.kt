package app.logdate.client.sync.cloud.account

import app.logdate.client.datastore.KeyValueStorage
import app.logdate.client.datastore.SessionStorage
import app.logdate.client.sync.cloud.CloudApiClient
import app.logdate.client.sync.cloud.CloudRequestBinding
import app.logdate.client.sync.cloud.CloudRequestLocation
import app.logdate.client.sync.cloud.CloudRequestLocationProvider
import app.logdate.client.sync.cloud.RefreshedSession
import app.logdate.client.sync.cloud.SessionRefreshingAccountRepository
import app.logdate.client.util.platformIODispatcher
import app.logdate.shared.config.DefaultLogDateConfigRepository
import app.logdate.shared.config.LogDateConfigRepository
import app.logdate.shared.model.AccountCredentials
import app.logdate.shared.model.AccountIdentityKind
import app.logdate.shared.model.AccountStorageMode
import app.logdate.shared.model.AuthenticationResult
import app.logdate.shared.model.BeginAccountCreationRequest
import app.logdate.shared.model.BeginAccountCreationResult
import app.logdate.shared.model.CloudAccount
import app.logdate.shared.model.CloudAccountRepository
import app.logdate.shared.model.CompleteAccountCreationRequest
import app.logdate.shared.model.DeviceInfo
import app.logdate.shared.model.PasskeyAuthenticatorResponse
import app.logdate.shared.model.PasskeyCredential
import app.logdate.shared.model.PasskeyCredentialResponse
import app.logdate.shared.model.toCloudAccount
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Default implementation of [CloudAccountRepository].
 *
 * This implementation uses the [CloudApiClient] for network operations and
 * keeps account metadata in [KeyValueStorage]. Credentials belong to [SessionStorage].
 *
 * @param apiClient The client for LogDate Cloud API communication.
 * @param accountMetadata Non-secret account metadata retained for offline use.
 */
class DefaultCloudAccountRepository(
    private val apiClient: CloudApiClient,
    private val accountMetadata: KeyValueStorage,
    private val configRepository: LogDateConfigRepository = DefaultLogDateConfigRepository(),
    private val coroutineScope: CoroutineScope = CoroutineScope(platformIODispatcher),
    private val sessionStorage: SessionStorage,
) : CloudAccountRepository,
    CloudRequestLocationProvider,
    SessionRefreshingAccountRepository {
    override fun captureLocation(): CloudRequestLocation {
        val origin = configRepository.getCurrentBackendUrl()
        val apiBaseUrl = configRepository.getCurrentApiBaseUrl()
        check(configRepository.getCurrentBackendUrl() == origin) { "Cloud request origin changed" }
        return CloudRequestLocation(origin, apiBaseUrl)
    }

    override fun isCurrentOrigin(origin: String): Boolean = configRepository.getCurrentBackendUrl() == origin

    private val accountFlow = MutableStateFlow<CloudAccount?>(null)

    init {
        coroutineScope.launch {
            combine(configRepository.backendUrl, sessionStorage.getSessionFlow()) { origin, _ -> origin }
                .collectLatest {
                    accountFlow.value = null
                    loadStoredAccount()
                }
        }
    }

    /**
     * Loads metadata only for the current canonical secure session.
     */
    private suspend fun loadStoredAccount() {
        try {
            val backendUrl = configRepository.getCurrentBackendUrl()
            accountMetadata.migrateLegacyAccountMetadata(backendUrl)

            // Check if we have stored credentials
            val session = sessionStorage.getOriginBoundSession()?.takeIf { it.origin == backendUrl }
            val accountId = accountMetadata.getString(cloudAccountScopedKey(CloudAccountStorageKeys.ACCOUNT_ID, backendUrl))
            val username = accountMetadata.getString(cloudAccountScopedKey(CloudAccountStorageKeys.ACCOUNT_USERNAME, backendUrl))
            val displayName = accountMetadata.getString(cloudAccountScopedKey(CloudAccountStorageKeys.ACCOUNT_DISPLAY_NAME, backendUrl))
            val bio = accountMetadata.getString(cloudAccountScopedKey(CloudAccountStorageKeys.ACCOUNT_BIO, backendUrl))
            val originalBio = accountMetadata.getString(cloudAccountScopedKey(CloudAccountStorageKeys.ACCOUNT_ORIGINAL_BIO, backendUrl))
            val did = accountMetadata.getString(cloudAccountScopedKey(CloudAccountStorageKeys.ACCOUNT_DID, backendUrl))
            val handle = accountMetadata.getString(cloudAccountScopedKey(CloudAccountStorageKeys.ACCOUNT_HANDLE, backendUrl))
            val userIdString = accountMetadata.getString(cloudAccountScopedKey(CloudAccountStorageKeys.USER_ID, backendUrl))
            val createdAtString = accountMetadata.getString(cloudAccountScopedKey(CloudAccountStorageKeys.CREATED_AT, backendUrl))
            val updatedAtString = accountMetadata.getString(cloudAccountScopedKey(CloudAccountStorageKeys.UPDATED_AT, backendUrl))
            val passkeyIdsString = accountMetadata.getString(cloudAccountScopedKey(CloudAccountStorageKeys.PASSKEY_IDS, backendUrl))
            val storageMode = accountMetadata.getString(cloudAccountScopedKey(CloudAccountStorageKeys.ACCOUNT_STORAGE_MODE, backendUrl))
            val identityKind = accountMetadata.getString(cloudAccountScopedKey(CloudAccountStorageKeys.ACCOUNT_IDENTITY_KIND, backendUrl))

            if (session != null &&
                accountId == session.session.accountId &&
                username != null &&
                displayName != null &&
                userIdString != null &&
                createdAtString != null &&
                updatedAtString != null
            ) {
                val userId = Uuid.parse(userIdString)
                val createdAt = Instant.parse(createdAtString)
                val updatedAt = Instant.parse(updatedAtString)
                // Convert String IDs to UUIDs
                val account =
                    CloudAccount(
                        id = Uuid.parse(checkNotNull(accountId)),
                        username = username,
                        displayName = displayName,
                        bio = bio,
                        originalBio = originalBio,
                        did = did,
                        handle = handle,
                        userId = userId,
                        createdAt = createdAt,
                        updatedAt = updatedAt,
                        passkeyCredentialIds = parseCloudPasskeyIds(passkeyIdsString),
                        storageMode = storageMode?.let(AccountStorageMode::fromWireValue) ?: AccountStorageMode.UNRECOGNIZED,
                        identityKind = identityKind?.let(AccountIdentityKind::fromWireValue) ?: AccountIdentityKind.UNRECOGNIZED,
                    )

                sessionStorage.runIfSessionCurrent(session) {
                    if (isCurrentOrigin(backendUrl)) accountFlow.value = account
                }
                Napier.d("Loaded stored account")
            } else {
                accountFlow.value = null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Napier.e("Failed to load stored account")
            // If loading fails, we just keep the account as null
        }
    }

    /**
     * Checks if a username is available for registration.
     *
     * @param username The username to check.
     * @return True if the username is available, false otherwise.
     */
    override suspend fun isUsernameAvailable(username: String): Result<Boolean> =
        try {
            // Using the RESTful approach
            val result = apiClient.checkUsernameAvailability(username)

            result.map { response ->
                response.available
            }
        } catch (e: Exception) {
            Napier.e("Error checking username availability")
            Result.failure(e)
        }

    /**
     * Begins the account creation process.
     *
     * @param username The desired username.
     * @param displayName The user's display name.
     * @param deviceInfo Optional device information.
     * @return A session token and challenge for passkey creation.
     */
    @Suppress("UNUSED_PARAMETER")
    override suspend fun beginAccountCreation(
        username: String,
        displayName: String,
        deviceInfo: DeviceInfo?,
    ): Result<BeginAccountCreationResult> =
        try {
            // Device metadata is currently not part of the auth v1 begin-signup contract.
            // Keep the parameter for domain interface compatibility until the API adds support.
            val request =
                BeginAccountCreationRequest(
                    username = username,
                    displayName = displayName,
                    bio = null, // Optional bio not supported in domain model yet
                )

            val result = apiClient.beginAccountCreation(request)

            result.map { response ->
                val registrationUserId = decodeWebAuthnUserId(response.data.registrationOptions.user.id) ?: Uuid.random()
                BeginAccountCreationResult(
                    sessionToken = response.data.sessionToken,
                    challenge = response.data.registrationOptions.challenge,
                    rpId = response.data.registrationOptions.rpId,
                    rpName = response.data.registrationOptions.rpName,
                    userId = registrationUserId,
                    username = response.data.registrationOptions.user.name,
                    displayName = response.data.registrationOptions.user.displayName,
                    timeout = response.data.registrationOptions.timeout,
                )
            }
        } catch (e: Exception) {
            Napier.e("Error beginning account creation")
            Result.failure(e)
        }

    /**
     * Completes the account creation process with a passkey.
     *
     * @param sessionToken The session token from beginAccountCreation.
     * @param credentialId The passkey credential ID.
     * @param clientDataJSON The client data JSON from the passkey creation.
     * @param attestationObject The attestation object from the passkey creation.
     * @return The result of the account creation attempt.
     */
    override suspend fun completeAccountCreation(
        sessionToken: String,
        credentialId: String,
        clientDataJSON: String,
        attestationObject: String,
    ): Result<AuthenticationResult> =
        try {
            val request =
                CompleteAccountCreationRequest(
                    sessionToken = sessionToken,
                    credential =
                        PasskeyCredentialResponse(
                            id = credentialId,
                            rawId = credentialId, // In a real implementation, these would be different
                            response =
                                PasskeyAuthenticatorResponse(
                                    clientDataJSON = clientDataJSON,
                                    attestationObject = attestationObject,
                                ),
                            type = "public-key",
                        ),
                )

            val result = apiClient.completeAccountCreation(request)

            result.map { response ->
                if (response.success) {
                    val accountDto = response.data.account
                    val tokensDto = response.data.tokens

                    // Create the account and credentials
                    val account = accountDto.toCloudAccount()

                    val credentials =
                        AccountCredentials(
                            accessToken = tokensDto.accessToken,
                            refreshToken = tokensDto.refreshToken,
                            expiresIn = 3600, // Default to 1 hour if not specified
                        )

                    // Store the account and credentials
                    accountMetadata.storeCloudAccount(account, configRepository.getCurrentBackendUrl())

                    // Update the current account flow
                    accountFlow.value = account

                    AuthenticationResult.Success(account, credentials)
                } else {
                    AuthenticationResult.Error(
                        errorCode = "ACCOUNT_CREATION_FAILED",
                        message = "Failed to create account",
                    )
                }
            }
        } catch (e: Exception) {
            Napier.e("Error completing account creation")
            Result.failure(e)
        }

    /**
     * Gets the currently authenticated account.
     *
     * @return The current account, or null if not authenticated.
     */
    override suspend fun getCurrentAccount(): CloudAccount? = accountFlow.value

    /**
     * Observes the current authentication state.
     *
     * @return A flow emitting the current account whenever it changes.
     */
    override fun observeCurrentAccount(): Flow<CloudAccount?> = accountFlow.asStateFlow()

    /**
     * Refreshes the access token using a refresh token.
     *
     * @param refreshToken The refresh token.
     * @return The new access token if successful.
     */
    override suspend fun refreshAccessToken(refreshToken: String): Result<String> =
        try {
            val binding = currentCoroutineContext()[CloudRequestBinding]
            if (binding != null && binding.session.refreshToken != refreshToken) {
                error("Cloud request session changed")
            }
            val origin = binding?.location?.origin ?: configRepository.getCurrentBackendUrl()
            val result = apiClient.refreshAccessToken(refreshToken)

            result.onSuccess {
                check(isCurrentOrigin(origin)) { "Cloud request origin changed" }
            }

            result
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Napier.e("Error refreshing access token")
            Result.failure(e)
        }

    /**
     * Returns refreshed credentials to the caller, which commits them to the canonical session vault
     * only while the originating session is still current. Never stores tokens in account preferences.
     */
    override suspend fun refreshSession(refreshToken: String): Result<RefreshedSession> =
        try {
            val binding = currentCoroutineContext()[CloudRequestBinding]
            if (binding != null && binding.session.refreshToken != refreshToken) {
                error("Cloud request session changed")
            }
            val origin = binding?.location?.origin ?: configRepository.getCurrentBackendUrl()
            val rotated = apiClient.refreshSession(refreshToken)
            val result =
                if (rotated.exceptionOrNull() is UnsupportedOperationException) {
                    apiClient.refreshAccessToken(refreshToken).map { RefreshedSession(it, refreshToken = null) }
                } else {
                    rotated
                }

            result.onSuccess {
                check(isCurrentOrigin(origin)) { "Cloud request origin changed" }
            }

            result
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Napier.e("Error refreshing the session")
            Result.failure(e)
        }

    /**
     * Signs out the current user.
     *
     * @return True if the sign-out was successful.
     */
    override suspend fun signOut(): Result<Boolean> =
        try {
            val backendUrl = configRepository.getCurrentBackendUrl()
            // Clear all stored account information
            accountMetadata.remove(cloudAccountScopedKey(CloudAccountStorageKeys.ACCESS_TOKEN, backendUrl))
            accountMetadata.remove(cloudAccountScopedKey(CloudAccountStorageKeys.REFRESH_TOKEN, backendUrl))
            accountMetadata.remove(cloudAccountScopedKey(CloudAccountStorageKeys.ACCOUNT_ID, backendUrl))
            accountMetadata.remove(cloudAccountScopedKey(CloudAccountStorageKeys.ACCOUNT_USERNAME, backendUrl))
            accountMetadata.remove(cloudAccountScopedKey(CloudAccountStorageKeys.ACCOUNT_DISPLAY_NAME, backendUrl))
            accountMetadata.remove(cloudAccountScopedKey(CloudAccountStorageKeys.ACCOUNT_BIO, backendUrl))
            accountMetadata.remove(cloudAccountScopedKey(CloudAccountStorageKeys.ACCOUNT_ORIGINAL_BIO, backendUrl))
            accountMetadata.remove(cloudAccountScopedKey(CloudAccountStorageKeys.ACCOUNT_DID, backendUrl))
            accountMetadata.remove(cloudAccountScopedKey(CloudAccountStorageKeys.ACCOUNT_HANDLE, backendUrl))
            accountMetadata.remove(cloudAccountScopedKey(CloudAccountStorageKeys.USER_ID, backendUrl))
            accountMetadata.remove(cloudAccountScopedKey(CloudAccountStorageKeys.CREATED_AT, backendUrl))
            accountMetadata.remove(cloudAccountScopedKey(CloudAccountStorageKeys.UPDATED_AT, backendUrl))
            accountMetadata.remove(cloudAccountScopedKey(CloudAccountStorageKeys.PASSKEY_IDS, backendUrl))
            accountMetadata.remove(cloudAccountScopedKey(CloudAccountStorageKeys.ACCOUNT_STORAGE_MODE, backendUrl))
            accountMetadata.remove(cloudAccountScopedKey(CloudAccountStorageKeys.ACCOUNT_IDENTITY_KIND, backendUrl))

            // Clear the current account
            accountFlow.value = null

            Result.success(true)
        } catch (e: Exception) {
            Napier.e("Error signing out")
            Result.failure(e)
        }

    /**
     * Gets all passkeys associated with the current account.
     *
     * @return List of passkey credentials.
     */
    override suspend fun getPasskeyCredentials(): Result<List<PasskeyCredential>> {
        // This would typically call an API endpoint to get the list of passkeys
        // For now, we'll just return the stored credential IDs with minimal info
        return try {
            val account = getCurrentAccount()
            if (account != null) {
                val passkeyCredentials =
                    account.passkeyCredentialIds.map { credentialId ->
                        PasskeyCredential(
                            credentialId = credentialId,
                            nickname = "Passkey", // In a real implementation, we'd get this from the API
                            deviceInfo = null,
                            createdAt = account.createdAt, // Simplification
                        )
                    }
                Result.success(passkeyCredentials)
            } else {
                Result.failure(IllegalStateException("Not authenticated"))
            }
        } catch (e: Exception) {
            Napier.e("Error getting passkey credentials")
            Result.failure(e)
        }
    }

    /**
     * Associates the user's local identity with their cloud account.
     *
     * @param userId The local user ID.
     * @param accountId The cloud account ID.
     * @return True if the association was successful.
     */
    override suspend fun associateUserIdentity(
        userId: Uuid,
        accountId: String,
    ): Result<Boolean> {
        // This would typically involve API calls to associate the user ID with the account
        // For now, we'll just store the association locally
        return try {
            accountMetadata.putString(
                cloudAccountScopedKey(CloudAccountStorageKeys.USER_ID, configRepository.getCurrentBackendUrl()),
                userId.toString(),
            )
            Result.success(true)
        } catch (e: Exception) {
            Napier.e("Error associating user identity")
            Result.failure(e)
        }
    }

    /**
     * Decodes the account identifier returned by WebAuthn.
     */
    private fun decodeWebAuthnUserId(encodedUserId: String): Uuid? =
        runCatching {
            val raw = Base64.decode(encodedUserId)
            Uuid.parse(raw.decodeToString())
        }.getOrNull()
}
