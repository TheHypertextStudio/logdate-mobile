@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.logdate.client.location.settings.LocationTrackingSettings
import app.logdate.ui.adaptive.FoldableBookLayout
import app.logdate.ui.common.SettingsScaffold
import app.logdate.ui.common.SettingsSection
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.location_services
import logdate.client.feature.core.generated.resources.location_track_journal_entries
import logdate.client.feature.core.generated.resources.location_track_journal_entries_description
import logdate.client.feature.core.generated.resources.location_track_timeline_review
import logdate.client.feature.core.generated.resources.location_track_timeline_review_description
import logdate.client.feature.core.generated.resources.location_tracking_options
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun LocationTrackingOptionsScreen(
    onBack: () -> Unit,
    viewModel: LocationSettingsViewModel = koinViewModel(),
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LocationTrackingOptionsContent(
        state = uiState,
        onBack = onBack,
        onToggleJournalTracking = viewModel::toggleJournalTracking,
        onToggleTimelineTracking = viewModel::toggleTimelineTracking,
        modifier = modifier,
        onRetry = viewModel::retry,
        onReload = viewModel::reload,
    )
}

@Composable
fun LocationTrackingOptionsContent(
    settings: LocationTrackingSettings,
    onBack: () -> Unit,
    onToggleJournalTracking: (Boolean) -> Unit,
    onToggleTimelineTracking: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    LocationTrackingOptionsContent(
        state = LocationSettingsState.Available(settings),
        onBack = onBack,
        onToggleJournalTracking = onToggleJournalTracking,
        onToggleTimelineTracking = onToggleTimelineTracking,
        modifier = modifier,
    )
}

@Composable
fun LocationTrackingOptionsContent(
    state: LocationSettingsState,
    onBack: () -> Unit,
    onToggleJournalTracking: (Boolean) -> Unit,
    onToggleTimelineTracking: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onRetry: (LocationSetting) -> Unit = {},
    onReload: () -> Unit = {},
) {
    FoldableBookLayout(
        modifier = modifier.fillMaxSize(),
        minPaneWidth = 320.dp,
        startPane = {
            SettingsSection(
                title = stringResource(Res.string.location_services),
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = Spacing.lg, vertical = Spacing.lg),
            ) {
                LocationReadFeedback(state, onReload)
                LocationToggleSetting(
                    title = stringResource(Res.string.location_track_journal_entries),
                    description = stringResource(Res.string.location_track_journal_entries_description),
                    state = state,
                    setting = LocationSetting.JOURNAL_TRACKING,
                    value = { it.autoTrackForJournalEntries },
                    onCheckedChange = onToggleJournalTracking,
                    onRetry = onRetry,
                    onReload = onReload,
                )
            }
        },
        endPane = {
            SettingsSection(
                title = stringResource(Res.string.location_tracking_options),
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = Spacing.lg, vertical = Spacing.lg),
            ) {
                LocationToggleSetting(
                    title = stringResource(Res.string.location_track_timeline_review),
                    description = stringResource(Res.string.location_track_timeline_review_description),
                    state = state,
                    setting = LocationSetting.TIMELINE_TRACKING,
                    value = { it.autoTrackForTimelineReview },
                    onCheckedChange = onToggleTimelineTracking,
                    onRetry = onRetry,
                    onReload = onReload,
                )
            }
        },
        standardContent = {
            SettingsScaffold(
                title = stringResource(Res.string.location_tracking_options),
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
                            title = stringResource(Res.string.location_track_journal_entries),
                            description = stringResource(Res.string.location_track_journal_entries_description),
                            state = state,
                            setting = LocationSetting.JOURNAL_TRACKING,
                            value = { it.autoTrackForJournalEntries },
                            onCheckedChange = onToggleJournalTracking,
                            onRetry = onRetry,
                            onReload = onReload,
                        )
                        LocationToggleSetting(
                            title = stringResource(Res.string.location_track_timeline_review),
                            description = stringResource(Res.string.location_track_timeline_review_description),
                            state = state,
                            setting = LocationSetting.TIMELINE_TRACKING,
                            value = { it.autoTrackForTimelineReview },
                            onCheckedChange = onToggleTimelineTracking,
                            onRetry = onRetry,
                            onReload = onReload,
                        )
                    }
                }
            }
        },
    )
}
