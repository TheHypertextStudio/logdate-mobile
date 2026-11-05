package app.logdate.feature.core.settings.account.recovery

sealed interface RecoveryPhraseUiState {
    data object Checking : RecoveryPhraseUiState

    data object NotOnThisDevice : RecoveryPhraseUiState

    data object Unreadable : RecoveryPhraseUiState

    data class Available(
        val reveal: PhraseRevealState = PhraseRevealState.Hidden,
    ) : RecoveryPhraseUiState
}

sealed interface PhraseRevealState {
    data object Hidden : PhraseRevealState

    data object Authenticating : PhraseRevealState

    data object Reading : PhraseRevealState

    data class Revealed(
        val words: List<String>,
    ) : PhraseRevealState

    data class Failed(
        val reason: RevealFailure,
    ) : PhraseRevealState
}

enum class RevealFailure {
    AUTHENTICATION_UNAVAILABLE,
    UNREADABLE,
}

/** Localized by the UI, which owns the platform prompt's presentation. */
data class RevealPrompt(
    val title: String,
    val subtitle: String,
    val description: String?,
)
