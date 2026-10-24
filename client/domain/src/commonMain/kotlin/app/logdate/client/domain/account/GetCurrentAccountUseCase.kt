package app.logdate.client.domain.account

import app.logdate.client.repository.account.PasskeyAccountRepository
import app.logdate.shared.model.LogDateAccount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Use case for getting current account information and authentication status
 */
class GetCurrentAccountUseCase(
    private val passkeyAccountRepository: PasskeyAccountRepository,
) {
    /**
     * Get account information based on the requested type
     */
    suspend operator fun invoke(request: AccountRequest): AccountResult =
        when (request) {
            is AccountRequest.GetCurrentAccount -> {
                AccountResult.CurrentAccount(
                    passkeyAccountRepository.currentAccount,
                )
            }

            is AccountRequest.GetAuthenticationStatus -> {
                AccountResult.AuthenticationStatus(
                    passkeyAccountRepository.isAuthenticated,
                )
            }

            is AccountRequest.GetAccountState -> {
                AccountResult.AccountState(
                    passkeyAccountRepository.currentAccount.map { account ->
                        when {
                            account != null -> AccountState.Authenticated(account)
                            else -> AccountState.NotAuthenticated
                        }
                    },
                )
            }

            is AccountRequest.RefreshAccountInfo -> {
                val result = passkeyAccountRepository.getAccountInfo()
                AccountResult.RefreshResult(result)
            }
        }

    sealed class AccountRequest {
        object GetCurrentAccount : AccountRequest()

        object GetAuthenticationStatus : AccountRequest()

        object GetAccountState : AccountRequest()

        object RefreshAccountInfo : AccountRequest()
    }

    sealed class AccountResult {
        data class CurrentAccount(
            val account: Flow<LogDateAccount?>,
        ) : AccountResult()

        data class AuthenticationStatus(
            val isAuthenticated: Flow<Boolean>,
        ) : AccountResult()

        data class AccountState(
            val state: Flow<GetCurrentAccountUseCase.AccountState>,
        ) : AccountResult()

        data class RefreshResult(
            val result: Result<LogDateAccount>,
        ) : AccountResult()
    }

    sealed class AccountState {
        object NotAuthenticated : AccountState()

        object AuthenticatedButLoading : AccountState()

        data class Authenticated(
            val account: LogDateAccount,
        ) : AccountState()
    }
}
