package app.logdate.feature.core

import android.content.Intent
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.logdate.client.text.UiText
import app.logdate.client.text.resolve
import io.github.aakira.napier.Napier
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * An implementation of [BiometricGatekeeper] that uses the Android Biometric API.
 *
 * Whether app lock is on is not this class's business: `AppViewModel` reads the security level
 * from the datastore and decides when a prompt is required. This performs the challenge.
 */
class AndroidBiometricGatekeeper : BiometricGatekeeper {
    private var activityRef = WeakReference<FragmentActivity>(null)
    private val challenge = AuthenticationChallenge()
    private var pendingSuccess: (() -> Unit)? = null
    private var pendingCompletion: ((AppAuthState) -> Unit)? = null
    private val lifecycleObserver =
        LifecycleEventObserver { owner, event ->
            val host = owner as? FragmentActivity ?: return@LifecycleEventObserver
            if (activityRef.get() !== host) return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_PAUSE -> challenge.markHostPaused()
                Lifecycle.Event.ON_RESUME -> deliverPendingSuccess(host)
                Lifecycle.Event.ON_DESTROY -> {
                    challenge.invalidate()
                    pendingSuccess = null
                    pendingCompletion?.invoke(AppAuthState.REQUIRE_PROMPT)
                    pendingCompletion = null
                    activityRef.clear()
                    _authState.value = AppAuthState.REQUIRE_PROMPT
                }
                else -> Unit
            }
        }

    private val activity: FragmentActivity
        get() =
            activityRef.get()
                ?: throw IllegalStateException("Activity reference must be initialized using setActivity(FragmentActivity).")

    private val _authState = MutableStateFlow(AppAuthState.NO_PROMPT_NEEDED)

    private val challenges = AuthenticationChallengeOwner { completeAuthenticationChallenge(_authState, it) {} }

    override val authState: StateFlow<AppAuthState> = _authState

    /** Resolves the prompt's words from string resources before the prompt is shown. */
    private val promptScope = MainScope()

    private suspend fun promptInfo(
        title: UiText,
        subtitle: UiText,
        description: UiText?,
        requireConfirmation: Boolean,
        authenticators: Int,
    ): BiometricPrompt.PromptInfo =
        BiometricPrompt.PromptInfo
            .Builder()
            .setTitle(title.resolve())
            .setSubtitle(subtitle.resolve())
            .apply {
                if (description != null) {
                    setDescription(description.resolve())
                }
            }.setAllowedAuthenticators(authenticators)
            .setConfirmationRequired(requireConfirmation)
            .build()

    /**
     * Authenticates the user using biometric authentication.
     *
     * Enrollment and unavailable authentication keep the journal locked. An enrolled device
     * credential remains usable when the biometric sensor cannot authenticate.
     */
    override fun authenticate(
        title: UiText,
        subtitle: UiText,
        cancelLabel: UiText,
        requireConfirmation: Boolean,
        requestEnrollmentIfNecessary: Boolean,
        description: UiText?,
        onResult: (AppAuthState) -> Unit,
    ) {
        val owned = challenges.begin(onResult) ?: return
        val complete: (AppAuthState) -> Unit = { result ->
            pendingCompletion = null
            owned(result)
        }
        pendingCompletion = complete
        try {
            authenticateOwned(title, subtitle, requireConfirmation, requestEnrollmentIfNecessary, description, complete)
        } catch (error: Exception) {
            Napier.e("Could not start native authentication", error, tag = TAG)
            complete(AppAuthState.UNKNOWN)
        }
    }

    private fun authenticateOwned(
        title: UiText,
        subtitle: UiText,
        requireConfirmation: Boolean,
        requestEnrollmentIfNecessary: Boolean,
        description: UiText?,
        complete: (AppAuthState) -> Unit,
    ) {
        val host = activity
        val attempt = challenge.begin()
        pendingSuccess = null
        val biometricManager = BiometricManager.from(host)
        val executor = ContextCompat.getMainExecutor(host)
        val callback =
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(
                    errorCode: Int,
                    errString: CharSequence,
                ) {
                    super.onAuthenticationError(errorCode, errString)
                    if (!isCurrentAttempt(attempt, host)) return
                    challenge.invalidate()
                    pendingSuccess = null
                    Napier.e("Biometric authentication error: $errorCode, $errString", tag = TAG)
                    val state =
                        when (errorCode) {
                            BiometricPrompt.ERROR_CANCELED,
                            BiometricPrompt.ERROR_USER_CANCELED,
                            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                            -> AppAuthState.REQUIRE_PROMPT
                            else -> AppAuthState.UNKNOWN
                        }
                    complete(state)
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    if (!isCurrentAttempt(attempt, host)) return
                    val accepted =
                        challenge.recordSuccess(
                            attempt,
                            hostResumed = host.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
                            deviceCredential = result.authenticationType == BiometricPrompt.AUTHENTICATION_RESULT_TYPE_DEVICE_CREDENTIAL,
                        )
                    if (!accepted) {
                        complete(AppAuthState.REQUIRE_PROMPT)
                        return
                    }
                    pendingSuccess = {
                        complete(AppAuthState.AUTHENTICATED)
                    }
                    deliverPendingSuccess(host)
                }
            }
        // Reset a previous success so another successful challenge produces a fresh state event.
        _authState.value = AppAuthState.REQUIRE_PROMPT
        when (val availability = resolveAndroidAuthenticationAvailability(biometricManager::canAuthenticate)) {
            is AndroidAuthenticationAvailability.Ready -> {
                promptScope.launch {
                    try {
                        val info = promptInfo(title, subtitle, description, requireConfirmation, availability.authenticators)
                        if (!isCurrentAttempt(attempt, host)) return@launch
                        if (!host.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            challenge.invalidate()
                            complete(AppAuthState.REQUIRE_PROMPT)
                            return@launch
                        }
                        BiometricPrompt(host, executor, callback).authenticate(info)
                    } catch (error: Exception) {
                        Napier.e("Could not show native authentication", error, tag = TAG)
                        challenge.invalidate()
                        complete(AppAuthState.UNKNOWN)
                    }
                }
            }
            is AndroidAuthenticationAvailability.Unavailable -> {
                challenge.invalidate()
                complete(availability.state)
                if (availability.state == AppAuthState.REQUEST_ENROLLMENT && requestEnrollmentIfNecessary) {
                    requestEnrollment()
                }
            }
        }
    }

    private fun isCurrentAttempt(
        attempt: Long,
        host: FragmentActivity,
    ): Boolean = challenge.isCurrent(attempt) && activityRef.get() === host && !host.isDestroyed && !host.isFinishing

    private fun deliverPendingSuccess(host: FragmentActivity) {
        if (activityRef.get() !== host ||
            host.isDestroyed ||
            host.isFinishing ||
            !host.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) {
            return
        }
        if (!challenge.consumeSuccess()) return
        val publish = pendingSuccess
        pendingSuccess = null
        publish?.invoke()
    }

    /**
     * Requests that the user enroll in biometric authentication.
     *
     * This will launch the system biometric enrollment activity.
     */
    override fun requestEnrollment() {
        // Launched as a plain intent rather than through registerForActivityResult: this runs
        // from authenticate(), long after the activity has STARTED, and registering a launcher
        // that late throws. The result is not needed either -- when the user comes back,
        // authenticate() re-runs canAuthenticate() and sees whatever they actually enrolled.
        val intent =
            Intent(Settings.ACTION_BIOMETRIC_ENROLL).apply {
                putExtra(
                    Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                )
            }
        runCatching { activity.startActivity(intent) }
            .onFailure { Napier.e("Could not open biometric enrollment", it, tag = TAG) }
    }

    /**
     * Sets the [FragmentActivity] that will be used to launch the biometric prompt.
     *
     * This must be called before calling [authenticate] or [requestEnrollment].
     */
    fun setActivity(fragmentActivity: FragmentActivity) {
        if (activityRef.get() === fragmentActivity) return
        activityRef.get()?.lifecycle?.removeObserver(lifecycleObserver)
        challenge.invalidate()
        pendingSuccess = null
        pendingCompletion?.invoke(AppAuthState.REQUIRE_PROMPT)
        pendingCompletion = null
        activityRef.clear()
        activityRef = WeakReference(fragmentActivity)
        _authState.value = AppAuthState.REQUIRE_PROMPT
        fragmentActivity.lifecycle.addObserver(lifecycleObserver)
    }

    private companion object {
        const val TAG = "BiometricGatekeeper"
    }
}
