package app.logdate.feature.core

import app.logdate.client.text.UiText
import app.logdate.client.text.toUiText
import kotlinx.coroutines.flow.StateFlow
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.biometric_prompt_default_subtitle
import logdate.client.feature.core.generated.resources.biometric_prompt_default_title
import logdate.client.ui.generated.resources.common_cancel
import logdate.client.ui.generated.resources.Res as UiRes

/**
 * An interface for managing biometric authentication.
 *
 * Each request receives its own terminal result. The last provider result is diagnostic state;
 * application lifecycle authorization belongs to AppViewModel.
 *
 * Implementations of this interface should handle the platform-specific APIs for biometric
 * authentication.
 */
interface BiometricGatekeeper {
    val authState: StateFlow<AppAuthState>

    /**
     * Performs biometric authentication.
     *
     * An overlapping native request is dismissed without replacing the active challenge owner.
     *
     * A successful challenge emits [AppAuthState.AUTHENTICATED]. Missing enrollment or an
     * unavailable authenticator must keep the app locked; they never disable its security setting.
     *
     * The prompt is shown by the system, outside composition, so implementations resolve [title],
     * [subtitle], [cancelLabel] and [description] to words where they build the prompt.
     */
    fun authenticate(
        title: UiText = Res.string.biometric_prompt_default_title.toUiText(),
        subtitle: UiText = Res.string.biometric_prompt_default_subtitle.toUiText(),
        cancelLabel: UiText = UiRes.string.common_cancel.toUiText(),
        requireConfirmation: Boolean = false,
        requestEnrollmentIfNecessary: Boolean = true,
        description: UiText? = null,
        onResult: (AppAuthState) -> Unit = {},
    )

    /**
     * Request that the user enroll in biometric authentication.
     */
    fun requestEnrollment()
}

/**
 * A flag representing the current state of the app's biometric authentication.
 *
 * UI components can use this state to determine how to
 */
enum class AppAuthState {
    /**
     * The user has not enabled biometric authentication.
     */
    NO_PROMPT_NEEDED,

    /**
     * The user has enabled biometric authentication, but has not authenticated in the current session.
     */
    REQUIRE_PROMPT,

    /**
     * The user has recently authenticated with biometrics in the current session.
     */
    AUTHENTICATED,

    /**
     * The user has not enrolled in biometric authentication.
     */
    REQUEST_ENROLLMENT,

    /**
     * The user's device requires a security update to use biometric authentication.
     */
    UPDATE_REQUIRED,

    /**
     * The user's device does not support biometric authentication.
     */
    UNSUPPORTED,

    /**
     * An unknown error has occurred.
     *
     * The user should be prompted to restart.
     */
    UNKNOWN,
}
