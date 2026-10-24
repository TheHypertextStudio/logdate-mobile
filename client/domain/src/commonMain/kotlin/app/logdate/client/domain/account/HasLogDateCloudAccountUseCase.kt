package app.logdate.client.domain.account

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Use case to check if the user has a LogDate Cloud account created.
 *
 * This is useful for determining whether to show account creation prompts,
 * cloud features, or other account-dependent UI elements.
 */
class HasLogDateCloudAccountUseCase(
    private val getCurrentAccountUseCase: GetCurrentAccountUseCase,
) {
    /**
     * Returns a flow of the user's account status.
     *
     * @return Flow<AccountStatus> - detailed account status including whether user has an account
     */
    suspend operator fun invoke(): Flow<AccountStatus> {
        val result = getCurrentAccountUseCase(GetCurrentAccountUseCase.AccountRequest.GetAccountState)
        return when (result) {
            is GetCurrentAccountUseCase.AccountResult.AccountState -> {
                result.state.map { state ->
                    when (state) {
                        is GetCurrentAccountUseCase.AccountState.NotAuthenticated -> AccountStatus.NoAccount
                        is GetCurrentAccountUseCase.AccountState.AuthenticatedButLoading -> AccountStatus.HasAccountLoading
                        is GetCurrentAccountUseCase.AccountState.Authenticated -> AccountStatus.HasAccount(state.account)
                    }
                }
            }

            else -> {
                throw IllegalStateException("Unexpected result type from GetCurrentAccountUseCase")
            }
        }
    }

    /**
     * Represents the status of the user's LogDate Cloud account
     */
    sealed class AccountStatus {
        /**
         * User has no LogDate Cloud account
         */
        object NoAccount : AccountStatus()

        /**
         * User has an account but account information is loading
         */
        object HasAccountLoading : AccountStatus()

        /**
         * User has an account with full account information
         */
        data class HasAccount(
            val account: app.logdate.shared.model.LogDateAccount,
        ) : AccountStatus()

        /**
         * Convenience property to check if user has any account
         */
        val hasAccount: Boolean
            get() = this is HasAccount || this is HasAccountLoading
    }
}
