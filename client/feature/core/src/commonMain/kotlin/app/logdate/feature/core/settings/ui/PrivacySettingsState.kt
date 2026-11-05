package app.logdate.feature.core.settings.ui

data class PrivacySettingsState(
    val biometrics: PrivacyPreferenceState = PrivacyPreferenceState.Loading,
    val systemSearchVisibility: PrivacyPreferenceState = PrivacyPreferenceState.Loading,
    val showSystemSearchVisibilityToggle: Boolean = false,
)

sealed interface PrivacyPreferenceState {
    data object Loading : PrivacyPreferenceState

    data object Unavailable : PrivacyPreferenceState

    data class Available(
        val enabled: Boolean,
        val operation: PrivacyPreferenceOperation = PrivacyPreferenceOperation.Idle,
        val isRefreshing: Boolean = false,
        val refreshFailed: Boolean = false,
    ) : PrivacyPreferenceState
}

sealed interface PrivacyPreferenceOperation {
    data object Idle : PrivacyPreferenceOperation

    data object Authenticating : PrivacyPreferenceOperation

    data class Saving(
        val targetEnabled: Boolean,
    ) : PrivacyPreferenceOperation

    data class Failed(
        val targetEnabled: Boolean,
        val reason: PrivacyPreferenceFailure,
    ) : PrivacyPreferenceOperation
}

enum class PrivacyPreferenceFailure {
    SAVE,
    AUTHENTICATION,
    AUTHENTICATION_UNAVAILABLE,
    ENROLLMENT_REQUIRED,
    SECURITY_UPDATE_REQUIRED,
}

data class PrivacyAuthenticationPrompt(
    val title: String,
    val subtitle: String,
    val cancelLabel: String,
    val description: String,
)
