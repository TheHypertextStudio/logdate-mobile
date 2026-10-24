@file:Suppress("ktlint:standard:function-naming", "ktlint:standard:no-wildcard-imports")

package app.logdate.feature.onboarding.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.WavingHand
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.logdate.ui.step.StepBusyButton
import app.logdate.ui.step.StepHeroIcon
import app.logdate.ui.step.StepProgress
import app.logdate.ui.step.StepScaffold
import app.logdate.ui.theme.LogDateTheme
import app.logdate.ui.theme.Spacing
import kotlinx.coroutines.delay
import logdate.client.feature.onboarding.generated.resources.*
import logdate.client.feature.onboarding.generated.resources.Res
import logdate.client.ui.generated.resources.common_continue
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import logdate.client.ui.generated.resources.Res as UiRes

const val PERSONAL_INTRO_ROOT_TAG = "onboarding_personal_intro_root"
const val PERSONAL_INTRO_NAME_FIELD_TAG = "onboarding_personal_intro_name_field"
const val PERSONAL_INTRO_NAME_CONTINUE_TAG = "onboarding_personal_intro_name_continue"
const val PERSONAL_INTRO_BIO_FIELD_TAG = "onboarding_personal_intro_bio_field"
const val PERSONAL_INTRO_BIO_CONTINUE_TAG = "onboarding_personal_intro_bio_continue"

private const val INTRO_QUESTION_COUNT = 2

/**
 * Personal introduction screen that asks users to share their name and bio
 * in a playful, two-step flow with LLM-powered friendly responses.
 */
@Composable
fun PersonalIntroScreen(
    onNext: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PersonalIntroViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { message ->
            snackbarHostState.showSnackbar(getString(message))
            viewModel.clearError()
        }
    }

    // Auto-advance when LLM processing and saving is complete
    LaunchedEffect(uiState.canFinish) {
        if (uiState.canFinish && !uiState.isLoading) {
            // Brief delay to let user read the LLM response
            delay(2000)
            viewModel.completeIntroduction()
            if (!uiState.isLoading && uiState.errorMessage == null) {
                onNext()
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        PersonalIntroContent(
            uiState = uiState,
            onNameChanged = viewModel::onNameChanged,
            onBioChanged = viewModel::onBioChanged,
            onProceedToBio = viewModel::proceedToBio,
            onGoBackToName = viewModel::goBackToName,
            onProcessWithLlm = viewModel::processWithLlm,
            onBack = onBack,
            modifier = Modifier.fillMaxSize(),
        )
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing),
        )
    }
}

@Composable
fun PersonalIntroContent(
    uiState: PersonalIntroUiState,
    onNameChanged: (String) -> Unit,
    onBioChanged: (String) -> Unit,
    onProceedToBio: () -> Unit,
    onGoBackToName: () -> Unit,
    onProcessWithLlm: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    autoFocusInputs: Boolean = true,
    animateStepTransitions: Boolean = true,
) {
    AnimatedContent(
        targetState = uiState.currentStep,
        modifier = modifier.testTag(PERSONAL_INTRO_ROOT_TAG),
        transitionSpec = {
            if (animateStepTransitions) {
                onboardingSlideTransition()
            } else {
                ContentTransform(EnterTransition.None, ExitTransition.None)
            }
        },
        label = "Personal intro step",
    ) { step ->
        when (step) {
            PersonalIntroStep.Name -> {
                NameStep(
                    name = uiState.name,
                    nameError = uiState.nameError,
                    canContinue = uiState.canContinueFromName,
                    onNameChanged = onNameChanged,
                    onContinue = onProceedToBio,
                    onBack = onBack,
                    autoFocus = autoFocusInputs,
                )
            }

            PersonalIntroStep.Bio -> {
                BioStep(
                    bio = uiState.bio,
                    bioError = uiState.bioError,
                    canContinue = uiState.canContinueFromBio,
                    isProcessing = uiState.isProcessingLlm,
                    onBioChanged = onBioChanged,
                    onContinue = onProcessWithLlm,
                    onBack = onGoBackToName,
                    autoFocus = autoFocusInputs,
                )
            }

            PersonalIntroStep.LlmResponse -> {
                LlmResponseStep(
                    userName = uiState.name,
                    llmResponse = uiState.llmResponse,
                    llmError = uiState.llmError,
                    isLoading = uiState.isLoading,
                )
            }
        }
    }
}

@Composable
private fun NameStep(
    name: String,
    nameError: StringResource?,
    canContinue: Boolean,
    onNameChanged: (String) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    autoFocus: Boolean,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(autoFocus) {
        if (!autoFocus) return@LaunchedEffect
        delay(300) // Let the step transition settle before the keyboard opens.
        focusRequester.requestFocus()
    }

    StepScaffold(
        title = stringResource(Res.string.who_are_you),
        onBack = onBack,
        supportingText = stringResource(Res.string.lets_start_with_what_we_should_call_you),
        progress = StepProgress(current = 1, total = INTRO_QUESTION_COUNT),
        hero = { StepHeroIcon(Icons.Rounded.Face) },
        headerAlignment = Alignment.CenterHorizontally,
        actions = {
            Button(
                onClick = onContinue,
                enabled = canContinue,
                modifier = Modifier.fillMaxWidth().testTag(PERSONAL_INTRO_NAME_CONTINUE_TAG),
            ) {
                Text(stringResource(UiRes.string.common_continue))
            }
        },
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = onNameChanged,
            label = { Text(stringResource(Res.string.your_name)) },
            placeholder = { Text(stringResource(Res.string.account_username_display_name_prompt)) },
            isError = nameError != null,
            supportingText = nameError?.let { { Text(stringResource(it)) } },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag(PERSONAL_INTRO_NAME_FIELD_TAG)
                    .focusRequester(focusRequester),
            keyboardOptions =
                KeyboardOptions(
                    imeAction = ImeAction.Next,
                    capitalization = KeyboardCapitalization.Words,
                ),
            keyboardActions =
                KeyboardActions(
                    onNext = {
                        focusManager.clearFocus()
                        if (canContinue) onContinue()
                    },
                ),
            singleLine = true,
        )
    }
}

