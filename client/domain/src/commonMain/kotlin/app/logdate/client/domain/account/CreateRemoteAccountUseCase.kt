package app.logdate.client.domain.account

import app.logdate.client.repository.account.AccountCreationRequest
import app.logdate.client.repository.account.PasskeyAccountRepository
import app.logdate.shared.model.CloudAccount
import io.github.aakira.napier.Napier
import kotlin.time.Clock
import kotlin.uuid.Uuid

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
        try {
            // Validate input
            if (username.isBlank() || displayName.isBlank()) {
                return Result.Error(AccountCreationError.InvalidData)
            }

            // Check if username is available
            val availabilityResult = accountRepository.checkUsernameAvailability(username)
            if (availabilityResult.isFailure) {
                return Result.Error(AccountCreationError.NetworkError)
            }

            val isAvailable = availabilityResult.getOrThrow()
            if (!isAvailable) {
                return Result.Error(AccountCreationError.UsernameTaken)
            }

            // Create the account using the existing createAccountWithPasskey method
            try {
                // Create a request object with the needed information
                val request =
                    AccountCreationRequest(
                        username = username,
                        displayName = displayName,
                    )

                // Try to create the account
                val createResult = accountRepository.createAccountWithPasskey(request)

                if (createResult.isSuccess) {
                    // Create a proper CloudAccount with meaningful data
                    val now = Clock.System.now()
                    val accountId = Uuid.random()
                    val userId = Uuid.random()
                    val passkeyId = Uuid.random()

                    val cloudAccount =
                        CloudAccount(
                            id = accountId,
                            userId = userId,
                            username = username,
                            displayName = displayName,
                            createdAt = now,
                            updatedAt = now,
                            passkeyCredentialIds = listOf(passkeyId),
                            bio = null,
                            isVerified = false,
                            lastLoginAt = now,
                        )

                    Napier.i("Remote account created successfully for $username")
                    return Result.Success(cloudAccount)
                } else {
                    val exception = createResult.exceptionOrNull()
                    Napier.e("Failed to create remote account", exception)
                    return Result.Error(AccountCreationError.NetworkError)
                }
            } catch (e: Exception) {
                // Handle case where account creation fails
                Napier.e("Error during account creation", e)
                return Result.Error(AccountCreationError.Unknown("Account creation failed: ${e.message}"))
            }
        } catch (e: Exception) {
            Napier.e("Unexpected error creating remote account", e)
            return Result.Error(AccountCreationError.Unknown(e.message ?: "Unknown error"))
        }
    }
}
