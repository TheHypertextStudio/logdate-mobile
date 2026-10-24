@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.events.ui.calendarsync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.logdate.client.permissions.rememberCalendarPermissionState
import app.logdate.ui.adaptive.FoldableBookLayout
import app.logdate.ui.common.MasterFeatureToggle
import app.logdate.ui.common.SettingsFeatureGroup
import app.logdate.ui.common.SettingsNavigationItem
import app.logdate.ui.common.SettingsScaffold
import app.logdate.ui.common.SettingsSection
import app.logdate.ui.theme.Spacing
import logdate.client.feature.events.generated.resources.Res
import logdate.client.feature.events.generated.resources.calendar_sync_settings_activity_row_subtitle
import logdate.client.feature.events.generated.resources.calendar_sync_settings_activity_row_title
import logdate.client.feature.events.generated.resources.calendar_sync_settings_calendars_row_subtitle_none
import logdate.client.feature.events.generated.resources.calendar_sync_settings_calendars_row_subtitle_some
import logdate.client.feature.events.generated.resources.calendar_sync_settings_calendars_row_title
import logdate.client.feature.events.generated.resources.calendar_sync_settings_description
import logdate.client.feature.events.generated.resources.calendar_sync_settings_grant_action
import logdate.client.feature.events.generated.resources.calendar_sync_settings_grant_rationale
import logdate.client.feature.events.generated.resources.calendar_sync_settings_grant_title
import logdate.client.feature.events.generated.resources.calendar_sync_settings_master_toggle
import logdate.client.feature.events.generated.resources.calendar_sync_settings_title
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Calendar sync overview screen.
 *
 * Top-level entry into the device calendar import flow. Renders a permission state
 * machine first (rationale → grant → granted), then the master toggle, then two rows
 * that drill into the per-calendar picker and the recent imports list. The runtime
 * permission flow lives at the Composable layer because it needs
 * `rememberLauncherForActivityResult`; the result is fed back into the ViewModel via
 * [CalendarSyncOverviewViewModel.setPermissionState].
 */
@Composable
fun CalendarSyncSettingsScreen(
    onBack: () -> Unit,
    onNavigateToCalendars: () -> Unit,
    onNavigateToActivity: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CalendarSyncOverviewViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val permissionState = rememberCalendarPermissionState()

    LaunchedEffect(permissionState.hasPermission, permissionState.permissionRequested) {
        viewModel.setPermissionState(
            when {
                permissionState.hasPermission -> PermissionState.Granted
                permissionState.permissionRequested -> PermissionState.Denied
                else -> PermissionState.Unknown
            },
        )
    }

    CalendarSyncSettingsContent(
        uiState = uiState,
        onBack = onBack,
        onRequestPermission = permissionState.requestPermission,
        onSyncEnabledToggled = viewModel::setSyncEnabled,
        onNavigateToCalendars = onNavigateToCalendars,
        onNavigateToActivity = onNavigateToActivity,
        modifier = modifier,
    )
}

