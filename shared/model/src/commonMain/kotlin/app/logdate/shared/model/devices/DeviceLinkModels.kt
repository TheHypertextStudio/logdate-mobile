package app.logdate.shared.model.devices

import app.logdate.shared.model.AccountTokens
import kotlinx.serialization.Serializable

/** Values for [DeviceLinkRedeemRequest.platform]. Other short lowercase identifiers are accepted for future devices. */
object DeviceLinkPlatform {
    const val WEAR_OS = "wear_os"
}

/** Error codes the device linking routes and linked-device sessions answer with. */
object DeviceLinkErrorCodes {
    /** The device's session was removed; the device must stop syncing and be linked again. */
    const val DEVICE_SESSION_REVOKED = "DEVICE_SESSION_REVOKED"

    /** The device's session ran out unused but was never removed; the device keeps its data and is linked again. */
    const val DEVICE_SESSION_EXPIRED = "DEVICE_SESSION_EXPIRED"

    /** A linked device asked for something only a signed-in account may do. */
    const val DEVICE_SCOPE_DENIED = "DEVICE_SCOPE_DENIED"

    /** The link code is unknown, expired or already spent. */
    const val LINK_CODE_INVALID = "LINK_CODE_INVALID"

    /** The account already has the most linked devices it may have. */
    const val DEVICE_LIMIT_REACHED = "DEVICE_LIMIT_REACHED"

    /** The account minted too many link codes in the last hour. */
    const val DEVICE_LINK_RATE_LIMITED = "DEVICE_LINK_RATE_LIMITED"

    /** A full-access token tried to remove "this device". */
    const val NOT_A_LINKED_DEVICE = "NOT_A_LINKED_DEVICE"

    /** No linked device with that ID belongs to the account. */
    const val DEVICE_NOT_FOUND = "DEVICE_NOT_FOUND"
}

/** A one-time code a signed-in phone hands to a device it is linking. Works once, until [expiresAt] (epoch ms). */
@Serializable
data class DeviceLinkCodeResponse(
    val code: String,
    val expiresAt: Long,
) {
    override fun toString(): String = "DeviceLinkCodeResponse(code=<redacted>, expiresAt=$expiresAt)"
}

/** What a device sends to exchange a link code for its own session. */
@Serializable
data class DeviceLinkRedeemRequest(
    val code: String,
    val deviceName: String,
    val platform: String = DeviceLinkPlatform.WEAR_OS,
) {
    override fun toString(): String = "DeviceLinkRedeemRequest(code=<redacted>, deviceName=$deviceName, platform=$platform)"
}

/** The new device session: the account it belongs to, its ID, and sync-only tokens. */
@Serializable
data class DeviceLinkRedeemResponse(
    val accountId: String,
    val sessionId: String,
    val tokens: AccountTokens,
) {
    override fun toString(): String = "DeviceLinkRedeemResponse(accountId=$accountId, sessionId=$sessionId, tokens=<redacted>)"
}

/** One active linked device. Times are epoch milliseconds. */
@Serializable
data class LinkedDevice(
    val id: String,
    val deviceName: String,
    val platform: String,
    val createdAt: Long,
    val lastRefreshedAt: Long,
    val expiresAt: Long,
    val current: Boolean = false,
)

@Serializable
data class LinkedDeviceListResponse(
    val devices: List<LinkedDevice>,
)
