@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.logdate.client.domain.recommendation.AmbientPromptTime
import app.logdate.client.domain.recommendation.MemoriesSettings
import app.logdate.ui.adaptive.FoldableBookLayout
import app.logdate.ui.common.AlertDialog
import app.logdate.ui.common.LocalSettingsEnabled
import app.logdate.ui.common.MasterFeatureToggle
import app.logdate.ui.common.MaterialContainer
import app.logdate.ui.common.SettingsFeatureGroup
import app.logdate.ui.common.SettingsScaffold
import app.logdate.ui.common.SimpleSettingsItem
import app.logdate.ui.common.ToggleSettingsItem
import app.logdate.ui.common.disabledAlpha
import app.logdate.ui.theme.Spacing
import app.logdate.util.asTime
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.todayIn
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.ambient_prompts
import logdate.client.feature.core.generated.resources.ambient_prompts_description
import logdate.client.feature.core.generated.resources.capture_nudges
import logdate.client.feature.core.generated.resources.capture_nudges_description
import logdate.client.feature.core.generated.resources.draft_rescue
import logdate.client.feature.core.generated.resources.draft_rescue_description
import logdate.client.feature.core.generated.resources.evening_prompt
import logdate.client.feature.core.generated.resources.evening_prompt_description
import logdate.client.feature.core.generated.resources.evening_prompt_time
import logdate.client.feature.core.generated.resources.evening_prompt_time_description
import logdate.client.feature.core.generated.resources.memory_recall_notifications
import logdate.client.feature.core.generated.resources.memory_recall_notifications_description
import logdate.client.feature.core.generated.resources.morning_prompt
import logdate.client.feature.core.generated.resources.morning_prompt_description
import logdate.client.feature.core.generated.resources.morning_prompt_time
import logdate.client.feature.core.generated.resources.morning_prompt_time_description
import logdate.client.feature.core.generated.resources.prompt_schedule
import logdate.client.feature.core.generated.resources.recall_options
import logdate.client.feature.core.generated.resources.recommendations
import logdate.client.feature.core.generated.resources.recommendations_detail_description
import logdate.client.feature.core.generated.resources.smart_recall
import logdate.client.feature.core.generated.resources.smart_recall_description
import logdate.client.feature.core.generated.resources.use_recommendations
import logdate.client.ui.generated.resources.common_cancel
import logdate.client.ui.generated.resources.common_confirm
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import kotlin.time.Clock
import logdate.client.ui.generated.resources.Res as UiRes

/**
 * Detail screen for the Recommendations setting group.
 *
 * Follows the Pixel Settings detail pattern: large title, description,
 * primary toggle in a prominent row, then sub-options grouped by section.
 */