@Composable
private fun BioStep(
    bio: String,
    bioError: StringResource?,
    canContinue: Boolean,
    isProcessing: Boolean,
    onBioChanged: (String) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    autoFocus: Boolean,
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(autoFocus) {
        if (!autoFocus) return@LaunchedEffect
        delay(300) // Let the step transition settle before the keyboard opens.
        focusRequester.requestFocus()
    }

    StepScaffold(
        title = stringResource(Res.string.tell_us_a_bit_about_yourself),
        onBack = onBack,
        supportingText = stringResource(Res.string.what_makes_you_you_share_whatever_feels_right),
        progress = StepProgress(current = 2, total = INTRO_QUESTION_COUNT),
        hero = { StepHeroIcon(Icons.Rounded.Face) },
        headerAlignment = Alignment.CenterHorizontally,
        actions = {
            StepBusyButton(
                text = stringResource(Res.string.lets_see_what_i_think),
                onClick = onContinue,
                busy = isProcessing,
                enabled = canContinue,
                modifier = Modifier.testTag(PERSONAL_INTRO_BIO_CONTINUE_TAG),
            )
        },
    ) {
        OutlinedTextField(
            value = bio,
            onValueChange = onBioChanged,
            label = { Text(stringResource(Res.string.about_you)) },
            placeholder = { Text(stringResource(Res.string.i_love_coffee_hiking_and_terrible_movies)) },
            isError = bioError != null,
            supportingText = bioError?.let { { Text(stringResource(it)) } },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .testTag(PERSONAL_INTRO_BIO_FIELD_TAG)
                    .focusRequester(focusRequester),
            keyboardOptions =
                KeyboardOptions(
                    imeAction = ImeAction.Done,
                    capitalization = KeyboardCapitalization.Sentences,
                ),
            keyboardActions =
                KeyboardActions(
                    onDone = {
                        if (canContinue) onContinue()
                    },
                ),
            maxLines = 4,
        )
    }
}

@Composable
private fun LlmResponseStep(
    userName: String,
    llmResponse: String?,
    llmError: String?,
    isLoading: Boolean,
) {
    val response = llmResponse ?: llmError
    StepScaffold(
        title =
            if (response != null) {
                stringResource(Res.string.nice_to_meet_you_name, userName)
            } else {
                stringResource(Res.string.let_me_think_about_that)
            },
        onBack = null,
        hero = { StepHeroIcon(Icons.Rounded.WavingHand) },
        headerAlignment = Alignment.CenterHorizontally,
        actions = {
            if (response == null) return@StepScaffold
            Text(
                text =
                    if (isLoading) {
                        stringResource(Res.string.setting_up_your_profile)
                    } else {
                        stringResource(Res.string.ready_to_start_your_journaling_journey)
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        if (response == null) {
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
            }
            return@StepScaffold
        }
        Text(
            text = response,
            style = MaterialTheme.typography.bodyLarge,
            color =
                if (llmError != null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.large)
                    .padding(Spacing.lg),
        )
    }
}

@Preview
@Composable
private fun PersonalIntroContentPreview_Name() {
    LogDateTheme {
        PersonalIntroContent(
            uiState = PersonalIntroUiState(currentStep = PersonalIntroStep.Name),
            onNameChanged = {},
            onBioChanged = {},
            onProceedToBio = {},
            onGoBackToName = {},
            onProcessWithLlm = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun PersonalIntroContentPreview_Bio() {
    LogDateTheme {
        PersonalIntroContent(
            uiState =
                PersonalIntroUiState(
                    currentStep = PersonalIntroStep.Bio,
                    name = "Willie",
                ),
            onNameChanged = {},
            onBioChanged = {},
            onProceedToBio = {},
            onGoBackToName = {},
            onProcessWithLlm = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun PersonalIntroContentPreview_LlmResponse() {
    LogDateTheme {
        PersonalIntroContent(
            uiState =
                PersonalIntroUiState(
                    currentStep = PersonalIntroStep.LlmResponse,
                    name = "Willie",
                    bio = "I love coffee, hiking, and terrible movies.",
                    llmResponse =
                        "Nice to meet you! Your journey through coffee, trails, and " +
                            "cinematic disasters sounds like a story worth capturing.",
                ),
            onNameChanged = {},
            onBioChanged = {},
            onProceedToBio = {},
            onGoBackToName = {},
            onProcessWithLlm = {},
            onBack = {},
        )
    }
}
