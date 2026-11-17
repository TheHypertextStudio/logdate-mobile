package app.logdate.client.intelligence

sealed interface AIError {
    data object Network : AIError

    data object Timeout : AIError

    data object RateLimited : AIError

    data object ProviderUnavailable : AIError

    data object InvalidResponse : AIError

    data object Unauthorized : AIError

    data object Unknown : AIError
}
