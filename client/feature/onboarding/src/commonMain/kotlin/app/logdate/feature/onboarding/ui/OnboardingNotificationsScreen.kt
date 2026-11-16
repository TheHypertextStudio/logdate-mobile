@file:Suppress(
    "ktlint:standard:function-naming",
    "ktlint:standard:no-wildcard-imports",
)

package app.logdate.feature.onboarding.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import app.logdate.client.domain.reminders.ReminderPreferences
import app.logdate.client.domain.reminders.ReminderPreferencesState
import app.logdate.client.permissions.rememberNotificationPermissionState
import app.logdate.ui.step.StepHeroIcon
import app.logdate.ui.step.StepPrimaryButton
import app.logdate.ui.step.StepScaffold
import app.logdate.ui.theme.LogDateTheme
import kotlinx.coroutines.launch
import logdate.client.feature.onboarding.generated.resources.*
import logdate.client.feature.onboarding.generated.resources.Res
import logdate.client.ui.generated.resources.common_continue
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import logdate.client.ui.generated.resources.Res as UiRes

const val ONBOARDING_NOTIFICATIONS_ROOT_TAG = "onboarding_notifications_root"
const val ONBOARDING_NOTIFICATIONS_PRIMARY_TAG = "onboarding_notifications_primary"
const val ONBOARDING_NOTIFICATIONS_SKIP_TAG = "onboarding_notifications_skip"

@Composable
fun OnboardingNotificationsScreen(
    onBack: () -> Unit,
    onNext: () -> Unit,
    viewModel: OnboardingViewModel = koinViewModel(),
) {
    val reminders = koinInject<ReminderPreferences>()
    val reminderState by remember(reminders) { reminders.observe() }.collectAsState(ReminderPreferencesState())
    if (reminderState.ready && reminderState.rolloutEnabled) {
        ReminderOnboardingNotificationsStep(
            onBack = onBack,
            onNext = onNext,
            preferences = reminders,
            state = reminderState,
            markHandled = viewModel::markNotificationsHandled,
        )
        return
    }
    val recommendationsEnabled by viewModel.recommendationsEnabled.collectAsState()
    val permissionState = rememberNotificationPermissionState()
    val coroutineScope = rememberCoroutineScope()
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val hasDecision = permissionState.hasPermission || permissionState.permissionRequested

    OnboardingNotificationsContent(
        onBack = onBack,
        onPrimaryAction = {
            if (hasDecision) {
                coroutineScope.launch {
                    isSaving = true
                    errorMessage = null
                    viewModel
                        .markNotificationsHandled()
                        .onSuccess {
                            isSaving = false
                            onNext()
                        }.onFailure {
                            errorMessage = getString(Res.string.onboarding_error_save_notifications)
                            isSaving = false
                        }
                }
            } else {
                permissionState.requestPermission()
            }
        },
        onSkip = {
            coroutineScope.launch {
                isSaving = true
                errorMessage = null
                viewModel
                    .markNotificationsHandled()
                    .onSuccess {
                        isSaving = false
                        onNext()
                    }.onFailure {
                        errorMessage = getString(Res.string.onboarding_error_save_notifications)
                        isSaving = false
                    }
            }
        },
        recommendationsEnabled = recommendationsEnabled,
        hasDecision = hasDecision,
        isSaving = isSaving || permissionState.isRequestInFlight || !reminderState.ready,
        errorMessage = errorMessage,
    )
}

@Composable
fun OnboardingNotificationsContent(
    onBack: () -> Unit,
    onPrimaryAction: () -> Unit,
    onSkip: () -> Unit,
    recommendationsEnabled: Boolean,
    hasDecision: Boolean,
    isSaving: Boolean = false,
    errorMessage: String? = null,
    remindersEnabled: Boolean? = null,
) {
    val bodyText: String
    val previewTitle: String
    val previewBody: String
    if (remindersEnabled != null) {
        bodyText = stringResource(Res.string.onboarding_reminders_body)
        previewTitle = stringResource(Res.string.onboarding_reminders_preview_title)
        previewBody = stringResource(Res.string.onboarding_reminders_preview_body)
    } else if (recommendationsEnabled) {
        bodyText = stringResource(Res.string.onboarding_notifications_body_with_recommendations)
        previewTitle = stringResource(Res.string.onboarding_notifications_preview_prompt_title)
        previewBody = stringResource(Res.string.onboarding_notifications_preview_prompt_body)
    } else {
        bodyText = stringResource(Res.string.onboarding_notifications_body_without_recommendations)
        previewTitle = stringResource(Res.string.onboarding_notifications_preview_update_title)
        previewBody = stringResource(Res.string.onboarding_notifications_preview_update_body)
    }
    val primaryActionLabel =
        if (remindersEnabled == false || (remindersEnabled == true && !hasDecision)) {
            stringResource(Res.string.onboarding_reminders_enable)
        } else if (hasDecision) {
            stringResource(UiRes.string.common_continue)
        } else {
            stringResource(Res.string.onboarding_notifications_enable)
        }

    StepScaffold(
        title =
            stringResource(
                if (remindersEnabled ==
                    null
                ) {
                    Res.string.onboarding_notifications_title
                } else {
                    Res.string.onboarding_reminders_title
                },
            ),
        onBack = onBack,
        modifier = Modifier.testTag(ONBOARDING_NOTIFICATIONS_ROOT_TAG),
        supportingText = bodyText,
        hero = { StepHeroIcon(Icons.Rounded.NotificationsActive) },
        headerAlignment = Alignment.CenterHorizontally,
        actions = {
            StepPrimaryButton(
                text = primaryActionLabel,
                onClick = onPrimaryAction,
                busy = isSaving,
                modifier = Modifier.testTag(ONBOARDING_NOTIFICATIONS_PRIMARY_TAG),
            )
            TextButton(
                onClick = onSkip,
                enabled = !isSaving,
                modifier = Modifier.testTag(ONBOARDING_NOTIFICATIONS_SKIP_TAG),
            ) {
                Text(
                    stringResource(
                        if (remindersEnabled ==
                            null
                        ) {
                            Res.string.onboarding_notifications_not_now
                        } else {
                            Res.string.onboarding_reminders_not_now
                        },
                    ),
                )
            }
            OnboardingActionError(errorMessage)
        },
    ) {
        NotificationPreview(title = previewTitle, body = previewBody)
    }
}

@Preview
@Composable
private fun OnboardingNotificationsScreenPreview_WithRecommendations() {
    LogDateTheme {
        OnboardingNotificationsContent(
            onBack = {},
            onPrimaryAction = {},
            onSkip = {},
            recommendationsEnabled = true,
            hasDecision = false,
        )
    }
}

@Preview
@Composable
private fun OnboardingNotificationsScreenPreview_WithoutRecommendations() {
    LogDateTheme {
        OnboardingNotificationsContent(
            onBack = {},
            onPrimaryAction = {},
            onSkip = {},
            recommendationsEnabled = false,
            hasDecision = true,
        )
    }
}