@Composable
fun CalendarSyncSettingsContent(
    uiState: CalendarSyncOverviewUiState,
    onBack: () -> Unit,
    onRequestPermission: () -> Unit,
    onSyncEnabledToggled: (Boolean) -> Unit,
    onNavigateToCalendars: () -> Unit,
    onNavigateToActivity: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
                    text = stringResource(Res.string.calendar_sync_settings_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.lg),
                )

                when (uiState.permissionState) {
                    PermissionState.Granted -> {
                        MasterFeatureToggle(
                            label = stringResource(Res.string.calendar_sync_settings_master_toggle),
                            checked = uiState.isSyncEnabled,
                            onCheckedChange = onSyncEnabledToggled,
                            modifier = Modifier.padding(horizontal = Spacing.lg),
                        )
                    }

                    PermissionState.Denied,
                    PermissionState.Unknown,
                    -> {
                        PermissionRequestCard(
                            onRequestPermission = onRequestPermission,
                            modifier = Modifier.padding(horizontal = Spacing.lg),
                        )
                    }
                }
            }
        },
        endPane = {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                if (uiState.permissionState == PermissionState.Granted) {
                    SettingsFeatureGroup(enabled = uiState.isSyncEnabled) {
                        SettingsSection(
                            title = stringResource(Res.string.calendar_sync_settings_title),
                            modifier = Modifier.padding(horizontal = Spacing.lg),
                        ) {
                            SettingsNavigationItem(
                                title = stringResource(Res.string.calendar_sync_settings_calendars_row_title),
                                description =
                                    if (uiState.selectedCalendarCount == 0) {
                                        stringResource(Res.string.calendar_sync_settings_calendars_row_subtitle_none)
                                    } else {
                                        stringResource(
                                            Res.string.calendar_sync_settings_calendars_row_subtitle_some,
                                            uiState.selectedCalendarCount,
                                            uiState.totalCalendarCount,
                                        )
                                    },
                                icon = { Icon(Icons.Default.CalendarMonth, contentDescription = null) },
                                onClick = onNavigateToCalendars,
                            )
                            SettingsNavigationItem(
                                title = stringResource(Res.string.calendar_sync_settings_activity_row_title),
                                description = stringResource(Res.string.calendar_sync_settings_activity_row_subtitle),
                                icon = { Icon(Icons.Default.History, contentDescription = null) },
                                onClick = onNavigateToActivity,
                            )
                        }
                    }
                }
            }
        },
        standardContent = {
            SettingsScaffold(
                title = stringResource(Res.string.calendar_sync_settings_title),
                onBack = onBack,
                modifier = modifier,
            ) {
                item {
                    Text(
                        text = stringResource(Res.string.calendar_sync_settings_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.lg),
                    )
                }

                when (uiState.permissionState) {
                    PermissionState.Granted -> {
                        item {
                            MasterFeatureToggle(
                                label = stringResource(Res.string.calendar_sync_settings_master_toggle),
                                checked = uiState.isSyncEnabled,
                                onCheckedChange = onSyncEnabledToggled,
                                modifier = Modifier.padding(horizontal = Spacing.lg),
                            )
                        }
                        item {
                            SettingsFeatureGroup(enabled = uiState.isSyncEnabled) {
                                SettingsSection(
                                    title = stringResource(Res.string.calendar_sync_settings_title),
                                    modifier = Modifier.padding(horizontal = Spacing.lg),
                                ) {
                                    SettingsNavigationItem(
                                        title = stringResource(Res.string.calendar_sync_settings_calendars_row_title),
                                        description =
                                            if (uiState.selectedCalendarCount == 0) {
                                                stringResource(Res.string.calendar_sync_settings_calendars_row_subtitle_none)
                                            } else {
                                                stringResource(
                                                    Res.string.calendar_sync_settings_calendars_row_subtitle_some,
                                                    uiState.selectedCalendarCount,
                                                    uiState.totalCalendarCount,
                                                )
                                            },
                                        icon = { Icon(Icons.Default.CalendarMonth, contentDescription = null) },
                                        onClick = onNavigateToCalendars,
                                    )
                                    SettingsNavigationItem(
                                        title = stringResource(Res.string.calendar_sync_settings_activity_row_title),
                                        description = stringResource(Res.string.calendar_sync_settings_activity_row_subtitle),
                                        icon = { Icon(Icons.Default.History, contentDescription = null) },
                                        onClick = onNavigateToActivity,
                                    )
                                }
                            }
                        }
                    }

                    PermissionState.Denied,
                    PermissionState.Unknown,
                    -> {
                        item {
                            PermissionRequestCard(
                                onRequestPermission = onRequestPermission,
                                modifier = Modifier.padding(horizontal = Spacing.lg),
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun PermissionRequestCard(
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(Spacing.md),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                text = stringResource(Res.string.calendar_sync_settings_grant_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(Res.string.calendar_sync_settings_grant_rationale),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(onClick = onRequestPermission) {
                Text(stringResource(Res.string.calendar_sync_settings_grant_action))
            }
        }
    }
}
