package app.logdate.client.data.account

/** Local journal-key readiness is distinct from a successful account/passkey authentication. */
interface AccountKeyAccess {
    suspend fun hasLocalAccountKeys(accountId: String): Boolean

    suspend fun bindLocalKeysForNewAccount(accountId: String): Boolean

    suspend fun unlockOrProvision(
        apiBaseUrl: String,
        accountId: String,
        accessToken: String,
        credentialId: String,
        unlockSecret: ByteArray?,
        mayPublishLocalKeys: Boolean,
    ): AccountKeyUnlockStatus
}

internal class AccountKeysNotReadyException : IllegalStateException("Approve this device to open your journal")
