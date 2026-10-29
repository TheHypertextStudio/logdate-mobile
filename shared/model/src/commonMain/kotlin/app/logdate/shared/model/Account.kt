package app.logdate.shared.model

import kotlinx.serialization.Contextual
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class, ExperimentalSerializationApi::class)
@Serializable
data class LogDateAccount(
    val id: @Contextual Uuid = Uuid.random(),
    val username: String,
    val displayName: String,
    val did: String? = null,
    val handle: String? = null,
    val bio: String? = null,
    val originalBio: String? = null,
    val passkeyCredentialIds: List<String> = emptyList(),
    val createdAt: Instant = Clock.System.now(),
    val updatedAt: Instant = Clock.System.now(),
    val email: String? = null,
    val emailVerified: Boolean = false,
    val emailVerifiedAt: Instant? = null,
    /** How the server keeps this account's data; a server that predates storage modes sends nothing. */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val storageMode: AccountStorageMode = AccountStorageMode.LEGACY,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val identityKind: AccountIdentityKind = AccountIdentityKind.HOSTED,
)

@Serializable
data class AccountTokens(
    val accessToken: String,
    val refreshToken: String,
)

@Serializable
data class BeginAccountCreationRequest(
    val username: String,
    val displayName: String,
    val bio: String? = null,
    val originalBio: String? = null,
    /** The durable local owner that a new Cloud account must bind to. */
    val requestedOwnerId: String? = null,
)

@Serializable
data class BeginAccountCreationResponse(
    val success: Boolean,
    val data: BeginAccountCreationData,
)

@Serializable
data class BeginAccountCreationData(
    val sessionToken: String,
    val registrationOptions: PasskeyRegistrationOptions,
)

@Serializable
data class CompleteAccountCreationRequest(
    val sessionToken: String,
    val credential: PasskeyCredentialResponse,
    /** The name of the device creating the passkey, shown in the account's passkey list. */
    val nickname: String? = null,
)

@Serializable
data class CompleteAccountCreationResponse(
    val success: Boolean,
    val data: CompleteAccountCreationData,
)

@Serializable
data class CompleteAccountCreationData(
    val account: LogDateAccount,
    val tokens: AccountTokens,
    /**
     * Sent with a passkey sign-in or sign-up by a server that supports recovery keys: single-use
     * proof, for five minutes, that the person just confirmed with a passkey.
     */
    val stepUpGrant: String? = null,
)

@Serializable
data class PasskeyCredentialResponse(
    val id: String,
    val rawId: String,
    val response: PasskeyAuthenticatorResponse,
    val type: String = "public-key",
)

@Serializable
data class PasskeyAuthenticatorResponse(
    val clientDataJSON: String,
    val attestationObject: String,
)

@Serializable
data class BeginAuthenticationRequest(
    val username: String? = null,
)

@Serializable
data class BeginAuthenticationResponse(
    val success: Boolean,
    val data: BeginAuthenticationData,
)

@Serializable
data class BeginAuthenticationData(
    val challenge: String,
    val rpId: String,
    val allowCredentials: List<PasskeyAllowCredential>,
    val timeout: Long,
    val userVerification: String,
)

@Serializable
data class PasskeyAllowCredential(
    val type: String = "public-key",
    val id: String,
    /**
     * Transport hints for the credential.
     *
     * Optional in the WebAuthn spec and omitted by the server whenever a stored passkey recorded
     * none, so this must default rather than be required: making it mandatory failed
     * deserialization of every `signin/passkey/begin` response and broke sign-in outright.
     */
    val transports: List<String> = emptyList(),
)

@Serializable
data class CompleteAuthenticationRequest(
    val credential: PasskeyAssertionResponse,
    val challenge: String,
)

@Serializable
data class PasskeyAssertionResponse(
    val id: String,
    val rawId: String,
    val response: PasskeyAssertionAuthenticatorResponse,
    val type: String = "public-key",
)

@Serializable
data class PasskeyAssertionAuthenticatorResponse(
    val clientDataJSON: String,
    val authenticatorData: String,
    val signature: String,
    val userHandle: String,
)

@Serializable
data class CompleteAuthenticationResponse(
    val success: Boolean,
    val data: CompleteAuthenticationData,
)

@Serializable
data class CompleteAuthenticationData(
    val account: LogDateAccount,
    val tokens: AccountTokens,
    /**
     * Sent with a passkey sign-in or sign-up by a server that supports recovery keys: single-use
     * proof, for five minutes, that the person just confirmed with a passkey.
     */
    val stepUpGrant: String? = null,
)

@Serializable
data class UsernameAvailabilityResponse(
    val success: Boolean,
    val data: UsernameAvailabilityData,
)

@Serializable
data class UsernameAvailabilityData(
    val username: String,
    val available: Boolean,
)

@Serializable
data class ApiErrorResponse(
    val error: ApiError,
)

@Serializable
data class AccountInfoResponse(
    val success: Boolean,
    val data: LogDateAccount,
)

@Serializable
data class UpdateAccountProfileRequest(
    val displayName: String? = null,
    val username: String? = null,
    val bio: String? = null,
    val originalBio: String? = null,
)

@Serializable
data class ApiError(
    val code: String,
    val message: String,
)

@Serializable
data class RefreshTokenRequest(
    val refreshToken: String,
)

@Serializable
data class RefreshTokenResponse(
    val success: Boolean,
    val data: RefreshTokenData,
)

@Serializable
data class RefreshTokenData(
    val accessToken: String,
)
