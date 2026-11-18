package app.logdate.client.sync.cloud

/**
 * A refreshed session: always a new access token, and a new refresh token when the server rotates it.
 * A linked device's server session slides forward on refresh and rotates the token in the last days of
 * its life, so a caller that stores only [accessToken] loses the device when the old token expires.
 */
data class RefreshedSession(
    val accessToken: String,
    val refreshToken: String?,
)

/** An account repository that returns a rotated refresh token along with the new access token. */
interface SessionRefreshingAccountRepository {
    suspend fun refreshSession(refreshToken: String): Result<RefreshedSession>
}

/**
 * Told when the server ends this device's session. Only a revoked session means the device was removed,
 * so only [onDeviceSessionRevoked] may erase what the device holds.
 */
fun interface DeviceSessionRevocationListener {
    /** The session was removed from the account: the device stops and erases what it holds. */
    suspend fun onDeviceSessionRevoked()

    /** The session or its refresh token ran out without being removed: the device keeps its data and is linked again. */
    suspend fun onDeviceSessionEnded() {}

    companion object {
        val None = DeviceSessionRevocationListener { }
    }
}
