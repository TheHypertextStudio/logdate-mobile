package app.logdate.client.domain.account

import app.logdate.client.networking.PasskeyApiErrorCodes
import app.logdate.client.networking.PasskeyApiException
import app.logdate.client.repository.account.LocalDataAdoptionRequiredException
import app.logdate.client.repository.account.PasskeyAccountRepository
import app.logdate.shared.model.LogDateAccount
import io.github.aakira.napier.Napier

/**
 * Use case for authenticating with an existing LogDate Cloud account using passkeys
 */
class AuthenticateWithPasskeyUseCase(
    private val passkeyAccountRepository: PasskeyAccountRepository,
) {
    sealed class Result {
        data class Success(
            val account: LogDateAccount,
        ) : Result()

        data class Error(
            val error: AuthenticationError,
        ) : Result()
    }

    sealed class AuthenticationError {
        object PasskeyNotSupported : AuthenticationError()

        object PasskeyCancelled : AuthenticationError()

        object PasskeyFailed : AuthenticationError()

        object NoCredentialsFound : AuthenticationError()

        object AccountNotFound : AuthenticationError()

        object NetworkError : AuthenticationError()

        /** The server was reached but did not answer in time, or answered with a server error. */
        object ServerUnavailable : AuthenticationError()

        /**
         * This device has entries that belong to no account yet. Signing in makes them part of the
         * account, so the caller has to confirm before retrying with [adoptLocalData].
         */
        object LocalDataNeedsAdoption : AuthenticationError()

        data class Unknown(
            val message: String,
        ) : AuthenticationError()
    }

    suspend operator fun invoke(
        username: String? = null,
        adoptLocalData: Boolean = false,
    ): Result =
        try {
            val authResult = passkeyAccountRepository.authenticateWithPasskey(username, adoptLocalData)

            if (authResult.isSuccess) {
                val account = authResult.getOrThrow()
                Napier.i("Successfully authenticated with passkey for user: ${account.username}")
                Result.Success(account)
            } else {
                val exception = authResult.exceptionOrNull()
                val error = mapExceptionToError(exception)
                Napier.e("Failed to authenticate with passkey", exception)
                Result.Error(error)
            }
        } catch (e: Exception) {
            Napier.e("Unexpected error during passkey authentication", e)
            Result.Error(AuthenticationError.Unknown(e.message ?: "Unknown error"))
        }

    private fun mapExceptionToError(exception: Throwable?): AuthenticationError {
        // The API client's structured code says what went wrong with the request; message matching
        // below is only for the platform's credential errors, which have no code.
        if (exception is PasskeyApiException) {
            when (exception.errorCode) {
                PasskeyApiErrorCodes.NETWORK_ERROR -> return AuthenticationError.NetworkError
                PasskeyApiErrorCodes.SERVER_UNAVAILABLE -> return AuthenticationError.ServerUnavailable
                PasskeyApiErrorCodes.ACCOUNT_NOT_FOUND -> return AuthenticationError.AccountNotFound
            }
        }
        return when {
            exception is LocalDataAdoptionRequiredException -> {
                AuthenticationError.LocalDataNeedsAdoption
            }

            exception?.message?.contains("USER_CANCELLED", ignoreCase = true) == true -> {
                AuthenticationError.PasskeyCancelled
            }

            exception?.message?.contains("NOT_SUPPORTED", ignoreCase = true) == true -> {
                AuthenticationError.PasskeyNotSupported
            }

            exception?.message?.contains("NOT_ALLOWED", ignoreCase = true) == true -> {
                AuthenticationError.NoCredentialsFound
            }

            exception?.message?.contains("ACCOUNT_NOT_FOUND", ignoreCase = true) == true -> {
                AuthenticationError.AccountNotFound
            }

            exception?.message?.contains("NETWORK_ERROR", ignoreCase = true) == true -> {
                AuthenticationError.NetworkError
            }

            exception?.message?.contains("PASSKEY", ignoreCase = true) == true -> {
                AuthenticationError.PasskeyFailed
            }

            else -> {
                AuthenticationError.Unknown(exception?.message ?: "Unknown error")
            }
        }
    }
}
