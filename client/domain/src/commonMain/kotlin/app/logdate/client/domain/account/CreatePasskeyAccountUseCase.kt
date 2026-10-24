package app.logdate.client.domain.account

import app.logdate.client.networking.PasskeyApiErrorCodes
import app.logdate.client.networking.PasskeyApiException
import app.logdate.client.repository.account.AccountCreationRequest
import app.logdate.client.repository.account.PasskeyAccountRepository
import app.logdate.shared.model.LogDateAccount
import io.github.aakira.napier.Napier

/**
 * Use case for creating a new LogDate Cloud account using passkeys
 */
class CreatePasskeyAccountUseCase(
    private val passkeyAccountRepository: PasskeyAccountRepository,
) {
    sealed class Result {
        data class Success(
            val account: LogDateAccount,
        ) : Result()

        data class Error(
            val error: CreateAccountError,
        ) : Result()
    }

    sealed class CreateAccountError {
        data object UsernameTaken : CreateAccountError()

        data object UsernameInvalid : CreateAccountError()

        data object DisplayNameInvalid : CreateAccountError()

        data object PasskeyNotSupported : CreateAccountError()

        data object PasskeyCancelled : CreateAccountError()

        data object PasskeyFailed : CreateAccountError()

        data object NetworkError : CreateAccountError()

        /** Server-side rate limit hit (default: 5 signups/hour/IP). User should wait. */
        data object RateLimited : CreateAccountError()

        /** Server returned a 5xx; not user-fixable. Distinct from NetworkError so the UI can offer a different copy. */
        data object ServerError : CreateAccountError()

        data class Unknown(
            val message: String,
        ) : CreateAccountError()
    }

    suspend operator fun invoke(
        username: String,
        displayName: String,
        bio: String? = null,
        originalBio: String? = null,
    ): Result {
        return try {
            // Validate input
            val validationError = validateInput(username, displayName)
            if (validationError != null) {
                return Result.Error(validationError)
            }

            // Check username availability
            val availabilityResult = passkeyAccountRepository.checkUsernameAvailability(username)
            if (availabilityResult.isFailure) {
                Napier.e("Failed to check username availability", availabilityResult.exceptionOrNull())
                return Result.Error(CreateAccountError.NetworkError)
            }

            val isAvailable = availabilityResult.getOrThrow()
            if (!isAvailable) {
                return Result.Error(CreateAccountError.UsernameTaken)
            }

            // Create account with passkey
            val request =
                AccountCreationRequest(
                    username = username,
                    displayName = displayName,
                    bio = bio,
                    originalBio = originalBio,
                )
            val createResult = passkeyAccountRepository.createAccountWithPasskey(request)

            if (createResult.isSuccess) {
                val account = createResult.getOrThrow()
                Napier.i("Successfully created passkey account for user: ${account.username}")
                Result.Success(account)
            } else {
                val exception = createResult.exceptionOrNull()
                val error = mapExceptionToError(exception)
                Napier.e("Failed to create passkey account", exception)
                Result.Error(error)
            }
        } catch (e: Exception) {
            Napier.e("Unexpected error during account creation", e)
            Result.Error(CreateAccountError.Unknown(e.message ?: "Unknown error"))
        }
    }

    private fun validateInput(
        username: String,
        displayName: String,
    ): CreateAccountError? {
        // Username validation
        if (username.isBlank() || username.length < 3 || username.length > 50) {
            return CreateAccountError.UsernameInvalid
        }

        if (!username.matches(Regex("^[a-zA-Z0-9_]+$"))) {
            return CreateAccountError.UsernameInvalid
        }

        // Display name validation
        if (displayName.isBlank() || displayName.length > 100) {
            return CreateAccountError.DisplayNameInvalid
        }

        return null
    }

    private fun mapExceptionToError(exception: Throwable?): CreateAccountError {
        // Prefer the server's structured error code over substring-matching message text.
        // PasskeyApiException carries the {code, message} envelope from the server response;
        // older substring matching is a fallback for OS-level passkey errors that surface as
        // plain Throwable.message.
        if (exception is PasskeyApiException) {
            return when (exception.errorCode) {
                PasskeyApiErrorCodes.USERNAME_TAKEN -> CreateAccountError.UsernameTaken
                PasskeyApiErrorCodes.VALIDATION_ERROR -> CreateAccountError.UsernameInvalid
                PasskeyApiErrorCodes.PASSKEY_VERIFICATION_FAILED -> CreateAccountError.PasskeyFailed
                PasskeyApiErrorCodes.RATE_LIMIT_EXCEEDED -> CreateAccountError.RateLimited
                PasskeyApiErrorCodes.NETWORK_ERROR -> CreateAccountError.NetworkError
                PasskeyApiErrorCodes.SERVER_ERROR, PasskeyApiErrorCodes.SERVER_UNAVAILABLE -> CreateAccountError.ServerError
                else -> CreateAccountError.Unknown(exception.message ?: exception.errorCode)
            }
        }
        return when {
            exception?.message?.contains("USER_CANCELLED", ignoreCase = true) == true -> {
                CreateAccountError.PasskeyCancelled
            }

            exception?.message?.contains("NOT_SUPPORTED", ignoreCase = true) == true -> {
                CreateAccountError.PasskeyNotSupported
            }

            exception?.message?.contains("PASSKEY", ignoreCase = true) == true -> {
                CreateAccountError.PasskeyFailed
            }

            else -> {
                CreateAccountError.Unknown(exception?.message ?: "Unknown error")
            }
        }
    }
}
