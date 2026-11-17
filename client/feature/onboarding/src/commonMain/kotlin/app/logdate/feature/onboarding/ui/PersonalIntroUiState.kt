package app.logdate.feature.onboarding.ui

import org.jetbrains.compose.resources.StringResource

/**
 * UI state for the personal introduction onboarding screen.
 *
 * This screen has a two-step flow: name collection, then bio collection with LLM processing.
 */
data class PersonalIntroUiState(
    val currentStep: PersonalIntroStep = PersonalIntroStep.Name,
    val name: String = "",
    val bio: String = "",
    val nameError: StringResource? = null,
    val bioError: StringResource? = null,
    val isProcessingLlm: Boolean = false,
    val llmResponse: String? = null,
    val llmError: String? = null,
    val isLoading: Boolean = false,
    val errorMessage: StringResource? = null,
) {
    val canContinueFromName: Boolean
        get() = name.trim().isNotEmpty() && nameError == null && !isLoading

    /**
     * A bio is something to offer, not a toll. Requiring one left anyone who did not want to write
     * about themselves unable to finish setting up the app at all.
     */
    val canContinueFromBio: Boolean
        get() = bioError == null && !isProcessingLlm && !isLoading

    val canFinish: Boolean
        get() = currentStep == PersonalIntroStep.LlmResponse && llmResponse != null && !isLoading
}

/**
 * Represents the current step in the personal introduction flow.
 */
sealed class PersonalIntroStep {
    /**
     * Step 1: Collecting the user's name.
     */
    data object Name : PersonalIntroStep()

    /**
     * Step 2: Collecting the user's bio.
     */
    data object Bio : PersonalIntroStep()

    /**
     * Step 3: Showing the LLM's friendly response and allowing the user to continue.
     */
    data object LlmResponse : PersonalIntroStep()
}

/**
 * Represents the result of LLM processing.
 */
sealed class LlmProcessingResult {
    data class Success(
        val response: String,
    ) : LlmProcessingResult()

    data class Error(
        val message: String,
    ) : LlmProcessingResult()
}
