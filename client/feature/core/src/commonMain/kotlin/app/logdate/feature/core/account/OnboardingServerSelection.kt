package app.logdate.feature.core.account

import app.logdate.feature.core.settings.ui.ServerConfigurationCoordinator
import app.logdate.feature.core.settings.ui.ServerPreset
import app.logdate.feature.core.settings.ui.ServerValidationState
import app.logdate.shared.model.ServerDescriptor
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.MutableStateFlow
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.account_failure_server_url_empty
import org.jetbrains.compose.resources.StringResource

/**
 * The server an account lives on, for the account onboarding flow: which one is chosen, whether it
 * can be used, and saving it. Every result is written to [uiState].
 *
 * [resolveStep] turns the step to show next into the one the flow really lands on.
 */
internal class OnboardingServerSelection(
    private val uiState: MutableStateFlow<CloudAccountOnboardingUiState>,
    private val coordinator: ServerConfigurationCoordinator,
    private val resolveStep: (OnboardingStep) -> OnboardingStep,
) {
    fun selectPreset(preset: ServerPreset) {
        uiState.value =
            uiState.value.copy(
                serverSelectionState =
                    uiState.value.serverSelectionState.copy(
                        selectedPreset = preset,
                        validationState = ServerValidationState.Idle,
                    ),
                errorMessage = null,
            )
    }

    fun updateCustomUrl(url: String) {
        uiState.value =
            uiState.value.copy(
                serverSelectionState =
                    uiState.value.serverSelectionState.copy(
                        customServerUrl = url,
                        validationState = ServerValidationState.Idle,
                    ),
                errorMessage = null,
            )
    }

    /**
     * Points the app at the server a sign-in name belongs to, and reports whether it can be used.
     * A server that cannot be used leaves its problem in the UI state.
     */
    suspend fun useServerNamedBy(name: SignInName): Boolean {
        val preset = if (name.isOnLogDateCloud) ServerPreset.PRODUCTION else ServerPreset.CUSTOM
        uiState.value =
            uiState.value.copy(
                serverSelectionState =
                    uiState.value.serverSelectionState.copy(
                        selectedPreset = preset,
                        customServerUrl = if (preset == ServerPreset.CUSTOM) name.server.orEmpty() else "",
                        validationState = ServerValidationState.Validating,
                    ),
            )
        save(nextStep = uiState.value.currentStep, preset = preset) {
            when (preset) {
                ServerPreset.PRODUCTION -> coordinator.saveLogDateCloudSelection()
                ServerPreset.CUSTOM -> coordinator.validateAndSaveCustomServer(name.server.orEmpty())
            }
        }
        return uiState.value.serverSelectionState.validationState is ServerValidationState.Success
    }

    /** Checks and saves the chosen server, then moves the flow to [nextStep] if it can be used. */
    suspend fun confirm(nextStep: OnboardingStep) {
        val selection = uiState.value.serverSelectionState
        when (selection.selectedPreset) {
            ServerPreset.PRODUCTION -> {
                save(nextStep, ServerPreset.PRODUCTION) { coordinator.saveLogDateCloudSelection() }
            }

            ServerPreset.CUSTOM -> {
                val customServerUrl = selection.customServerUrl
                if (customServerUrl.isBlank()) {
                    update(
                        validationState = ServerValidationState.Error(Res.string.account_failure_server_url_empty),
                        errorMessage = Res.string.account_failure_server_url_empty,
                    )
                    return
                }

                update(validationState = ServerValidationState.Validating, errorMessage = null)
                save(nextStep, ServerPreset.CUSTOM) { coordinator.validateAndSaveCustomServer(customServerUrl) }
            }
        }
    }

    private suspend fun save(
        nextStep: OnboardingStep,
        preset: ServerPreset,
        save: suspend () -> Result<ServerConfigurationCoordinator.SaveResult>,
    ) {
        save()
            .onSuccess { result ->
                update(
                    validationState = ServerValidationState.Success(result.serverVersion),
                    errorMessage = null,
                    nextStep = resolveStep(nextStep),
                    activeServerDescriptor = result.descriptor,
                    customServerUrl =
                        result.serverOrigin.takeIf {
                            uiState.value.serverSelectionState.selectedPreset == ServerPreset.CUSTOM
                        },
                )
            }.onFailure { error ->
                Napier.w("Server validation failed", error)
                val message = serverSelectionFailureMessage(error, preset)
                update(validationState = ServerValidationState.Error(message), errorMessage = message)
            }
    }

    private fun update(
        validationState: ServerValidationState,
        errorMessage: StringResource?,
        nextStep: OnboardingStep? = null,
        activeServerDescriptor: ServerDescriptor? = uiState.value.serverSelectionState.activeServerDescriptor,
        customServerUrl: String? = null,
    ) {
        uiState.value =
            uiState.value.copy(
                currentStep = nextStep ?: uiState.value.currentStep,
                errorMessage = errorMessage,
                serverSelectionState =
                    uiState.value.serverSelectionState.copy(
                        validationState = validationState,
                        activeServerDescriptor = activeServerDescriptor,
                        customServerUrl = customServerUrl ?: uiState.value.serverSelectionState.customServerUrl,
                    ),
            )
    }
}
