@file:Suppress(
    "ktlint:standard:function-naming",
    "ktlint:standard:no-wildcard-imports",
)

package app.logdate.feature.onboarding.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import app.logdate.client.permissions.rememberLocationPermissionState
import app.logdate.ui.step.StepHeroIcon
import app.logdate.ui.step.StepPrimaryButton
import app.logdate.ui.step.StepScaffold
import app.logdate.ui.theme.LogDateTheme
import kotlinx.coroutines.launch
import logdate.client.feature.onboarding.generated.resources.*
import logdate.client.feature.onboarding.generated.resources.Res
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

const val ONBOARDING_LOCATION_ROOT_TAG = "onboarding_location_root"
const val ONBOARDING_LOCATION_ENABLE_TAG = "onboarding_location_enable"
const val ONBOARDING_LOCATION_SKIP_TAG = "onboarding_location_skip"

@Composable
fun OnboardingLocationScreen(
    onBack: () -> Unit,
    onNext: (Boolean) -> Unit,
    viewModel: OnboardingViewModel = koinViewModel(),
) {
    val permissionState = rememberLocationPermissionState()
    val coroutineScope = rememberCoroutineScope()
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Auto-advance when permission is granted
    LaunchedEffect(permissionState.hasPermission) {
        if (permissionState.hasPermission && permissionState.permissionRequested) {
            coroutineScope.launch {
                isSaving = true
                errorMessage = null
                viewModel
                    .persistLocationTrackingEnabled()
                    .onSuccess {
                        viewModel
                            .markLocationHandled()
                            .onSuccess {
                                isSaving = false
                                onNext(true)
                            }.onFailure {
                                errorMessage = getString(Res.string.onboarding_error_save_location)
                                isSaving = false
                            }
                    }.onFailure {
                        errorMessage = getString(Res.string.onboarding_error_save_location)
                        isSaving = false
                    }
            }
        }
    }

    OnboardingLocationContent(
        onBack = onBack,
        onEnable = permissionState.requestPermission,
        onSkip = {
            coroutineScope.launch {
                isSaving = true
                errorMessage = null
                viewModel
                    .markLocationHandled()
                    .onSuccess {
                        isSaving = false
                        onNext(false)
                    }.onFailure {
                        errorMessage = getString(Res.string.onboarding_error_save_location)
                        isSaving = false
                    }
            }
        },
        isSaving = isSaving || permissionState.isRequestInFlight,
        errorMessage = errorMessage,
    )
}

@Composable
fun OnboardingLocationContent(
    onBack: () -> Unit,
    onEnable: () -> Unit,
    onSkip: () -> Unit,
    isSaving: Boolean = false,
    errorMessage: String? = null,
) {
    StepScaffold(
        title = stringResource(Res.string.onboarding_location_title),
        onBack = onBack,
        modifier = Modifier.testTag(ONBOARDING_LOCATION_ROOT_TAG),
        supportingText = stringResource(Res.string.onboarding_location_body),
        hero = { StepHeroIcon(Icons.Rounded.Explore) },
        headerAlignment = Alignment.CenterHorizontally,
        actions = {
            StepPrimaryButton(
                text = stringResource(Res.string.onboarding_location_enable),
                onClick = onEnable,
                busy = isSaving,
                modifier = Modifier.testTag(ONBOARDING_LOCATION_ENABLE_TAG),
            )
            TextButton(
                onClick = onSkip,
                enabled = !isSaving,
                modifier = Modifier.testTag(ONBOARDING_LOCATION_SKIP_TAG),
            ) {
                Text(stringResource(Res.string.onboarding_location_not_now))
            }
            OnboardingActionError(errorMessage)
        },
    ) {
        OverviewItem(
            title = stringResource(Res.string.onboarding_location_card_title),
            description = stringResource(Res.string.onboarding_location_card_description),
            icon = { Icon(Icons.Rounded.LocationOn, contentDescription = null) },
        )
        PrivacyNote(body = stringResource(Res.string.onboarding_location_privacy))
    }
}

@Preview
@Composable
private fun OnboardingLocationScreenPreview() {
    LogDateTheme {
        OnboardingLocationContent(
            onBack = {},
            onEnable = {},
            onSkip = {},
        )
    }
}
