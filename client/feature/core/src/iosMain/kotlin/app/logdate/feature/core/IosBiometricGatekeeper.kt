package app.logdate.feature.core

import app.logdate.client.text.UiText
import app.logdate.client.text.resolve
import io.github.aakira.napier.Napier
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import platform.Foundation.NSError
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAErrorAppCancel
import platform.LocalAuthentication.LAErrorAuthenticationFailed
import platform.LocalAuthentication.LAErrorBiometryNotAvailable
import platform.LocalAuthentication.LAErrorBiometryNotEnrolled
import platform.LocalAuthentication.LAErrorPasscodeNotSet
import platform.LocalAuthentication.LAErrorSystemCancel
import platform.LocalAuthentication.LAErrorUserCancel
import platform.LocalAuthentication.LAErrorUserFallback
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthentication

/**
 * iOS implementation of [BiometricGatekeeper] backed by `LocalAuthentication`.
 *
 * Uses [LAPolicyDeviceOwnerAuthentication] so the system prompt automatically falls back
 * to the device passcode when biometrics fail or are unavailable.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosBiometricGatekeeper : BiometricGatekeeper {
    private val _authState = MutableStateFlow(AppAuthState.NO_PROMPT_NEEDED)

    private val challenges = AuthenticationChallengeOwner { completeAuthenticationChallenge(_authState, it) {} }

    override val authState: StateFlow<AppAuthState> = _authState.asStateFlow()

    /** Resolves the prompt's words from string resources before the prompt is shown. */
    private val promptScope = MainScope()

    override fun authenticate(
        title: UiText,
        subtitle: UiText,
        cancelLabel: UiText,
        requireConfirmation: Boolean,
        requestEnrollmentIfNecessary: Boolean,
        description: UiText?,
        onResult: (AppAuthState) -> Unit,
    ) {
        val complete = challenges.begin(onResult) ?: return
        val context = LAContext()

        val canEvaluate =
            memScoped {
                val errorPtr = alloc<ObjCObjectVar<NSError?>>()
                val ok = context.canEvaluatePolicy(LAPolicyDeviceOwnerAuthentication, errorPtr.ptr)
                if (!ok) {
                    val error = errorPtr.value
                    val state = mapLAError(error)
                    Napier.w(
                        "LAContext cannot evaluate policy: code=${error?.code} domain=${error?.domain}",
                        tag = TAG,
                    )
                    complete(state)
                }
                ok
            }
        if (!canEvaluate) return

        promptScope.launch {
            context.localizedCancelTitle = cancelLabel.resolve()
            val reason = (description ?: subtitle).resolve()
            context.evaluatePolicy(LAPolicyDeviceOwnerAuthentication, reason) { success, error ->
                val state =
                    if (success) {
                        AppAuthState.AUTHENTICATED
                    } else {
                        Napier.i(
                            "LAContext authentication failed: code=${error?.code} domain=${error?.domain}",
                            tag = TAG,
                        )
                        mapLAError(error)
                    }
                complete(state)
            }
        }
    }

    override fun requestEnrollment() {
        // No-op on iOS: there is no public API to enroll biometrics. With
        // LAPolicyDeviceOwnerAuthentication, missing biometric enrollment is handled
        // automatically by falling back to the device passcode.
    }

    private fun mapLAError(error: NSError?): AppAuthState =
        when (error?.code) {
            LAErrorPasscodeNotSet,
            LAErrorBiometryNotAvailable,
            -> AppAuthState.UNSUPPORTED

            LAErrorBiometryNotEnrolled -> AppAuthState.REQUEST_ENROLLMENT

            LAErrorUserCancel,
            LAErrorAppCancel,
            LAErrorSystemCancel,
            LAErrorUserFallback,
            -> AppAuthState.REQUIRE_PROMPT

            LAErrorAuthenticationFailed -> AppAuthState.UNKNOWN

            else -> AppAuthState.UNKNOWN
        }

    private companion object {
        const val TAG = "IosBiometricGatekeeper"
    }
}
