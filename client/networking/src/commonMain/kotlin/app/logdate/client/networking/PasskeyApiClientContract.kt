package app.logdate.client.networking

import app.logdate.shared.model.BeginAccountCreationData
import app.logdate.shared.model.BeginAccountCreationRequest
import app.logdate.shared.model.BeginAuthenticationData
import app.logdate.shared.model.BeginAuthenticationRequest
import app.logdate.shared.model.CompleteAccountCreationData
import app.logdate.shared.model.CompleteAccountCreationRequest
import app.logdate.shared.model.CompleteAuthenticationData
import app.logdate.shared.model.CompleteAuthenticationRequest
import app.logdate.shared.model.EntitlementResponse
import app.logdate.shared.model.LogDateAccount
import app.logdate.shared.model.PasskeyInfo
import app.logdate.shared.model.PasskeyRegistrationOptions
import app.logdate.shared.model.UsernameAvailabilityData
import io.ktor.client.request.get

/**
 * Interface for passkey API operations.
 */
interface PasskeyApiClientContract {
    suspend fun checkUsernameAvailability(username: String): Result<UsernameAvailabilityData>

    suspend fun beginAccountCreation(request: BeginAccountCreationRequest): Result<BeginAccountCreationData>

    suspend fun completeAccountCreation(request: CompleteAccountCreationRequest): Result<CompleteAccountCreationData>

    suspend fun beginAuthentication(request: BeginAuthenticationRequest): Result<BeginAuthenticationData>

    suspend fun completeAuthentication(request: CompleteAuthenticationRequest): Result<CompleteAuthenticationData>

    suspend fun getAccountInfo(accessToken: String): Result<LogDateAccount>

    /** Fetch the caller's current cloud entitlement (plan + quota + status). */
    suspend fun getEntitlement(accessToken: String): Result<EntitlementResponse>

    suspend fun updateAccountProfile(
        accessToken: String,
        displayName: String? = null,
        username: String? = null,
        bio: String? = null,
    ): Result<LogDateAccount>

    suspend fun updateAccountProfileWithOriginalBio(
        accessToken: String,
        bio: String?,
        originalBio: String?,
    ): Result<LogDateAccount> = Result.failure(UnsupportedOperationException("Cloud original bio updates unavailable"))

    suspend fun refreshToken(refreshToken: String): Result<String>

    /** Best-effort server-side refresh-token revocation for an explicit sign-out. */
    suspend fun logout(refreshToken: String): Result<Unit> =
        Result.failure(PasskeyApiException("NOT_SUPPORTED", "Remote logout is not supported by this client"))

    suspend fun deletePasskey(
        accessToken: String,
        credentialId: String,
    ): Result<Unit>

    /**
     * Lists the account's passkeys with the detail the account payload does not carry -- a
     * nickname, a device type, and when each was created and last used.
     *
     * The account itself only knows credential IDs, which cannot tell a person which of their
     * devices a credential belongs to.
     */
    suspend fun listPasskeys(accessToken: String): Result<List<PasskeyInfo>>

    /** Starts adding a passkey to the signed-in account; returns the options for the platform. */
    suspend fun beginAddPasskey(accessToken: String): Result<PasskeyRegistrationOptions> =
        Result.failure(PasskeyApiException("NOT_SUPPORTED", "Adding passkeys is not supported by this client"))

    /** Finishes adding a passkey with the credential the platform created. */
    suspend fun completeAddPasskey(
        accessToken: String,
        request: AddPasskeyRequest,
    ): Result<PasskeyInfo> = Result.failure(PasskeyApiException("NOT_SUPPORTED", "Adding passkeys is not supported by this client"))

    /**
     * Lists every way the account can sign in, passkeys included, as recorded by the server.
     */
    suspend fun listSignInIdentities(accessToken: String): Result<List<AccountSignInIdentity>> =
        Result.failure(PasskeyApiException("NOT_SUPPORTED", "Listing sign-in methods is not supported by this client"))

    /**
     * Begin restore key registration. Returns WebAuthn registration options for creating a restore key.
     * Requires an authenticated session.
     */
    suspend fun beginRestoreKeyRegistration(accessToken: String): Result<PasskeyRegistrationOptions>

    /**
     * Complete restore key registration by sending the credential JSON back to the server.
     * Requires an authenticated session.
     *
     * @param credentialJson The WebAuthn registration response JSON from [RestoreCredentialManager]
     * @param challenge The challenge that was used during registration (from [beginRestoreKeyRegistration])
     */
    suspend fun completeRestoreKeyRegistration(
        accessToken: String,
        credentialJson: String,
        challenge: String,
    ): Result<Unit>

    /**
     * Begin restore sign-in. Returns a WebAuthn authentication challenge.
     * Does not require authentication — called on first launch after device restore.
     */
    suspend fun beginRestoreSignIn(): Result<BeginAuthenticationData>

    /**
     * Complete restore sign-in. Verifies the restore credential and returns tokens.
     */
    suspend fun completeRestoreSignIn(request: CompleteAuthenticationRequest): Result<CompleteAuthenticationData>

    /**
     * Delete the authenticated account, including all sync data, media blobs, and backup blobs.
     *
     * Returns success on `204 No Content`. The caller should clear all local state immediately
     * after success — every other authenticated request will start returning 401 because the
     * server-side account no longer exists.
     *
     * Returns a `PasskeyApiException` with code `DELETION_UNAVAILABLE` (HTTP 503) when the
     * server hasn't been configured with an `AccountDeletionService`. Surface that as
     * "Account deletion is temporarily unavailable" rather than a generic error.
     */
    suspend fun deleteAccount(accessToken: String): Result<Unit>

    /**
     * Create an account from a verified Google ID token. The server validates the token against its
     * configured Google OIDC client IDs and returns the new account plus tokens. Default fails so
     * non-Google clients/fakes don't have to implement it.
     */
    suspend fun signUpWithGoogle(
        idToken: String,
        username: String? = null,
        displayName: String? = null,
        nonce: String? = null,
        requestedOwnerId: String? = null,
    ): Result<CompleteAccountCreationData> =
        Result.failure(PasskeyApiException("NOT_SUPPORTED", "Google sign-up is not supported by this client"))

    /** Sign in with a verified Google ID token. */
    suspend fun signInWithGoogle(
        idToken: String,
        nonce: String? = null,
    ): Result<CompleteAuthenticationData> =
        Result.failure(PasskeyApiException("NOT_SUPPORTED", "Google sign-in is not supported by this client"))
}
