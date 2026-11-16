@file:Suppress(
    "ktlint:standard:function-naming",
    "ktlint:standard:no-wildcard-imports",
)

package app.logdate.feature.onboarding.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.logdate.feature.onboarding.flow.OnboardingCompletionCoordinator
import app.logdate.feature.onboarding.flow.OnboardingFinishResult
import app.logdate.feature.onboarding.flow.OnboardingStep
import app.logdate.ui.GenericLoadingScreen
import app.logdate.ui.platform.rememberLogDateHaptics
import app.logdate.ui.step.StepScaffold
import app.logdate.ui.streak.Campfire
import app.logdate.ui.streak.CampfirePhase
import app.logdate.ui.streak.CampfireSize
import app.logdate.ui.theme.LogDateTheme
import app.logdate.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import logdate.client.feature.onboarding.generated.resources.*
import logdate.client.feature.onboarding.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

const val ONBOARDING_COMPLETION_ROOT_TAG = "onboarding_complete_root"
const val ONBOARDING_COMPLETION_CONTINUE_TAG = "onboarding_complete_continue"
const val ONBOARDING_COMPLETION_FINAL_TAG = "onboarding_complete_final"
const val ONBOARDING_COMPLETION_LOADING_TAG = "onboarding_complete_loading"
const val FIRST_ENTRY_IMPORT_ACTION_TAG = "first_entry_import_action"
const val FIRST_ENTRY_CONTINUE_ACTION_TAG = "first_entry_continue_action"

@Composable
fun FirstEntryImportOfferScreen(
    onImport: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    StepScaffold(
        title = stringResource(Res.string.first_entry_saved_title),
        supportingText = stringResource(Res.string.first_entry_import_description),
        onBack = null,
        actions = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Button(
                    onClick = onContinue,
                    modifier = Modifier.fillMaxWidth().testTag(FIRST_ENTRY_CONTINUE_ACTION_TAG),
                ) {
                    Text(stringResource(Res.string.first_entry_continue_home))
                }
                OutlinedButton(
                    onClick = onImport,
                    modifier = Modifier.fillMaxWidth().testTag(FIRST_ENTRY_IMPORT_ACTION_TAG),
                ) {
                    Text(stringResource(Res.string.first_entry_import_photos))
                }
            }
        },
        modifier = modifier,
    )
}

/**
 * The last screen of the onboarding flow.
 *
 * This shows the user their freshly lit campfire and the completion of the onboarding flow.
 */
@Composable
fun OnboardingCompletionScreen(
    onFinish: () -> Unit,
    onRequirementsIncomplete: (OnboardingStep) -> Unit = {},
    finishImmediately: Boolean = false,
    completionCoordinator: OnboardingCompletionCoordinator = koinInject(),
    modifier: Modifier = Modifier,
) {
    var shouldShowFinish by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val finishOnboarding: () -> Unit = {
        coroutineScope.launch {
            when (val result = completionCoordinator.finishOnboarding()) {
                OnboardingFinishResult.Finished -> onFinish()
                is OnboardingFinishResult.IncompleteStep -> onRequirementsIncomplete(result.step)
                is OnboardingFinishResult.SaveFailed -> saveFailed = true
            }
        }
    }

    LaunchedEffect(finishImmediately) {
        if (finishImmediately) finishOnboarding()
    }

    if (saveFailed) {
        OnboardingSaveFailedContent(
            onRetry = {
                saveFailed = false
                finishOnboarding()
            },
            modifier = modifier,
        )
        return
    }

    if (finishImmediately) {
        GenericLoadingScreen(modifier = modifier.testTag(ONBOARDING_COMPLETION_LOADING_TAG))
        return
    }

    OnboardingCompletionContent(
        shouldShowFinish = shouldShowFinish,
        onContinue = { shouldShowFinish = true },
        onFinish = finishOnboarding,
        modifier = modifier,
    )
}

@Composable
fun OnboardingCompletionContent(
    shouldShowFinish: Boolean,
    onContinue: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var finalContentVisible by remember { mutableStateOf(true) }
    val haptics = rememberLogDateHaptics()

    LaunchedEffect(shouldShowFinish) {
        if (shouldShowFinish) {
            haptics.saveSucceeded()
            // Hold the final message, then fade out before leaving onboarding so the
            // next screen doesn't snap in on top of "Happy logging!"
            delay(1_600)
            finalContentVisible = false
            delay(ONBOARDING_COMPLETION_EXIT_FADE_MILLIS.toLong())
            onFinish()
        }
    }

    AnimatedContent(
        targetState = shouldShowFinish,
        modifier = modifier.testTag(ONBOARDING_COMPLETION_ROOT_TAG),
        transitionSpec = { onboardingFadeTransition() },
        label = "Show Finish Screen",
    ) { isShowingFinish ->
        if (isShowingFinish) {
            AnimatedVisibility(
                visible = finalContentVisible,
                enter = fadeIn(tween(ONBOARDING_COMPLETION_EXIT_FADE_MILLIS)),
                exit = fadeOut(tween(ONBOARDING_COMPLETION_EXIT_FADE_MILLIS)),
            ) {
                CompletionFinalContent()
            }
        } else {
            CompletionStreakContent(onContinue = onContinue)
        }
    }
}

private const val ONBOARDING_COMPLETION_EXIT_FADE_MILLIS = 400

@Composable
private fun CompletionStreakContent(onContinue: () -> Unit) {
    val title = stringResource(Res.string.onboarding_completion_fire_lit)
    val encouragement = stringResource(Res.string.onboarding_completion_fire_encouragement)

    StepScaffold(
        title = title,
        onBack = null,
        supportingText = encouragement,
        illustration = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Campfire(
                    phase = CampfirePhase.BURNING,
                    size = CampfireSize.SPARK,
                    contentDescription = title,
                    modifier = Modifier.size(128.dp),
                )
                Spacer(modifier = Modifier.height(Spacing.xl))
                Text(
                    text = stringResource(Res.string.one_more_thing),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
        actions = {
            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth().testTag(ONBOARDING_COMPLETION_CONTINUE_TAG),
            ) {
                Text(stringResource(Res.string.action_onboarding_continue))
            }
        },
    )
}

@Composable
private fun CompletionFinalContent() {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .testTag(ONBOARDING_COMPLETION_FINAL_TAG),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(Res.string.onboarding_completion_happy_logging),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Preview(
//    name = "Onboarding Final Screen 1"
)
@Composable
private fun PreviewCompletionStreakContent() {
    LogDateTheme {
        CompletionStreakContent(onContinue = {})
    }
}

@Preview(
//    name = "Onboarding Final Screen 2"
)
@Composable
private fun PreviewCompletionFinalContent() {
    LogDateTheme {
        CompletionFinalContent()
    }
}
