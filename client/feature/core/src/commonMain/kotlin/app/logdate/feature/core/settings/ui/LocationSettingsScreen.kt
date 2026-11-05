@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.logdate.client.location.settings.LocationCaptureMode
import app.logdate.client.location.settings.LocationTrackingSettings
import app.logdate.ui.adaptive.FoldableBookLayout
import app.logdate.ui.common.SettingsNavigationItem
import app.logdate.ui.common.SettingsScaffold
import app.logdate.ui.common.SettingsSection
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.location_background_tracking_description
import logdate.client.feature.core.generated.resources.location_data_privacy_note
import logdate.client.feature.core.generated.resources.location_data_stored_on_device
import logdate.client.feature.core.generated.resources.location_enable_background_tracking
import logdate.client.feature.core.generated.resources.location_services
import logdate.client.feature.core.generated.resources.location_settings
import logdate.client.feature.core.generated.resources.location_timeline
import logdate.client.feature.core.generated.resources.location_timeline_description
import logdate.client.feature.core.generated.resources.location_tracking_options
import logdate.client.feature.core.generated.resources.location_tracking_options_description
import logdate.client.feature.core.generated.resources.location_update_interval
import logdate.client.feature.core.generated.resources.location_update_interval_description
import logdate.client.feature.core.generated.resources.view_location_timeline
import logdate.client.feature.core.generated.resources.view_timeline
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Location settings overview screen with navigation to detail screens.
 *
 * Shows the primary background tracking toggle, a tracking mode selector,
 * and navigation items to detail screens for tracking options and interval.
 */
@Composable
fun LocationSettingsScreen(
    onBack: () -> Unit,
    onOpenLocationTimeline: () -> Unit,
    onShowLocationTimeline: () -> Unit = onOpenLocationTimeline,
    onNavigateToTrackingOptions: () -> Unit = {},
    onNavigateToInterval: () -> Unit = {},
    viewModel: LocationSettingsViewModel = koinViewModel(),
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LocationSettingsContent(
        state = uiState,
        onBack = onBack,
        onToggleBackgroundTracking = viewModel::toggleBackgroundTracking,
        onSetCaptureMode = viewModel::setCaptureMode,
        onShowLocationTimeline = onShowLocationTimeline,
        onNavigateToTrackingOptions = onNavigateToTrackingOptions,
        onNavigateToInterval = onNavigateToInterval,
        onRetry = viewModel::retry,
        onReload = viewModel::reload,
        modifier = modifier,
    )
}

@Composable
fun LocationSettingsContent(
    settings: LocationTrackingSettings,
    onBack: () -> Unit,
    onToggleBackgroundTracking: (Boolean) -> Unit,
    onSetCaptureMode: (LocationCaptureMode) -> Unit,
    onShowLocationTimeline: () -> Unit,
    onNavigateToTrackingOptions: () -> Unit,
    onNavigateToInterval: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LocationSettingsContent(
        state = LocationSettingsState.Available(settings),
        onBack = onBack,
        onToggleBackgroundTracking = onToggleBackgroundTracking,
        onSetCaptureMode = onSetCaptureMode,
        onShowLocationTimeline = onShowLocationTimeline,
        onNavigateToTrackingOptions = onNavigateToTrackingOptions,
        onNavigateToInterval = onNavigateToInterval,
        modifier = modifier,
    )
}

