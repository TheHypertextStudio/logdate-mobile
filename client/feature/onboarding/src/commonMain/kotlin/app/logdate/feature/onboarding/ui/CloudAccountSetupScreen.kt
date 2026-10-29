@file:Suppress(
    "ktlint:standard:function-naming",
    "ktlint:standard:no-wildcard-imports",
)

package app.logdate.feature.onboarding.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import app.logdate.feature.core.account.CloudAccountOnboardingScreen
import app.logdate.feature.core.account.CloudAccountOnboardingViewModel
import app.logdate.ui.step.StepHeroIcon
import app.logdate.ui.step.StepScaffold
import app.logdate.ui.theme.LogDateTheme
import logdate.client.feature.onboarding.generated.resources.*
import logdate.client.feature.onboarding.generated.resources.Res
import logdate.client.ui.generated.resources.common_continue
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import app.logdate.feature.core.account.OnboardingStep as CloudAccountOnboardingStep
import logdate.client.ui.generated.resources.Res as UiRes

const val CLOUD_ACCOUNT_SETUP_ROOT_TAG = "onboarding_account_root"
const val CLOUD_ACCOUNT_SETUP_PRIMARY_ACTION_TAG = "onboarding_account_primary_action"
const val CLOUD_ACCOUNT_SETUP_SKIP_ACTION_TAG = "onboarding_account_skip_action"
const val CLOUD_ACCOUNT_SETUP_SIGN_IN_ACTION_TAG = "onboarding_account_sign_in_action"

@Composable
fun CloudAccountSetupScreen(
    onBack: () -> Unit,
    onContinue: () -> Unit,
    onSkip: () -> Unit,
    onContinueWithBluesky: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // Null until the person chooses; otherwise the step the account flow opens on. Creating an
    // account and signing in are separate entry points into the same flow, so someone
    // reinstalling the app can get back to their journals without creating a second account.
    var entryStep by remember { mutableStateOf<CloudAccountOnboardingStep?>(null) }
    val cloudAccountViewModel = koinViewModel<CloudAccountOnboardingViewModel>()

    LaunchedEffect(entryStep) {
        entryStep?.let { step ->
            cloudAccountViewModel.resetFlow()
            cloudAccountViewModel.setInitialStep(step)
        }
    }

    if (entryStep != null) {
        CloudAccountOnboardingScreen(
            onContinueWithBluesky = onContinueWithBluesky,
            viewModel = cloudAccountViewModel,
            onAccountCreated = onContinue,
            onSkipOnboarding = onSkip,
            onBack = {
                cloudAccountViewModel.resetFlow()
                entryStep = null
            },
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    CloudAccountSetupContent(
        onBack = onBack,
        onContinue = { entryStep = CloudAccountOnboardingStep.DisplayName },
        onSignIn = { entryStep = CloudAccountOnboardingStep.SignIn },
        onSkip = onSkip,
        modifier = modifier,
    )
}

@Composable
fun CloudAccountSetupContent(
    onBack: () -> Unit,
    onContinue: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
    onSignIn: () -> Unit = {},
) {
    StepScaffold(
        title = stringResource(Res.string.backup_and_sync),
        onBack = onBack,
        modifier = modifier.testTag(CLOUD_ACCOUNT_SETUP_ROOT_TAG),
        supportingText = stringResource(Res.string.onboarding_cloud_backup_description),
        hero = { StepHeroIcon(Icons.Rounded.CloudSync) },
        headerAlignment = Alignment.CenterHorizontally,
        actions = {
            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth().testTag(CLOUD_ACCOUNT_SETUP_PRIMARY_ACTION_TAG),
            ) {
                Text(stringResource(UiRes.string.common_continue))
            }
            OutlinedButton(
                onClick = onSignIn,
                modifier = Modifier.fillMaxWidth().testTag(CLOUD_ACCOUNT_SETUP_SIGN_IN_ACTION_TAG),
            ) {
                Text(stringResource(Res.string.onboarding_account_existing_action))
            }
            TextButton(
                onClick = onSkip,
                modifier = Modifier.testTag(CLOUD_ACCOUNT_SETUP_SKIP_ACTION_TAG),
            ) {
                Text(stringResource(Res.string.continue_without_cloud_sync))
            }
        },
        footer = {
            Text(
                text = stringResource(Res.string.onboarding_sync_later_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        Text(
            text = stringResource(Res.string.onboarding_free_cloud_explanation),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Preview
@Composable
private fun CloudAccountSetupScreenPreview() {
    LogDateTheme {
        CloudAccountSetupContent(
            onBack = {},
            onContinue = {},
            onSkip = {},
        )
    }
}
