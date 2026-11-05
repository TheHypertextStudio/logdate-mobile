package app.logdate.feature.core

sealed interface GlobalAppUiState

/**
 * UI state for properties that span the entire client app UX.
 */
data class GlobalAppUiLoadedState(
    val isLoaded: Boolean = false,
    /**
     * Indicates whether the app is currently connected to the internet.
     */
    val isOnline: Boolean = false,
    /**
     * The current authentication state of the app.
     *
     * This can be used to determine whether the user needs to authenticate (e.g. use biometrics
     * or a password) to access the app.
     */
    val authState: AppAuthState = AppAuthState.NO_PROMPT_NEEDED,
    /**
     * Whether the user has set up the app for their use.
     *
     * If this is false, the user will be prompted to set up the app when they first open it.
     */
    val isOnboarded: Boolean = false,
    /**
     * The user's display name, used for the lock screen greeting.
     */
    val displayName: String = "",
    val unlockState: AppUnlockState = AppUnlockState.Idle,
) : GlobalAppUiState

data object GlobalAppUiLoadingState : GlobalAppUiState

val GlobalAppUiLoadedState.requiresUnlock: Boolean
    get() = !isAppUnlocked

val GlobalAppUiLoadedState.isAppUnlocked: Boolean
    get() = authState == AppAuthState.NO_PROMPT_NEEDED || authState == AppAuthState.AUTHENTICATED

/** Feedback for the app's own unlock request, independent of sensitive-action authentication. */
sealed interface AppUnlockState {
    data object Idle : AppUnlockState

    data object Authenticating : AppUnlockState

    data object Failed : AppUnlockState
}
