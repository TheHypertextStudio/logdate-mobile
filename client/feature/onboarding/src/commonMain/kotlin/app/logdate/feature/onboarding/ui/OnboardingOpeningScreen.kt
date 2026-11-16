@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.onboarding.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.logdate.ui.motion.rememberDeviceParallax
import app.logdate.ui.step.StepPrimaryButton
import app.logdate.ui.step.StepScaffold
import app.logdate.ui.step.StepSecondaryButton
import app.logdate.ui.theme.FirstLaunchTheme
import logdate.client.feature.onboarding.generated.resources.Res
import logdate.client.feature.onboarding.generated.resources.onboarding_action_sign_in
import logdate.client.feature.onboarding.generated.resources.onboarding_opening_back
import logdate.client.feature.onboarding.generated.resources.onboarding_opening_continue
import logdate.client.feature.onboarding.generated.resources.onboarding_opening_description
import logdate.client.feature.onboarding.generated.resources.onboarding_opening_overview_action
import logdate.client.feature.onboarding.generated.resources.onboarding_opening_overview_description
import logdate.client.feature.onboarding.generated.resources.onboarding_opening_overview_title
import logdate.client.feature.onboarding.generated.resources.onboarding_opening_title
import org.jetbrains.compose.resources.stringResource

internal const val ONBOARDING_OPENING_SCENE_TAG = "onboarding_opening_scene"

/**
 * Opening and overview share their scene and state, so the objects never leave the composition.
 *
 * The screen is laid out by [StepScaffold], the same as every other onboarding step. That is what
 * keeps its actions where the next step's actions are: pinned at the bottom, the same distance
 * from the edge, the same width, the main action first. Only the scene and the words above them
 * change between the two states, and only they move.
 */
@Composable
internal fun OnboardingOpeningScreen(
    overview: Boolean,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
    showControls: Boolean = true,
    animateScene: Boolean = true,
) {
    FirstLaunchTheme {
        val colors = MaterialTheme.colorScheme
        val parallax by rememberDeviceParallax(enabled = animateScene)
        Box(
            modifier =
                modifier
                    .fillMaxSize()
                    .testTag(ONBOARDING_START_ROOT_TAG)
                    .background(
                        Brush.verticalGradient(
                            listOf(colors.tertiaryContainer.copy(alpha = 0.32f), colors.surface, colors.surface),
                        ),
                    ),
        ) {
            StepScaffold(
                title = stringResource(if (overview) Res.string.onboarding_opening_overview_title else Res.string.onboarding_opening_title),
                supportingText =
                    stringResource(
                        if (overview) Res.string.onboarding_opening_overview_description else Res.string.onboarding_opening_description,
                    ),
                onBack = null,
                containerColor = Color.Transparent,
                illustration = {
                    // The most the scene wants; the step gives it less where there is less room.
                    Box(Modifier.fillMaxWidth().height(SCENE_MAX_HEIGHT).testTag(ONBOARDING_OPENING_SCENE_TAG)) {
                        OnboardingObjects(overview = overview, animateScene = animateScene, parallax = parallax)
                    }
                },
                actions = { if (showControls) OpeningActions(overview, onContinue, onBack, onSignIn) },
            )
        }
    }
}

@Composable
private fun OpeningActions(
    overview: Boolean,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
) {
    StepPrimaryButton(
        text = stringResource(if (overview) Res.string.onboarding_opening_continue else Res.string.onboarding_opening_overview_action),
        onClick = onContinue,
        modifier = Modifier.testTag(if (overview) ONBOARDING_OVERVIEW_CONTINUE_TAG else ONBOARDING_START_GET_STARTED_TAG),
    )
    StepSecondaryButton(
        text = stringResource(if (overview) Res.string.onboarding_opening_back else Res.string.onboarding_action_sign_in),
        onClick = if (overview) onBack else onSignIn,
        modifier = Modifier.testTag(if (overview) "onboarding_overview_back" else ONBOARDING_START_FROM_BACKUP_TAG),
    )
}

private val SCENE_MAX_HEIGHT = 400.dp
