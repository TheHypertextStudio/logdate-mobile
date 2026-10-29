package app.logdate.server.auth

import app.logdate.shared.model.AccountIdentityKind
import app.logdate.shared.model.AccountStorageMode
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * A decision about an account made by the people who run the server, not by its owner. Either one
 * takes the account out of the public network: its repository is no longer shown, and relays are told
 * with the status named by [wireValue].
 */
@Serializable
enum class AccountModeration(
    val wireValue: String,
) {
    /** The account is paused, and can be restored. */
    @SerialName("suspended")
    SUSPENDED("suspended"),

    /** The account's content was removed from the network. */
    @SerialName("takendown")
    TAKENDOWN("takendown"),
    ;

    companion object {
        /** The moderation named by [value], or `null` for no moderation or a value this server does not know. */
        fun fromWireValue(value: String?): AccountModeration? = entries.firstOrNull { it.wireValue == value }
    }
}

@OptIn(ExperimentalUuidApi::class)
@Serializable
data class Account(
    val id: @Contextual Uuid,
    val username: String,
    val displayName: String,
    val did: String? = null,
    val handle: String? = null,
    val signingKeyPublic: String? = null,
    val plcRecoveryDidKey: String? = null,
    val email: String? = null,
    val emailVerified: Boolean = false,
    val emailVerifiedAt: Instant? = null,
    val bio: String? = null,
    val originalBio: String? = null,
    val createdAt: Instant,
    val lastSignInAt: Instant? = null,
    val timezone: String? = null,
    val locale: String? = null,
    val preferences: String? = null,
    val isActive: Boolean = true,
    /**
     * How this account's journal data is kept. Set when the account is created and changed only by
     * [AccountRepository.updateStorageModeIfUnchanged]; [AccountRepository.save] ignores it on updates.
     */
    val storageMode: AccountStorageMode = AccountStorageMode.LEGACY,
    /**
     * Whether the server's operators suspended or took down this account. Changed only by
     * [AccountRepository.setModeration]; [AccountRepository.save] ignores it on updates.
     */
    val moderation: AccountModeration? = null,
    /**
     * The exact DID the owner explicitly made public, after publication completed. Null means
     * private. Ordinary account saves preserve this value; changing identity never transfers consent.
     */
    val publicIdentityDid: String? = null,
    /** Set by verified enrollment or adoption; profile saves cannot change identity ownership. */
    val identityKind: AccountIdentityKind = AccountIdentityKind.HOSTED,
)

@OptIn(ExperimentalUuidApi::class)
@Serializable
data class DeviceInfo(
    val platform: String, // "android", "ios", "web"
    val deviceName: String,
    val osVersion: String? = null,
    val appVersion: String? = null,
    val capabilities: List<String> = emptyList(), // ["uv", "rk", "up"]
)

@OptIn(ExperimentalUuidApi::class)
@Serializable
data class TemporarySession(
    val id: String,
    val temporaryUserId: @Contextual Uuid,
    val challenge: String, // Base64URL encoded
    val username: String,
    val displayName: String,
    val bio: String? = null,
    val originalBio: String? = null,
    val deviceInfo: DeviceInfo?,
    val sessionType: SessionType,
    val createdAt: Instant,
    val expiresAt: Instant,
    val isUsed: Boolean = false,
)

@Serializable
enum class SessionType {
    ACCOUNT_CREATION,
    AUTHENTICATION,
}
