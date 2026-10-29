package app.logdate.client.repository.account

import app.logdate.shared.model.AtprotoConnectionSnapshot
import kotlinx.coroutines.flow.StateFlow

/** Optional PDS authorization remains independent from LogDate sign-in and journal access. */
interface AtprotoConnectionRepository {
    val state: StateFlow<AccountConnectionState>

    suspend fun refresh()

    suspend fun requestDisconnect()

    suspend fun cancelDisconnectConfirmation()

    suspend fun confirmDisconnect()

    /** The caller opens the returned browser URL only after the durable app proof is saved. */
    suspend fun reconnect(): Result<String>

    /** A recognized persisted connection return is never passed to ordinary account sign-in. */
    suspend fun resume(returnUrl: String): Boolean

    suspend fun cancelPending()

    /** Cancels in-flight work and forgets visible state; a browser proof survives background suspension. */
    suspend fun invalidate()
}

data class AccountConnectionState(
    val available: Boolean = false,
    val snapshot: AtprotoConnectionSnapshot? = null,
    val busy: Boolean = false,
    val observationFailed: Boolean = false,
    val waitingForBrowser: Boolean = false,
    val hasPendingDisconnect: Boolean = false,
    val confirmingDisconnect: Boolean = false,
    val problem: AccountConnectionProblem? = null,
    val remoteRevocationUnconfirmed: Boolean = false,
    val newerConnectionPreserved: Boolean = false,
)

enum class AccountConnectionProblem {
    BROWSER_UNAVAILABLE,
    UNAVAILABLE,
    CHANGED,
    PROOF_INVALID,
    PROOF_EXPIRED,
    LAST_SIGN_IN_FACTOR,
    INELIGIBLE,
    RATE_LIMITED,
}
