package app.logdate.feature.core.settings.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.datastore.LogdatePreferencesDataSource
import app.logdate.client.repository.user.UserStateRepository
import app.logdate.client.text.toUiText
import app.logdate.feature.core.AppAuthState
import app.logdate.feature.core.BiometricGatekeeper
import app.logdate.shared.model.user.AppSecurityLevel
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PrivacySettingsViewModel(
    private val preferencesDataSource: LogdatePreferencesDataSource,
    private val userStateRepository: UserStateRepository,
    private val biometricGatekeeper: BiometricGatekeeper,
    private val supportsSystemSearchVisibilityToggle: Boolean = false,
) : ViewModel() {
    private val mutableState =
        MutableStateFlow(
            PrivacySettingsState(showSystemSearchVisibilityToggle = supportsSystemSearchVisibilityToggle),
        )
    val state: StateFlow<PrivacySettingsState> = mutableState.asStateFlow()
    private var biometricObservation: Job? = null
    private var searchObservation: Job? = null
    private var authenticationRequest = 0L
    private val observationVersions = mutableMapOf<Preference, Long>()

    init {
        observeBiometrics()
        observeSystemSearchVisibility()
    }

    fun setBiometricEnabled(
        enabled: Boolean,
        prompt: PrivacyAuthenticationPrompt,
    ) {
        val current = preference(Preference.BIOMETRICS) as? PrivacyPreferenceState.Available ?: return
        if (current.operation.isPending()) return
        if (enabled == current.enabled) {
            setOperation(Preference.BIOMETRICS, PrivacyPreferenceOperation.Idle)
            return
        }
        if (!enabled) {
            save(Preference.BIOMETRICS, false)
            return
        }

        val request = ++authenticationRequest
        setOperation(Preference.BIOMETRICS, PrivacyPreferenceOperation.Authenticating)
        try {
            biometricGatekeeper.authenticate(
                title = prompt.title.toUiText(),
                subtitle = prompt.subtitle.toUiText(),
                cancelLabel = prompt.cancelLabel.toUiText(),
                description = prompt.description.toUiText(),
                onResult = { result ->
                    if (
                        request == authenticationRequest &&
                        viewModelScope.isActive &&
                        (preference(Preference.BIOMETRICS) as? PrivacyPreferenceState.Available)?.operation ==
                        PrivacyPreferenceOperation.Authenticating
                    ) {
                        handleAuthenticationResult(result)
                    }
                },
            )
        } catch (cancelled: CancellationException) {
            cancelAuthentication()
            throw cancelled
        } catch (error: Exception) {
            Napier.e("Unable to authenticate a biometric preference change", error)
            if (
                request == authenticationRequest &&
                (preference(Preference.BIOMETRICS) as? PrivacyPreferenceState.Available)?.operation ==
                PrivacyPreferenceOperation.Authenticating
            ) {
                setOperation(
                    Preference.BIOMETRICS,
                    PrivacyPreferenceOperation.Failed(true, PrivacyPreferenceFailure.AUTHENTICATION),
                )
            }
        }
    }

    fun setSystemSearchVisibilityEnabled(enabled: Boolean) {
        if (!supportsSystemSearchVisibilityToggle) return
        val current = preference(Preference.SYSTEM_SEARCH) as? PrivacyPreferenceState.Available ?: return
        if (current.operation.isPending()) return
        if (enabled == current.enabled) {
            setOperation(Preference.SYSTEM_SEARCH, PrivacyPreferenceOperation.Idle)
            return
        }
        save(Preference.SYSTEM_SEARCH, enabled)
    }

    fun retryBiometrics(prompt: PrivacyAuthenticationPrompt) {
        val current = preference(Preference.BIOMETRICS) as? PrivacyPreferenceState.Available
        val failure = current?.operation as? PrivacyPreferenceOperation.Failed
        if (failure != null) {
            setBiometricEnabled(failure.targetEnabled, prompt)
        } else {
            observeBiometrics()
        }
    }

    fun retrySystemSearchVisibility() {
        val current = preference(Preference.SYSTEM_SEARCH) as? PrivacyPreferenceState.Available
        val failure = current?.operation as? PrivacyPreferenceOperation.Failed
        if (failure != null) {
            setSystemSearchVisibilityEnabled(failure.targetEnabled)
        } else {
            observeSystemSearchVisibility()
        }
    }

    fun cancelAuthentication() {
        authenticationRequest++
        val current = preference(Preference.BIOMETRICS) as? PrivacyPreferenceState.Available
        if (current?.operation == PrivacyPreferenceOperation.Authenticating) {
            setOperation(Preference.BIOMETRICS, PrivacyPreferenceOperation.Idle)
        }
    }

    override fun onCleared() {
        cancelAuthentication()
        super.onCleared()
    }

    private fun handleAuthenticationResult(result: AppAuthState) {
        val failure =
            when (result) {
                AppAuthState.AUTHENTICATED -> {
                    save(Preference.BIOMETRICS, true)
                    return
                }
                AppAuthState.REQUIRE_PROMPT, AppAuthState.NO_PROMPT_NEEDED -> {
                    setOperation(Preference.BIOMETRICS, PrivacyPreferenceOperation.Idle)
                    return
                }
                AppAuthState.REQUEST_ENROLLMENT -> PrivacyPreferenceFailure.ENROLLMENT_REQUIRED
                AppAuthState.UPDATE_REQUIRED -> PrivacyPreferenceFailure.SECURITY_UPDATE_REQUIRED
                AppAuthState.UNSUPPORTED -> PrivacyPreferenceFailure.AUTHENTICATION_UNAVAILABLE
                AppAuthState.UNKNOWN -> PrivacyPreferenceFailure.AUTHENTICATION
            }
        setOperation(Preference.BIOMETRICS, PrivacyPreferenceOperation.Failed(true, failure))
    }

    private fun save(
        preference: Preference,
        enabled: Boolean,
    ) {
        val observedVersion = observationVersions[preference]
        setOperation(preference, PrivacyPreferenceOperation.Saving(enabled))
        viewModelScope.launch {
            try {
                when (preference) {
                    Preference.BIOMETRICS -> userStateRepository.setBiometricEnabled(enabled)
                    Preference.SYSTEM_SEARCH -> preferencesDataSource.setSystemSearchVisibilityEnabled(enabled)
                }
                updatePreference(preference) { current ->
                    if (current !is PrivacyPreferenceState.Available) return@updatePreference current
                    current.copy(
                        enabled = if (observedVersion == observationVersions[preference]) enabled else current.enabled,
                        operation = PrivacyPreferenceOperation.Idle,
                    )
                }
            } catch (cancelled: CancellationException) {
                setOperation(preference, PrivacyPreferenceOperation.Idle)
                throw cancelled
            } catch (error: Exception) {
                Napier.e("Unable to save privacy preference $preference", error)
                setOperation(preference, PrivacyPreferenceOperation.Failed(enabled, PrivacyPreferenceFailure.SAVE))
            }
        }
    }

    private fun observeBiometrics() {
        if (biometricObservation?.isActive == true) return
        biometricObservation =
            observePreference(
                Preference.BIOMETRICS,
                userStateRepository.userData.map { it.securityLevel == AppSecurityLevel.BIOMETRIC },
            )
    }

    private fun observeSystemSearchVisibility() {
        if (!supportsSystemSearchVisibilityToggle || searchObservation?.isActive == true) return
        searchObservation =
            observePreference(
                Preference.SYSTEM_SEARCH,
                preferencesDataSource.observeSystemSearchVisibilityEnabled(),
            )
    }

    private fun observePreference(
        preference: Preference,
        values: Flow<Boolean>,
    ): Job =
        viewModelScope.launch {
            updatePreference(preference) { previous ->
                (previous as? PrivacyPreferenceState.Available)?.copy(isRefreshing = true, refreshFailed = false)
                    ?: PrivacyPreferenceState.Loading
            }
            try {
                values.collect { enabled ->
                    observationVersions[preference] = (observationVersions[preference] ?: 0) + 1
                    updatePreference(preference) { previous ->
                        PrivacyPreferenceState.Available(
                            enabled = enabled,
                            operation = (previous as? PrivacyPreferenceState.Available)?.operation ?: PrivacyPreferenceOperation.Idle,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Napier.e("Unable to observe privacy preference $preference", error)
                updatePreference(preference) { previous ->
                    (previous as? PrivacyPreferenceState.Available)?.copy(isRefreshing = false, refreshFailed = true)
                        ?: PrivacyPreferenceState.Unavailable
                }
            }
        }

    private fun preference(preference: Preference): PrivacyPreferenceState =
        when (preference) {
            Preference.BIOMETRICS -> mutableState.value.biometrics
            Preference.SYSTEM_SEARCH -> mutableState.value.systemSearchVisibility
        }

    private fun setOperation(
        preference: Preference,
        operation: PrivacyPreferenceOperation,
    ) {
        updatePreference(preference) { current ->
            (current as? PrivacyPreferenceState.Available)?.copy(operation = operation) ?: current
        }
    }

    private fun updatePreference(
        preference: Preference,
        transform: (PrivacyPreferenceState) -> PrivacyPreferenceState,
    ) {
        mutableState.update { current ->
            when (preference) {
                Preference.BIOMETRICS -> current.copy(biometrics = transform(current.biometrics))
                Preference.SYSTEM_SEARCH -> current.copy(systemSearchVisibility = transform(current.systemSearchVisibility))
            }
        }
    }

    private fun PrivacyPreferenceOperation.isPending(): Boolean =
        this == PrivacyPreferenceOperation.Authenticating || this is PrivacyPreferenceOperation.Saving

    private enum class Preference {
        BIOMETRICS,
        SYSTEM_SEARCH,
    }
}