@Composable
fun LocationSettingsContent(
    state: LocationSettingsState,
    onBack: () -> Unit,
    onToggleBackgroundTracking: (Boolean) -> Unit,
    onSetCaptureMode: (LocationCaptureMode) -> Unit,
    onShowLocationTimeline: () -> Unit,
    onNavigateToTrackingOptions: () -> Unit,
    onNavigateToInterval: () -> Unit,
    modifier: Modifier = Modifier,
    onRetry: (LocationSetting) -> Unit = {},
    onReload: () -> Unit = {},
) {
    val settings = (state as? LocationSettingsState.Available)?.settings
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
                SettingsSection(
                    title = stringResource(Res.string.location_services),
                    modifier = Modifier.padding(horizontal = Spacing.lg),
                ) {
                    LocationReadFeedback(state, onReload)
                    LocationToggleSetting(
                        title = stringResource(Res.string.location_enable_background_tracking),
                        description = stringResource(Res.string.location_background_tracking_description),
                        state = state,
                        setting = LocationSetting.BACKGROUND_TRACKING,
                        value = { it.backgroundTrackingEnabled },
                        onCheckedChange = onToggleBackgroundTracking,
                        onRetry = onRetry,
                        onReload = onReload,
                    )

                    SettingsNavigationItem(
                        title = stringResource(Res.string.location_tracking_options),
                        description = stringResource(Res.string.location_tracking_options_description),
                        onClick = onNavigateToTrackingOptions,
                    )
                }

                if (settings?.backgroundTrackingEnabled == true) {
                    CaptureModeSelector(
                        currentMode = settings.captureMode,
                        onModeSelected = onSetCaptureMode,
                        modifier = Modifier.padding(horizontal = Spacing.lg),
                        state = state,
                        onRetry = onRetry,
                    )
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
                if (settings?.backgroundTrackingEnabled == true && settings.captureMode == LocationCaptureMode.PASSIVE) {
                    SettingsSection(
                        title = "",
                        modifier = Modifier.padding(horizontal = Spacing.lg),
                    ) {
                        SettingsNavigationItem(
                            title = stringResource(Res.string.location_update_interval),
                            description = stringResource(Res.string.location_update_interval_description),
                            onClick = onNavigateToInterval,
                        )
                    }
                }

                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.lg),
                ) {
                    Text(
                        text = stringResource(Res.string.location_timeline),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(vertical = Spacing.sm),
                    )
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onShowLocationTimeline,
                    ) {
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(Spacing.md),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                        ) {
                            Icon(
                                Icons.Default.Timeline,
                                contentDescription = stringResource(Res.string.view_timeline),
                                tint = MaterialTheme.colorScheme.primary,
                            )

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(Res.string.view_location_timeline),
                                    style = MaterialTheme.typography.titleSmall,
                                )

                                Text(
                                    stringResource(Res.string.location_timeline_description),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                LocationSettingsNotes()
            }
        },
        standardContent = {
            SettingsScaffold(
                title = stringResource(Res.string.location_settings),
                onBack = onBack,
                modifier = modifier,
            ) {
                item {
                    SettingsSection(
                        title = stringResource(Res.string.location_services),
                        modifier = Modifier.padding(horizontal = Spacing.lg),
                    ) {
                        LocationReadFeedback(state, onReload)
                        LocationToggleSetting(
                            title = stringResource(Res.string.location_enable_background_tracking),
                            description = stringResource(Res.string.location_background_tracking_description),
                            state = state,
                            setting = LocationSetting.BACKGROUND_TRACKING,
                            value = { it.backgroundTrackingEnabled },
                            onCheckedChange = onToggleBackgroundTracking,
                            onRetry = onRetry,
                            onReload = onReload,
                        )

                        SettingsNavigationItem(
                            title = stringResource(Res.string.location_tracking_options),
                            description = stringResource(Res.string.location_tracking_options_description),
                            onClick = onNavigateToTrackingOptions,
                        )
                    }
                }

                if (settings?.backgroundTrackingEnabled == true) {
                    item {
                        CaptureModeSelector(
                            currentMode = settings.captureMode,
                            onModeSelected = onSetCaptureMode,
                            modifier = Modifier.padding(horizontal = Spacing.lg),
                            state = state,
                            onRetry = onRetry,
                        )
                    }

                    if (settings.captureMode == LocationCaptureMode.PASSIVE) {
                        item {
                            SettingsSection(
                                title = "",
                                modifier = Modifier.padding(horizontal = Spacing.lg),
                            ) {
                                SettingsNavigationItem(
                                    title = stringResource(Res.string.location_update_interval),
                                    description = stringResource(Res.string.location_update_interval_description),
                                    onClick = onNavigateToInterval,
                                )
                            }
                        }
                    }
                }

                item {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
                    ) {
                        Text(
                            text = stringResource(Res.string.location_timeline),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(vertical = Spacing.sm),
                        )
                        OutlinedCard(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = onShowLocationTimeline,
                        ) {
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(Spacing.md),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                            ) {
                                Icon(
                                    Icons.Default.Timeline,
                                    contentDescription = stringResource(Res.string.view_timeline),
                                    tint = MaterialTheme.colorScheme.primary,
                                )

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        stringResource(Res.string.view_location_timeline),
                                        style = MaterialTheme.typography.titleSmall,
                                    )

                                    Text(
                                        stringResource(Res.string.location_timeline_description),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    LocationSettingsNotes()
                }
            }
        },
    )
}


@Composable
private fun LocationSettingsNotes() {
    Surface {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Icon(
                    Icons.Default.LocationOn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )

                Text(
                    stringResource(Res.string.location_data_stored_on_device),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                )
            }

            Text(
                stringResource(Res.string.location_data_privacy_note),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
