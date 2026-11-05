@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.logdate.client.domain.dayboundary.HealthConnectGateState
import app.logdate.client.permissions.rememberHealthConnectPermissionState
import app.logdate.ui.adaptive.FoldableBookLayout
import app.logdate.ui.common.MasterFeatureToggle
import app.logdate.ui.common.MaterialContainer
import app.logdate.ui.common.SettingsRow
import app.logdate.ui.common.SettingsScaffold
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.day_schedule
import logdate.client.feature.core.generated.resources.day_schedule_detail_description
import logdate.client.feature.core.generated.resources.day_schedule_disabled_explanation
import logdate.client.feature.core.generated.resources.day_schedule_enabled_explanation
import logdate.client.feature.core.generated.resources.day_schedule_fallback_time
import logdate.client.feature.core.generated.resources.day_schedule_fallback_time_description
import logdate.client.feature.core.generated.resources.use_sleep_schedule
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun DayBoundarySettingsScreen(
    onBack: () -> Unit,
    onSetUpHealthConnect: () -> Unit = {},
    onOpenHealthConnectPermissions: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: DayBoundarySettingsViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val permissionState = rememberHealthConnectPermissionState(onRequestResult = viewModel::onPermissionResult)
    LaunchedEffect(permissionState.hasPermission, permissionState.permissionRequested) {
        viewModel.onPermissionStatus(permissionState.hasPermission, permissionState.permissionRequested)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        permissionState.refreshPermissionState()
        viewModel.refreshHealthStatus()
    }
    LaunchedEffect(uiState.healthConnectStatus) { permissionState.refreshPermissionState() }
    val values = uiState.preferences.values
    if (values == null) {
        DayScheduleReadContent(
            title = stringResource(Res.string.day_schedule),
            state = uiState.preferences,
            onBack = onBack,
            onRetry = viewModel::retryPreferences,
            modifier = modifier,
        )
        return
    }
    DayBoundarySettingsContent(
        sleepBasedPreferenceEnabled = values.settings.sleepBasedBoundariesEnabled,
        fallbackStartHour = values.fallbackStartHour,
        gateState = uiState.gateState,
        isRequestInFlight = permissionState.isRequestInFlight,
        onBack = onBack,
        onToggleSleepBased = viewModel::toggleSleepBasedBoundaries,
        onSetFallbackHour = viewModel::setFallbackStartHour,
        onRequestPermissions = {
            viewModel.onPermissionRequestStarted()
            permissionState.requestPermission()
        },
        onSetUpHealthConnect = onSetUpHealthConnect,
        onOpenHealthConnectPermissions = onOpenHealthConnectPermissions,
        modifier = modifier,
        sleepWrite = uiState.sleepWrite,
        fallbackWrite = uiState.fallbackWrite,
        readState = uiState.preferences,
        healthCheck = uiState.healthCheck,
        onRetrySleep = viewModel::retrySleepWrite,
        onRetryFallback = viewModel::retryFallbackWrite,
        onRetryPreferences = viewModel::retryPreferences,
        onRetryHealth = viewModel::refreshHealthStatus,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayBoundarySettingsContent(
    sleepBasedPreferenceEnabled: Boolean,
    fallbackStartHour: Int,
    gateState: HealthConnectGateState,
    isRequestInFlight: Boolean,
    onBack: () -> Unit,
    onToggleSleepBased: (Boolean) -> Unit,
    onSetFallbackHour: (Int) -> Unit,
    onRequestPermissions: () -> Unit,
    onSetUpHealthConnect: () -> Unit,
    onOpenHealthConnectPermissions: () -> Unit = {},
    modifier: Modifier = Modifier,
    sleepWrite: DaySettingWriteState<Boolean> = DaySettingWriteState.Idle,
    fallbackWrite: DaySettingWriteState<Int> = DaySettingWriteState.Idle,
    readState: DaySchedulePreferencesState? = null,
    healthCheck: SleepAccessCheckState = SleepAccessCheckState.Idle,
    onRetrySleep: () -> Unit = {},
    onRetryFallback: () -> Unit = {},
    onRetryPreferences: () -> Unit = {},
    onRetryHealth: () -> Unit = {},
) {
    var showTimePicker by remember { mutableStateOf(false) }
    val showSleepBasedToggle = shouldShowSleepBasedToggle(gateState)

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
                Text(
                    text = stringResource(Res.string.day_schedule_detail_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.lg),
                )

                DayScheduleReadFeedback(readState, onRetryPreferences)
                SleepAccessFeedback(healthCheck, onRetryHealth)
                if (showSleepBasedToggle) {
                    MasterFeatureToggle(
                        label = stringResource(Res.string.use_sleep_schedule),
                        checked = sleepBasedPreferenceEnabled,
                        enabled = sleepWrite !is DaySettingWriteState.Saving,
                        onCheckedChange = onToggleSleepBased,
                        modifier = Modifier.padding(horizontal = Spacing.lg),
                    )
                } else {
                    HealthConnectGateCard(
                        gateState = gateState,
                        isRequestInFlight = isRequestInFlight,
                        onRequestPermissions = onRequestPermissions,
                        onSetUpHealthConnect = onSetUpHealthConnect,
                        onOpenHealthConnectPermissions = onOpenHealthConnectPermissions,
                        onDisableSleepBased = { onToggleSleepBased(false) },
                        isChangingPreference = sleepWrite is DaySettingWriteState.Saving,
                        modifier = Modifier.padding(horizontal = Spacing.lg),
                    )
                }
                Column(modifier = Modifier.padding(horizontal = Spacing.lg)) {
                    DaySettingFeedback(sleepWrite, onRetrySleep)
                }
            }
        },
        endPane = {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = Spacing.lg, vertical = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text(
                    text =
                        stringResource(
                            if (showSleepBasedToggle && sleepBasedPreferenceEnabled) {
                                Res.string.day_schedule_enabled_explanation
                            } else {
                                Res.string.day_schedule_disabled_explanation
                            },
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(Spacing.sm))

                MaterialContainer {
                    SettingsRow(
                        headlineContent = {
                            Text(stringResource(Res.string.day_schedule_fallback_time))
                        },
                        supportingContent = {
                            Text(stringResource(Res.string.day_schedule_fallback_time_description))
                        },
                        trailingContent = {
                            TextButton(onClick = { showTimePicker = true }, enabled = fallbackWrite !is DaySettingWriteState.Saving) {
                                Text(
                                    text = formatHour(fallbackStartHour),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                        },
                    )
                }
                DaySettingFeedback(fallbackWrite, onRetryFallback)
            }
        },
        standardContent = {
            SettingsScaffold(
                title = stringResource(Res.string.day_schedule),
                onBack = onBack,
                modifier = modifier,
            ) {
                item {
                    Text(
                        text = stringResource(Res.string.day_schedule_detail_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.lg),
                    )
                }

                if (readState is DaySchedulePreferencesState.Loading ||
                    readState is DaySchedulePreferencesState.Unavailable ||
                    healthCheck == SleepAccessCheckState.Failed
                ) {
                    item {
                        Column(modifier = Modifier.padding(horizontal = Spacing.lg)) {
                            DayScheduleReadFeedback(readState, onRetryPreferences)
                            SleepAccessFeedback(healthCheck, onRetryHealth)
                        }
                    }
                }
                if (showSleepBasedToggle) {
                    item {
                        MasterFeatureToggle(
                            label = stringResource(Res.string.use_sleep_schedule),
                            checked = sleepBasedPreferenceEnabled,
                            enabled = sleepWrite !is DaySettingWriteState.Saving,
                            onCheckedChange = onToggleSleepBased,
                            modifier = Modifier.padding(horizontal = Spacing.lg),
                        )
                    }
                }

                item {
                    Column(
                        modifier = Modifier.padding(horizontal = Spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        if (!showSleepBasedToggle) {
                            HealthConnectGateCard(
                                gateState = gateState,
                                isRequestInFlight = isRequestInFlight,
                                onRequestPermissions = onRequestPermissions,
                                onSetUpHealthConnect = onSetUpHealthConnect,
                                onOpenHealthConnectPermissions = onOpenHealthConnectPermissions,
                                onDisableSleepBased = { onToggleSleepBased(false) },
                                isChangingPreference = sleepWrite is DaySettingWriteState.Saving,
                            )
                        }

                        DaySettingFeedback(sleepWrite, onRetrySleep)
                        Text(
                            text =
                                stringResource(
                                    if (showSleepBasedToggle && sleepBasedPreferenceEnabled) {
                                        Res.string.day_schedule_enabled_explanation
                                    } else {
                                        Res.string.day_schedule_disabled_explanation
                                    },
                                ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Spacer(modifier = Modifier.height(Spacing.sm))

                        MaterialContainer {
                            SettingsRow(
                                headlineContent = {
                                    Text(stringResource(Res.string.day_schedule_fallback_time))
                                },
                                supportingContent = {
                                    Text(stringResource(Res.string.day_schedule_fallback_time_description))
                                },
                                trailingContent = {
                                    TextButton(
                                        onClick = { showTimePicker = true },
                                        enabled = fallbackWrite !is DaySettingWriteState.Saving,
                                    ) {
                                        Text(
                                            text = formatHour(fallbackStartHour),
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                    }
                                },
                            )
                        }
                        DaySettingFeedback(fallbackWrite, onRetryFallback)
                    }
                }
            }
        },
    )

    if (showTimePicker) {
        TimePickerDialog(
            initialHour = fallbackStartHour,
            onConfirm = { hour ->
                onSetFallbackHour(hour)
                showTimePicker = false
            },
            onDismiss = { showTimePicker = false },
        )
    }
}