@Composable
fun RecommendationSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MemoriesSettingsViewModel = koinViewModel(),
    onReminderFix: (ReminderSettingsFix) -> Unit = {},
) {
    val flags = koinInject<FeatureFlagStore>()
    val remindersEnabled by remember(flags) { flags.observe(FeatureFlag.WATCH_EXPERIENCE_V2) }.collectAsStateWithLifecycle(false)
    if (remindersEnabled) {
        ReminderSettingsScreen(onBack = onBack, onFix = onReminderFix)
        return
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    RecommendationSettingsContent(
        settings = uiState.settings,
        onBack = onBack,
        onToggleContextualRecommendations = viewModel::toggleContextualRecommendations,
        onToggleAmbientPrompts = viewModel::toggleAmbientPrompts,
        onToggleCaptureNudges = viewModel::toggleCaptureNudges,
        onToggleDraftRescue = viewModel::toggleDraftRescue,
        onToggleMemoryRecallNotifications = viewModel::toggleMemoryRecallNotifications,
        onToggleMorningPrompt = viewModel::toggleMorningPrompt,
        onToggleEveningPrompt = viewModel::toggleEveningPrompt,
        onSetMorningPromptTime = viewModel::setMorningPromptTime,
        onSetEveningPromptTime = viewModel::setEveningPromptTime,
        onToggleSmartRecall = viewModel::toggleAiRecall,
        modifier = modifier,
    )
}

private enum class PromptTimeSelection {
    MORNING,
    EVENING,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecommendationSettingsContent(
    settings: MemoriesSettings,
    onBack: () -> Unit,
    onToggleContextualRecommendations: (Boolean) -> Unit,
    onToggleAmbientPrompts: (Boolean) -> Unit,
    onToggleCaptureNudges: (Boolean) -> Unit,
    onToggleDraftRescue: (Boolean) -> Unit,
    onToggleMemoryRecallNotifications: (Boolean) -> Unit,
    onToggleMorningPrompt: (Boolean) -> Unit,
    onToggleEveningPrompt: (Boolean) -> Unit,
    onSetMorningPromptTime: (AmbientPromptTime) -> Unit,
    onSetEveningPromptTime: (AmbientPromptTime) -> Unit,
    onToggleSmartRecall: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editingPromptTime by remember { mutableStateOf<PromptTimeSelection?>(null) }

    FoldableBookLayout(
        modifier = modifier.fillMaxSize(),
        minPaneWidth = 320.dp,
        startPane = {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                RecommendationOverviewSection(
                    settings = settings,
                    onToggleContextualRecommendations = onToggleContextualRecommendations,
                )
            }
        },
        endPane = {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = Spacing.lg),
            ) {
                RecommendationOptionsSection(
                    settings = settings,
                    onToggleAmbientPrompts = onToggleAmbientPrompts,
                    onToggleCaptureNudges = onToggleCaptureNudges,
                    onToggleDraftRescue = onToggleDraftRescue,
                    onToggleMemoryRecallNotifications = onToggleMemoryRecallNotifications,
                    onToggleMorningPrompt = onToggleMorningPrompt,
                    onToggleEveningPrompt = onToggleEveningPrompt,
                    onEditPromptTime = { editingPromptTime = it },
                    onToggleSmartRecall = onToggleSmartRecall,
                )
            }
        },
        standardContent = {
            SettingsScaffold(
                title = stringResource(Res.string.recommendations),
                onBack = onBack,
                modifier = modifier,
            ) {
                item {
                    RecommendationOverviewSection(
                        settings = settings,
                        onToggleContextualRecommendations = onToggleContextualRecommendations,
                    )
                }

                item {
                    RecommendationOptionsSection(
                        settings = settings,
                        onToggleAmbientPrompts = onToggleAmbientPrompts,
                        onToggleCaptureNudges = onToggleCaptureNudges,
                        onToggleDraftRescue = onToggleDraftRescue,
                        onToggleMemoryRecallNotifications = onToggleMemoryRecallNotifications,
                        onToggleMorningPrompt = onToggleMorningPrompt,
                        onToggleEveningPrompt = onToggleEveningPrompt,
                        onEditPromptTime = { editingPromptTime = it },
                        onToggleSmartRecall = onToggleSmartRecall,
                    )
                }
            }
        },
    )

    val selectedTime =
        when (editingPromptTime) {
            PromptTimeSelection.MORNING -> settings.morningPromptTime
            PromptTimeSelection.EVENING -> settings.eveningPromptTime
            null -> null
        }

    if (selectedTime != null) {
        PromptTimePickerDialog(
            initialTime = selectedTime,
            onConfirm = { updatedTime ->
                when (editingPromptTime) {
                    PromptTimeSelection.MORNING -> onSetMorningPromptTime(updatedTime)
                    PromptTimeSelection.EVENING -> onSetEveningPromptTime(updatedTime)
                    null -> Unit
                }
                editingPromptTime = null
            },
            onDismiss = { editingPromptTime = null },
        )
    }
}

