@file:Suppress(
    "ktlint:standard:function-naming",
    "ktlint:standard:no-wildcard-imports",
)

package app.logdate.feature.onboarding.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
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

const val ONBOARDING_RECOMMENDATIONS_ROOT_TAG = "onboarding_recommendations_root"
const val ONBOARDING_RECOMMENDATIONS_KEEP_ON_TAG = "onboarding_recommendations_keep_on"
const val ONBOARDING_RECOMMENDATIONS_TURN_OFF_TAG = "onboarding_recommendations_turn_off"

@Composable
fun OnboardingRecommendationsScreen(
    onBack: () -> Unit,
    onNext: (Boolean) -> Unit,
    viewModel: OnboardingViewModel = koinViewModel(),
) {
    val coroutineScope = rememberCoroutineScope()
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    OnboardingRecommendationsContent(
        onBack = onBack,
        onKeepOn = {
            coroutineScope.launch {
                isSaving = true
                errorMessage = null
                viewModel
                    .persistRecommendationsEnabled(true)
                    .onSuccess {
                        viewModel
                            .markRecommendationsHandled()
                            .onSuccess {
                                isSaving = false
                                onNext(true)
                            }.onFailure {
                                errorMessage = getString(Res.string.onboarding_error_save_recommendations)
                                isSaving = false
                            }
                    }.onFailure {
                        errorMessage = getString(Res.string.onboarding_error_save_recommendations)
                        isSaving = false
                    }
            }
        },
        onTurnOff = {
            coroutineScope.launch {
                isSaving = true
                errorMessage = null
                viewModel
                    .persistRecommendationsEnabled(false)
                    .onSuccess {
                        viewModel
                            .markRecommendationsHandled()
                            .onSuccess {
                                isSaving = false
                                onNext(false)
                            }.onFailure {
                                errorMessage = getString(Res.string.onboarding_error_save_recommendations)
                                isSaving = false
                            }
                    }.onFailure {
                        errorMessage = getString(Res.string.onboarding_error_save_recommendations)
                        isSaving = false
                    }
            }
        },
        isSaving = isSaving,
        errorMessage = errorMessage,
    )
}

@Composable
fun OnboardingRecommendationsContent(
    onBack: () -> Unit,
    onKeepOn: () -> Unit,
    onTurnOff: () -> Unit,
    isSaving: Boolean = false,
    errorMessage: String? = null,
) {
    StepScaffold(
        title = stringResource(Res.string.onboarding_recommendations_title),
        onBack = onBack,
        modifier = Modifier.testTag(ONBOARDING_RECOMMENDATIONS_ROOT_TAG),
        supportingText = stringResource(Res.string.onboarding_recommendations_body),
        hero = { StepHeroIcon(Icons.Rounded.AutoAwesome) },
        headerAlignment = Alignment.CenterHorizontally,
        actions = {
            StepPrimaryButton(
                text = stringResource(Res.string.onboarding_recommendations_keep_on),
                onClick = onKeepOn,
                busy = isSaving,
                modifier = Modifier.testTag(ONBOARDING_RECOMMENDATIONS_KEEP_ON_TAG),
            )
            TextButton(
                onClick = onTurnOff,
                enabled = !isSaving,
                modifier = Modifier.testTag(ONBOARDING_RECOMMENDATIONS_TURN_OFF_TAG),
            ) {
                Text(stringResource(Res.string.onboarding_recommendations_turn_off))
            }
            OnboardingActionError(errorMessage)
        },
    ) {
        OverviewItem(
            title = stringResource(Res.string.onboarding_recommendations_card_drafts_title),
            description = stringResource(Res.string.onboarding_recommendations_card_drafts_description),
            icon = { Icon(Icons.Rounded.EditNote, contentDescription = null) },
        )
        OverviewItem(
            title = stringResource(Res.string.onboarding_recommendations_card_recall_title),
            description = stringResource(Res.string.onboarding_recommendations_card_recall_description),
            icon = { Icon(Icons.Rounded.History, contentDescription = null) },
        )
        PrivacyNote(body = stringResource(Res.string.onboarding_recommendations_privacy))
    }
}

@Preview
@Composable
private fun OnboardingRecommendationsScreenPreview() {
    LogDateTheme {
        OnboardingRecommendationsContent(
            onBack = {},
            onKeepOn = {},
            onTurnOff = {},
        )
    }
}
