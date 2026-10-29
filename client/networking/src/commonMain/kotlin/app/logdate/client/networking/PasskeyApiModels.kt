package app.logdate.client.networking

import app.logdate.shared.model.AccountIdentityKind
import app.logdate.shared.model.AccountStorageMode
import app.logdate.shared.model.AccountTokens
import app.logdate.shared.model.LogDateAccount
import app.logdate.shared.model.PasskeyAllowCredential
import app.logdate.shared.model.PasskeyCredentialResponse
import app.logdate.shared.model.PasskeyInfo
import app.logdate.shared.model.PasskeyRegistrationOptions
import io.ktor.client.request.get
import kotlinx.serialization.Serializable
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Exception thrown when passkey API operations fail.
 */
class PasskeyApiException(
    val errorCode: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Common API error codes.
 */
object PasskeyApiErrorCodes {
    const val USERNAME_TAKEN = "USERNAME_TAKEN"
    const val VALIDATION_ERROR = "VALIDATION_ERROR"
    const val INVALID_SESSION_TOKEN = "INVALID_SESSION_TOKEN"
    const val PASSKEY_VERIFICATION_FAILED = "PASSKEY_VERIFICATION_FAILED"
    const val ACCOUNT_NOT_FOUND = "ACCOUNT_NOT_FOUND"
    const val AUTHENTICATION_FAILED = "AUTHENTICATION_FAILED"
    const val NETWORK_ERROR = "NETWORK_ERROR"
    const val INVALID_TOKEN = "INVALID_TOKEN"
    const val INVALID_REFRESH_TOKEN = "INVALID_REFRESH_TOKEN"
    const val REFRESH_TOKEN_REVOKED = "REFRESH_TOKEN_REVOKED"
    const val UNAUTHORIZED = "UNAUTHORIZED"
    const val PASSKEY_NOT_FOUND = "PASSKEY_NOT_FOUND"
    const val PASSKEY_DELETION_FAILED = "PASSKEY_DELETION_FAILED"

    // Codes the server emits that the client can usefully distinguish.
    const val RATE_LIMIT_EXCEEDED = "RATE_LIMIT_EXCEEDED"
    const val ACCOUNT_LINK_CONFLICT = "ACCOUNT_LINK_CONFLICT"
    const val EMAIL_BINDING_INVALID = "EMAIL_BINDING_INVALID"
    const val GOOGLE_AUTH_NOT_CONFIGURED = "GOOGLE_AUTH_NOT_CONFIGURED"
    const val GOOGLE_TOKEN_INVALID = "GOOGLE_TOKEN_INVALID"
    const val DELETION_UNAVAILABLE = "DELETION_UNAVAILABLE"
    const val LAST_SIGNIN_FACTOR = "LAST_SIGNIN_FACTOR"
    const val CANONICAL_OWNER_ID_TAKEN = "CANONICAL_OWNER_ID_TAKEN"
    const val SERVER_ERROR = "SERVER_ERROR"

    // Codes this client assigns when the server's answer carries no code of its own.

    /** The server did not answer in time, or answered with a server error and no explanation. */
    const val SERVER_UNAVAILABLE = "SERVER_UNAVAILABLE"

    /** The server answered with something that is not a LogDate API response. */
    const val UNEXPECTED_RESPONSE = "UNEXPECTED_RESPONSE"
}

@Serializable
internal data class GoogleAuthRequestDto(
    val idToken: String,
    val username: String? = null,
    val displayName: String? = null,
    val nonce: String? = null,
    val requestedOwnerId: String? = null,
)

@Serializable
internal data class SignupPasskeyBeginRequestDto(
    val username: String,
    val displayName: String,
    val bio: String? = null,
    val originalBio: String? = null,
    val requestedOwnerId: String? = null,
)

@Serializable
internal data class SignupPasskeyBeginResponseDto(
    val success: Boolean,
    val data: SignupPasskeyBeginDataDto,
)

@Serializable
internal data class SignupPasskeyBeginDataDto(
    val sessionToken: String,
    val registrationOptions: PasskeyRegistrationOptions,
)

@Serializable
internal data class SigninPasskeyBeginResponseDto(
    val success: Boolean,
    val data: SigninPasskeyBeginDataDto,
)

@Serializable
internal data class SigninPasskeyBeginDataDto(
    val challenge: String,
    val rpId: String,
    val allowCredentials: List<PasskeyAllowCredential>,
    val timeout: Long,
    val userVerification: String,
)

@Serializable
internal data class AuthResponseDto(
    val success: Boolean,
    val data: AuthResponseDataDto,
)

@Serializable
internal data class AuthResponseDataDto(
    val account: AuthAccountDto,
    val tokens: AccountTokens,
    val stepUpGrant: String? = null,
)

@Serializable
internal data class AuthAccountDto(
    val id: String,
    val username: String,
    val displayName: String,
    val did: String? = null,
    val handle: String? = null,
    val bio: String? = null,
    val originalBio: String? = null,
    val passkeyCredentialIds: List<String> = emptyList(),
    val createdAt: String,
    val updatedAt: String,
    val email: String? = null,
    val emailVerified: Boolean = false,
    val emailVerifiedAt: String? = null,
    /** Absent from servers that predate storage modes, which keep every account in legacy. */
    val storageMode: AccountStorageMode = AccountStorageMode.LEGACY,
    val identityKind: AccountIdentityKind = AccountIdentityKind.UNRECOGNIZED,
)

@Serializable
internal data class RefreshTokenResponseV1Dto(
    val success: Boolean,
    val data: RefreshTokenDataV1Dto,
)

@Serializable
internal data class RefreshTokenDataV1Dto(
    val accessToken: String,
)

@Serializable
internal data class LogoutRequestDto(
    val refreshToken: String,
)

@Serializable
internal data class RestoreRegisterBeginResponseDto(
    val success: Boolean,
    val data: PasskeyRegistrationOptions,
)

@Serializable
internal data class RestoreRegisterCompleteRequestDto(
    val credentialJson: String,
    val challenge: String,
)

internal fun AuthAccountDto.toLogDateAccount(): LogDateAccount =
    LogDateAccount(
        id = Uuid.parse(id),
        username = username,
        displayName = displayName,
        did = did,
        handle = handle,
        bio = bio,
        originalBio = originalBio,
        passkeyCredentialIds = passkeyCredentialIds,
        createdAt = Instant.parse(createdAt),
        updatedAt = Instant.parse(updatedAt),
        email = email,
        emailVerified = emailVerified,
        emailVerifiedAt = emailVerifiedAt?.let { Instant.parse(it) },
        storageMode = storageMode,
        identityKind = identityKind,
    )

/** The wrapped shape `GET /auth/me/passkeys` replies with. */
@Serializable
internal data class PasskeyListApiResponse(
    val success: Boolean,
    val data: List<PasskeyInfo>,
)

/** The wrapped shape `POST /auth/me/passkeys/complete` replies with. */
@Serializable
internal data class PasskeyApiResponse(
    val success: Boolean,
    val data: PasskeyInfo,
)

/** A passkey the platform created, sent to be added to the signed-in account. */
@Serializable
data class AddPasskeyRequest(
    val challenge: String,
    val credential: PasskeyCredentialResponse,
    /** The name of this device, so the passkey list can tell the person which one it is. */
    val nickname: String? = null,
)

/**
 * One way an account can sign in, as `GET /auth/me/identities` reports it.
 *
 * @property provider `passkey` or `google`; other values may appear as the server grows.
 */
@Serializable
data class AccountSignInIdentity(
    val provider: String,
    val email: String? = null,
    val emailVerified: Boolean = false,
    val createdAt: String,
    val lastSignInAt: String? = null,
)

@Serializable
internal data class SignInIdentityListApiResponse(
    val success: Boolean,
    val data: List<AccountSignInIdentity>,
)
