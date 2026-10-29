package app.logdate.client.domain.account

import app.logdate.client.repository.account.AccountCreationRequest
import app.logdate.client.repository.account.PasskeyAccountRepository
import app.logdate.shared.model.CloudAccount
import app.logdate.shared.model.toCloudAccount
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException

/**
 * Use case for creating a remote cloud account.
 */
class CreateRemoteAccountUseCase(
    private val accountRepository: PasskeyAccountRepository,
) {
    sealed class Result {
        data class Success(
            val account: CloudAccount,
        ) : Result()

        data class Error(
            val error: AccountCreationError,
        ) : Result()
    }

    sealed class AccountCreationError {
        object NetworkError : AccountCreationError()

        object UsernameTaken : AccountCreationError()

        object InvalidData : AccountCreationError()

        data class Unknown(
            val message: String,
        ) : AccountCreationError()
    }

    /**
     * Creates a new remote account with the given details.
     *
     * @param username The unique username for the account
     * @param displayName The display name for the account
     * @return The result of the account creation operation
     */
    suspend operator fun invoke(
        username: String,
        displayName: String,
    ): Result {
        if (username.isBlank() || displayName.isBlank()) return Result.Error(AccountCreationError.InvalidData)
        return try {
            val availability = accountRepository.checkUsernameAvailability(username)
            availability.exceptionOrNull()?.let { failure ->
                if (failure is CancellationException) throw failure
                return Result.Error(AccountCreationError.NetworkError)
            }
            if (!availability.getOrThrow()) return Result.Error(AccountCreationError.UsernameTaken)
            val creation = accountRepository.createAccountWithPasskey(AccountCreationRequest(username, displayName))
            creation.exceptionOrNull()?.let { failure ->
                if (failure is CancellationException) throw failure
                return Result.Error(AccountCreationError.NetworkError)
            }
            Result.Success(creation.getOrThrow().toCloudAccount())
        } catch (canceled: CancellationException) {
            throw canceled
        } catch (_: Exception) {
            Napier.e("Cloud account creation could not finish")
            Result.Error(AccountCreationError.Unknown("Account creation could not finish"))
        }
    }
}
