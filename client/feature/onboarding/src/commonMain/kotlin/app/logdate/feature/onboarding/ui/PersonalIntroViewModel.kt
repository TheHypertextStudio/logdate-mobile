package app.logdate.feature.onboarding.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.domain.onboarding.ProcessPersonalIntroductionUseCase
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import logdate.client.feature.onboarding.generated.resources.Res
import logdate.client.feature.onboarding.generated.resources.onboarding_personal_intro_bio_required
import logdate.client.feature.onboarding.generated.resources.onboarding_personal_intro_name_required
import logdate.client.feature.onboarding.generated.resources.onboarding_personal_intro_profile_setup_failed
import logdate.client.feature.onboarding.generated.resources.onboarding_personal_intro_save_bio_failed
import logdate.client.feature.onboarding.generated.resources.onboarding_personal_intro_save_failed
import logdate.client.feature.onboarding.generated.resources.onboarding_personal_intro_save_name_failed
import org.jetbrains.compose.resources.StringResource

/**
 * ViewModel for the personal introduction onboarding screen.
 *
 * Manages the two-step flow: name collection, bio collection, and LLM processing
 * to create a friendly, personal introduction experience.
 */
class PersonalIntroViewModel(
    private val processPersonalIntroductionUseCase: ProcessPersonalIntroductionUseCase,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val _uiState = MutableStateFlow(savedStateHandle.restoreUiState())
    val uiState: StateFlow<PersonalIntroUiState> = _uiState.asStateFlow()

    /**
     * Update the user's name input.
     */
    fun onNameChanged(name: String) {
        updateUiState {
            it.copy(
                name = name,
                nameError = null, // Clear error when user starts typing
            )
        }
    }

    /**
     * Update the user's bio input.
     */
    fun onBioChanged(bio: String) {
        updateUiState {
            it.copy(
                bio = bio,
                bioError = null, // Clear error when user starts typing
            )
        }
    }

    /**
     * Proceed from name step to bio step.
     */
    fun proceedToBio() {
        val currentState = _uiState.value

        if (currentState.name.trim().isEmpty()) {
            updateUiState { currentState.copy(nameError = Res.string.onboarding_personal_intro_name_required) }
            return
        }

        updateUiState {
            currentState.copy(
                currentStep = PersonalIntroStep.Bio,
                nameError = null,
            )
        }
    }

    /**
     * Go back from bio step to name step.
     */
    fun goBackToName() {
        updateUiState {
            it.copy(
                currentStep = PersonalIntroStep.Name,
                bioError = null,
                llmError = null,
            )
        }
    }

    /**
     * Process the bio with LLM and complete the entire introduction flow.
     */
    fun processWithLlm() {
        val currentState = _uiState.value

        if (currentState.bio.trim().isEmpty()) {
            updateUiState { currentState.copy(bioError = Res.string.onboarding_personal_intro_bio_required) }
            return
        }

        viewModelScope.launch {
            try {
                updateUiState {
                    currentState.copy(
                        isProcessingLlm = true,
                        bioError = null,
                        llmError = null,
                    )
                }

                // Process the entire introduction using the domain use case
                val result =
                    processPersonalIntroductionUseCase(
                        name = currentState.name.trim(),
                        bio = currentState.bio.trim(),
                    )

                when (result) {
                    is ProcessPersonalIntroductionUseCase.Result.Success -> {
                        updateUiState {
                            _uiState.value.copy(
                                isProcessingLlm = false,
                                llmResponse = result.data.llmResponse,
                                currentStep = PersonalIntroStep.LlmResponse,
                            )
                        }
                        Napier.d("Personal introduction processed successfully")
                    }

                    is ProcessPersonalIntroductionUseCase.Result.Error -> {
                        updateUiState {
                            _uiState.value.copy(
                                isProcessingLlm = false,
                                errorMessage = errorMessageFor(result.reason),
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Napier.e("Failed to process personal introduction", e)
                updateUiState {
                    _uiState.value.copy(
                        isProcessingLlm = false,
                        errorMessage = errorMessageFor(ProcessPersonalIntroductionUseCase.ErrorReason.UnexpectedFailure),
                    )
                }
            }
        }
    }

    /**
     * Complete the introduction - this is now handled by processWithLlm().
     * This method is kept for backward compatibility with the UI.
     */
    fun completeIntroduction() {
        // Introduction is already completed in processWithLlm()
        // This method is kept for UI compatibility but doesn't need to do anything
        Napier.d("Introduction already completed")
    }

    /**
     * Clear error messages.
     */
    fun clearError() {
        updateUiState { it.copy(errorMessage = null) }
    }

    private fun errorMessageFor(reason: ProcessPersonalIntroductionUseCase.ErrorReason): StringResource =
        when (reason) {
            ProcessPersonalIntroductionUseCase.ErrorReason.SaveDisplayNameFailed -> {
                Res.string.onboarding_personal_intro_save_name_failed
            }

            ProcessPersonalIntroductionUseCase.ErrorReason.SaveBioFailed -> {
                Res.string.onboarding_personal_intro_save_bio_failed
            }

            ProcessPersonalIntroductionUseCase.ErrorReason.ProfileUpdateFailed -> {
                Res.string.onboarding_personal_intro_profile_setup_failed
            }

            ProcessPersonalIntroductionUseCase.ErrorReason.UnexpectedFailure -> {
                Res.string.onboarding_personal_intro_save_failed
            }
        }

    private fun updateUiState(update: (PersonalIntroUiState) -> PersonalIntroUiState) {
        val newState = update(_uiState.value)
        _uiState.value = newState
        savedStateHandle.persistUiState(newState)
    }
}

private const val PERSONAL_INTRO_STEP_KEY = "personal_intro_step"
private const val PERSONAL_INTRO_NAME_KEY = "personal_intro_name"
private const val PERSONAL_INTRO_BIO_KEY = "personal_intro_bio"
private const val PERSONAL_INTRO_LLM_RESPONSE_KEY = "personal_intro_llm_response"

private fun SavedStateHandle.restoreUiState(): PersonalIntroUiState =
    PersonalIntroUiState(
        currentStep =
            when (get<String>(PERSONAL_INTRO_STEP_KEY)) {
                "bio" -> PersonalIntroStep.Bio
                "llm_response" -> PersonalIntroStep.LlmResponse
                else -> PersonalIntroStep.Name
            },
        name = get(PERSONAL_INTRO_NAME_KEY) ?: "",
        bio = get(PERSONAL_INTRO_BIO_KEY) ?: "",
        llmResponse = get(PERSONAL_INTRO_LLM_RESPONSE_KEY),
    )

private fun SavedStateHandle.persistUiState(state: PersonalIntroUiState) {
    set(
        PERSONAL_INTRO_STEP_KEY,
        when (state.currentStep) {
            PersonalIntroStep.Name -> "name"
            PersonalIntroStep.Bio -> "bio"
            PersonalIntroStep.LlmResponse -> "llm_response"
        },
    )
    set(PERSONAL_INTRO_NAME_KEY, state.name)
    set(PERSONAL_INTRO_BIO_KEY, state.bio)
    set(PERSONAL_INTRO_LLM_RESPONSE_KEY, state.llmResponse)
}
