package app.logdate.client.intelligence

sealed interface AIResult<out T> {
    data class Success<T>(
        val value: T,
        val fromCache: Boolean = false,
    ) : AIResult<T>

    data class Error(
        val error: AIError,
        val throwable: Throwable? = null,
    ) : AIResult<Nothing>

    data class Unavailable(
        val reason: AIUnavailableReason,
    ) : AIResult<Nothing>
}

sealed interface AIUnavailableReason {
    data object NoNetwork : AIUnavailableReason

    data object DataSaverActive : AIUnavailableReason

    data object MissingCredentials : AIUnavailableReason

    data object InputTooLarge : AIUnavailableReason
}
