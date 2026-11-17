@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.onboarding.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import app.logdate.feature.onboarding.ui.opening.OpeningScene
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

/**
 * The first screen of the app, and the timeline it turns into.
 *
 * Both are one step, laid out by [StepScaffold] like every other onboarding step, which is what
 * keeps the title and the actions where the next step has them. What makes it the opening is the
 * scene around them: [OpeningScene] fills the whole screen with the things LogDate keeps, and the
 * same pieces stay on the table when [overview] lines them up by day, so nothing is swapped out
 * between the two.
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
        val parallax = rememberDeviceParallax(enabled = animateScene)
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
                scene = { bounds -> OpeningScene(bounds, tidied = overview, animate = animateScene, parallax = { parallax.value }) },
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