@Composable
private fun RecommendationOverviewSection(
    settings: MemoriesSettings,
    onToggleContextualRecommendations: (Boolean) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Text(
            text = stringResource(Res.string.recommendations_detail_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        MasterFeatureToggle(
            label = stringResource(Res.string.use_recommendations),
            checked = settings.contextualRecommendationsEnabled,
            onCheckedChange = onToggleContextualRecommendations,
        )
    }
}

@Composable
private fun RecommendationOptionsSection(
    settings: MemoriesSettings,
    onToggleAmbientPrompts: (Boolean) -> Unit,
    onToggleCaptureNudges: (Boolean) -> Unit,
    onToggleDraftRescue: (Boolean) -> Unit,
    onToggleMemoryRecallNotifications: (Boolean) -> Unit,
    onToggleMorningPrompt: (Boolean) -> Unit,
    onToggleEveningPrompt: (Boolean) -> Unit,
    onEditPromptTime: (PromptTimeSelection) -> Unit,
    onToggleSmartRecall: (Boolean) -> Unit,
) {
    SettingsFeatureGroup(enabled = settings.contextualRecommendationsEnabled) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                text = stringResource(Res.string.ambient_prompts),
                style = MaterialTheme.typography.titleMedium,
                modifier =
                    Modifier
                        .padding(vertical = Spacing.sm)
                        .disabledAlpha(LocalSettingsEnabled.current),
            )

            MaterialContainer {
                ToggleSettingsItem(
                    title = stringResource(Res.string.ambient_prompts),
                    description = stringResource(Res.string.ambient_prompts_description),
                    checked = settings.ambientPromptsEnabled,
                    onCheckedChange = onToggleAmbientPrompts,
                )

                SettingsFeatureGroup(enabled = settings.ambientPromptsEnabled) {
                    ToggleSettingsItem(
                        title = stringResource(Res.string.capture_nudges),
                        description = stringResource(Res.string.capture_nudges_description),
                        checked = settings.captureNudgesEnabled,
                        onCheckedChange = onToggleCaptureNudges,
                    )
                    ToggleSettingsItem(
                        title = stringResource(Res.string.draft_rescue),
                        description = stringResource(Res.string.draft_rescue_description),
                        checked = settings.draftRescueEnabled,
                        onCheckedChange = onToggleDraftRescue,
                    )
                    ToggleSettingsItem(
                        title = stringResource(Res.string.memory_recall_notifications),
                        description = stringResource(Res.string.memory_recall_notifications_description),
                        checked = settings.memoryRecallNotificationsEnabled,
                        onCheckedChange = onToggleMemoryRecallNotifications,
                    )
                }
            }

            SettingsFeatureGroup(
                enabled = settings.ambientPromptsEnabled && settings.captureNudgesEnabled,
            ) {
                Text(
                    text = stringResource(Res.string.prompt_schedule),
                    style = MaterialTheme.typography.titleMedium,
                    modifier =
                        Modifier
                            .padding(vertical = Spacing.sm)
                            .disabledAlpha(LocalSettingsEnabled.current),
                )

                MaterialContainer {
                    ToggleSettingsItem(
                        title = stringResource(Res.string.morning_prompt),
                        description = stringResource(Res.string.morning_prompt_description),
                        checked = settings.morningPromptEnabled,
                        onCheckedChange = onToggleMorningPrompt,
                    )
                    SimpleSettingsItem(
                        title = stringResource(Res.string.morning_prompt_time),
                        description = stringResource(Res.string.morning_prompt_time_description),
                        onClick = { onEditPromptTime(PromptTimeSelection.MORNING) },
                    ) {
                        Text(
                            text = formatPromptTime(settings.morningPromptTime),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }

                    ToggleSettingsItem(
                        title = stringResource(Res.string.evening_prompt),
                        description = stringResource(Res.string.evening_prompt_description),
                        checked = settings.eveningPromptEnabled,
                        onCheckedChange = onToggleEveningPrompt,
                    )
                    SimpleSettingsItem(
                        title = stringResource(Res.string.evening_prompt_time),
                        description = stringResource(Res.string.evening_prompt_time_description),
                        onClick = { onEditPromptTime(PromptTimeSelection.EVENING) },
                    ) {
                        Text(
                            text = formatPromptTime(settings.eveningPromptTime),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }

            Text(
                text = stringResource(Res.string.recall_options),
                style = MaterialTheme.typography.titleMedium,
                modifier =
                    Modifier
                        .padding(vertical = Spacing.sm)
                        .disabledAlpha(LocalSettingsEnabled.current),
            )

            MaterialContainer {
                ToggleSettingsItem(
                    title = stringResource(Res.string.smart_recall),
                    description =
                        stringResource(Res.string.smart_recall_description),
                    checked = settings.aiRecallEnabled,
                    onCheckedChange = onToggleSmartRecall,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PromptTimePickerDialog(
    initialTime: AmbientPromptTime,
    onConfirm: (AmbientPromptTime) -> Unit,
    onDismiss: () -> Unit,
) {
    val state =
        rememberTimePickerState(
            initialHour = initialTime.hour,
            initialMinute = initialTime.minute,
        )

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(AmbientPromptTime(hour = state.hour, minute = state.minute))
                },
            ) {
                Text(stringResource(UiRes.string.common_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(UiRes.string.common_cancel))
            }
        },
        text = {
            TimePicker(state = state)
        },
    )
}

private fun formatPromptTime(time: AmbientPromptTime): String {
    val timeZone = TimeZone.currentSystemDefault()
    val today = Clock.System.todayIn(timeZone)
    return LocalDateTime(today, LocalTime(time.hour, time.minute)).toInstant(timeZone).asTime
}
