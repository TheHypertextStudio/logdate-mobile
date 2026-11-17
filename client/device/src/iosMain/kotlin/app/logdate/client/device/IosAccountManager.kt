package app.logdate.client.device

import app.logdate.shared.model.LogDateAccount

/**
 * Legacy keychain account indexes can contain more than one identity. They are deliberately
 * quarantined until they can be migrated into the single canonical owner model without reading,
 * rewriting, or deleting any existing credentials.
 */
class IosAccountManager : PlatformAccountManager {
    override suspend fun addAccount(
        account: LogDateAccount,
        accessToken: String,
        refreshToken: String,
        backendUrl: String,
    ): Result<Unit> = unavailable()

    override suspend fun updateAccount(
        account: LogDateAccount,
        backendUrl: String,
    ): Result<Unit> = unavailable()

    override suspend fun updateTokens(
        username: String,
        backendUrl: String,
        accessToken: String,
        refreshToken: String,
    ): Result<Unit> = unavailable()

    override suspend fun removeAccount(
        username: String,
        backendUrl: String,
    ): Result<Unit> = unavailable()

    override suspend fun getStoredAccounts(): Result<List<PlatformAccountInfo>> = unavailable()

    override suspend fun getTokens(
        username: String,
        backendUrl: String,
    ): Result<TokenPair?> = unavailable()

    override suspend fun clearAllTokens(): Result<Unit> = unavailable()

    private fun <T> unavailable(): Result<T> =
        Result.failure(
            PlatformAccountException(
                "iOS legacy account storage is quarantined pending canonical identity migration",
            ),
        )
}
