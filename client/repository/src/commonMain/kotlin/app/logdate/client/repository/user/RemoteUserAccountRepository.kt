package app.logdate.client.repository.user

import kotlinx.coroutines.flow.Flow

/**
 * Repository for user account data.
 *
 * This is the main interface used to interact with LogDate-compatible syncing services such as
 * LogDate Cloud.
 */
interface RemoteUserAccountRepository {
    /**
     * The currently authenticated user.
     *
     * If no user has been authenticated (e.g. completed onboarding or signed up for LogDate Cloud),
     * this property will be `null`.
     */
    val currentUser: Flow<UserAccount?>

    suspend fun signInWithPasskey(
        primaryIdentifier: String,
        passkey: String,
    ): Result<UserAccount>

    suspend fun signOut(): Result<Unit>

    suspend fun createAccount(
        primaryIdentifier: String,
        password: String,
        displayName: String,
        photoUrl: String? = null,
    ): Result<UserAccount>

    suspend fun updateAccount(
        displayName: String? = null,
        primaryIdentifier: String? = null,
        photoUrl: String? = null,
    ): Result<UserAccount>

    suspend fun createPasskey(
        primaryIdentifier: String,
        passkey: String,
    ): Result<Unit>

    suspend fun deletePasskey(primaryIdentifier: String): Result<Unit>

    suspend fun deleteAccount(): Result<Unit>
}

/**
 * A record for a user account.
 */
data class UserAccount(
    val uid: String,
    /**
     * The primary identifier for the user.
     */
    val email: String,
    /**
     * The display name of the user.
     *
     * For passkey operations, this is a user-friendly name used to identify an associated passkey.
     * It exists primarily for display purposes and is not used for authentication.
     */
    val displayName: String,
    val photoUrl: String?,
    /**
     * The host of the service that the user is registered with.
     *
     * Example: `logdate.app`
     */
    val host: String,
)
