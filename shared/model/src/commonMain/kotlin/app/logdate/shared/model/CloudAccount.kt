package app.logdate.shared.model

import app.logdate.util.UuidSerializer
import kotlinx.serialization.Serializable
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Represents a user's LogDate Cloud account.
 *
 * This is the core domain model for cloud account information.
 */
@Serializable
data class CloudAccount(
    @Serializable(with = UuidSerializer::class)
    val id: Uuid,
    val username: String,
    val displayName: String,
    val did: String? = null,
    val handle: String? = null,
    @Serializable(with = UuidSerializer::class)
    val userId: Uuid,
    val createdAt: Instant,
    val updatedAt: Instant,
    val passkeyCredentialIds: List<
        @Serializable(with = UuidSerializer::class)
        Uuid,
    >,
    val bio: String? = null,
    val originalBio: String? = null,
    val isVerified: Boolean = false,
    val lastLoginAt: Instant? = null,
    /**
     * How the server keeps this account's data, as the server last reported it. Unknown until the
     * server has reported it: a device never assumes legacy.
     */
    val storageMode: AccountStorageMode = AccountStorageMode.UNRECOGNIZED,
    val identityKind: AccountIdentityKind = AccountIdentityKind.UNRECOGNIZED,
)

/**
 * Represents the authentication credentials for a cloud account.
 */
@Serializable
data class AccountCredentials(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,
)

/**
 * Represents the result of an authentication operation.
 */
@Serializable
sealed class AuthenticationResult {
    @Serializable
    data class Success(
        val account: CloudAccount,
        val credentials: AccountCredentials,
    ) : AuthenticationResult()

    @Serializable
    data class Error(
        val errorCode: String,
        val message: String,
    ) : AuthenticationResult()
}

/**
 * Information about the device associated with a passkey.
 */
@Serializable
data class DeviceInfo(
    val platform: String,
    val deviceName: String?,
    val deviceType: DeviceType,
)

/**
 * Device information DTO for API requests.
 */
@Serializable
data class DeviceInfoDto(
    val platform: String,
    val deviceName: String? = null,
    val deviceType: String = "MOBILE",
)

/**
 * The type of device a passkey is associated with.
 */
@Serializable
enum class DeviceType {
    MOBILE,
    TABLET,
    DESKTOP,
    UNKNOWN,
}

/**
 * Represents a passkey credential for authentication.
 */
@Serializable
data class PasskeyCredential(
    @Serializable(with = UuidSerializer::class)
    val credentialId: Uuid,
    val nickname: String,
    val deviceInfo: DeviceInfo?,
    val createdAt: Instant,
)

/**
 * Result of beginning account creation.
 */
@Serializable
data class BeginAccountCreationResult(
    val sessionToken: String,
    val challenge: String,
    val rpId: String,
    val rpName: String,
    @Serializable(with = UuidSerializer::class)
    val userId: Uuid,
    val username: String,
    val displayName: String,
    val timeout: Long,
)
