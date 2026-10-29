@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.routes

import app.logdate.shared.model.AccountIdentityKind
import app.logdate.shared.model.AccountStorageMode
import app.logdate.shared.model.AccountTokens
import app.logdate.shared.model.PasskeyAssertionResponse
import app.logdate.shared.model.PasskeyCredentialResponse
import app.logdate.shared.model.PasskeyRegistrationOptions
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

@Serializable
data class EmailBindingRequest(
    val source: String,
    val value: String,
    val nonce: String? = null,
)

@Serializable
data class SignupPasskeyBeginRequest(
    val username: String,
    val displayName: String,
    val bio: String? = null,
    val originalBio: String? = null,
    val requestedOwnerId: String? = null,
)

@Serializable
data class SignupPasskeyBeginData(
    val sessionToken: String,
    val registrationOptions: app.logdate.shared.model.PasskeyRegistrationOptions,
)

@Serializable
data class SignupPasskeyBeginResponse(
    val success: Boolean,
    val data: SignupPasskeyBeginData,
)

@Serializable
data class SignupPasskeyCompleteRequest(
    val sessionToken: String,
    val credential: PasskeyCredentialResponse,
    val emailBinding: EmailBindingRequest? = null,
    /** The name of the device the passkey was created on, shown in the passkey list. */
    val nickname: String? = null,
)

@Serializable
data class SigninPasskeyBeginRequest(
    val username: String? = null,
)

@Serializable
data class SigninPasskeyBeginData(
    val challenge: String,
    val rpId: String,
    val allowCredentials: List<PasskeyAllowCredentialDto>,
    val timeout: Long,
    val userVerification: String,
)

@Serializable
data class SigninPasskeyBeginResponse(
    val success: Boolean,
    val data: SigninPasskeyBeginData,
)

@Serializable
data class PasskeyAllowCredentialDto(
    @EncodeDefault
    val type: String = "public-key",
    val id: String,
    val transports: List<String> = emptyList(),
)

@Serializable
data class SigninPasskeyCompleteRequest(
    val credential: PasskeyAssertionResponse,
    val challenge: String,
)

@Serializable
data class GoogleAuthRequest(
    val idToken: String,
    val username: String? = null,
    val displayName: String? = null,
    val nonce: String? = null,
    val requestedOwnerId: String? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AuthAccountView(
    val id: String,
    val username: String,
    val displayName: String,
    val did: String? = null,
    val handle: String? = null,
    val bio: String? = null,
    val originalBio: String? = null,
    val email: String? = null,
    val emailVerified: Boolean,
    val emailVerifiedAt: String? = null,
    val linkedProviders: List<String>,
    val passkeyCredentialIds: List<String>,
    val createdAt: String,
    val updatedAt: String,
    /** How the server keeps this account's data; apps choose how to sync from it. */
    val storageMode: AccountStorageMode,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val identityKind: AccountIdentityKind = AccountIdentityKind.HOSTED,
)

@Serializable
data class AuthResponseData(
    val account: AuthAccountView,
    val tokens: AccountTokens,
    /**
     * Sent after a passkey sign-in or sign-up on a server with recovery keys: single-use proof, for
     * five minutes, that the person just confirmed with a passkey. The app spends it to register the
     * account's first recovery key without a second prompt.
     */
    val stepUpGrant: String? = null,
)

@Serializable
data class AuthResponse(
    val success: Boolean,
    val data: AuthResponseData,
)

@Serializable
data class RefreshTokenRequestV1(
    val refreshToken: String,
)

@Serializable
data class LogoutRequestV1(
    val refreshToken: String,
)

@Serializable
data class RefreshTokenDataV1(
    val accessToken: String,
    /** A new refresh token, sent only when a linked device's session was just renewed. Store it. */
    val refreshToken: String? = null,
)

@Serializable
data class RefreshTokenResponseV1(
    val success: Boolean,
    val data: RefreshTokenDataV1,
)

@Serializable
data class IdentityView(
    val provider: String,
    val providerSubject: String,
    val email: String? = null,
    val emailVerified: Boolean,
    val createdAt: String,
    val lastSignInAt: String? = null,
)

@Serializable
data class IdentityListResponse(
    val success: Boolean,
    val data: List<IdentityView>,
)

@Serializable
data class AuthMetricsResponse(
    val success: Boolean,
    val data: app.logdate.server.auth.AuthMetricsSnapshot,
)

@Serializable
data class RestoreRegisterBeginResponse(
    val success: Boolean,
    val data: PasskeyRegistrationOptions,
)

@Serializable
data class RestoreRegisterCompleteRequest(
    val credentialJson: String,
    val challenge: String,
)

@Serializable
data class AddPasskeyCompleteRequest(
    val challenge: String,
    val credential: PasskeyCredentialResponse,
    /** The name of the device the passkey was created on, shown in the passkey list. */
    val nickname: String? = null,
)

@Serializable
data class PasskeyListResponse(
    val success: Boolean,
    val data: List<app.logdate.shared.model.PasskeyInfo>,
)

@Serializable
data class PasskeyResponse(
    val success: Boolean,
    val data: app.logdate.shared.model.PasskeyInfo,
)
