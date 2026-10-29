package app.logdate.client.repository.account

import app.logdate.shared.model.LogDateAccount
import kotlinx.coroutines.flow.StateFlow

/** Browser identity proof always ends in an explicit LogDate passkey ceremony. */
interface BlueskySignInRepository {
    val state: StateFlow<BlueskySignInState>

    suspend fun isAvailable(): Boolean = false

    suspend fun prepare(): Boolean = isAvailable()

    suspend fun start(handle: String): Result<String>

    suspend fun resume(returnUrl: String): Boolean

    suspend fun restore()

    suspend fun createAccount(request: AccountCreationRequest)

    suspend fun confirmPasskey(adoptLocalData: Boolean = false)

    suspend fun cancel()
}

sealed interface BlueskySignInState {
    data object Idle : BlueskySignInState

    data object Working : BlueskySignInState

    data object AwaitingBrowser : BlueskySignInState

    class AccountDetails(
        val savedRequest: AccountCreationRequest? = null,
    ) : BlueskySignInState {
        override fun toString(): String = "AccountDetails()"
    }

    data object ConfirmPasskey : BlueskySignInState

    data class LocalDataConsent(
        val requiresNewSignIn: Boolean = false,
    ) : BlueskySignInState

    data class DeviceApproval(
        val requiresNewSignIn: Boolean = false,
    ) : BlueskySignInState

    data class Complete(
        val account: LogDateAccount,
        val backendOrigin: String,
    ) : BlueskySignInState

    data class Failed(
        val reason: BlueskySignInFailure,
    ) : BlueskySignInState
}

enum class BlueskySignInFailure { CANCELED, UNAVAILABLE, EXPIRED, CONTEXT_CHANGED, INVALID_REQUEST }
