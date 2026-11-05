@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.logdate.client.location.settings.LocationTrackingSettings
import app.logdate.ui.adaptive.FoldableBookLayout
import app.logdate.ui.common.SettingsScaffold
import app.logdate.ui.common.SettingsSection
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.location_interval_selected_frequency
import logdate.client.feature.core.generated.resources.location_tracking_battery_note
import logdate.client.feature.core.generated.resources.location_update_frequency
import logdate.client.feature.core.generated.resources.location_update_interval
import logdate.client.feature.core.generated.resources.tracking_interval
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun LocationIntervalScreen(
    onBack: () -> Unit,
    viewModel: LocationSettingsViewModel = koinViewModel(),
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LocationIntervalContent(
        state = state,
        onBack = onBack,
        onUpdateTrackingInterval = viewModel::updateTrackingInterval,
        onRetry = viewModel::retry,
        onReload = viewModel::reload,
        modifier = modifier,
    )
}

@Composable
fun LocationIntervalContent(
    settings: LocationTrackingSettings,
    onBack: () -> Unit,
    onUpdateTrackingInterval: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    LocationIntervalContent(LocationSettingsState.Available(settings), onBack, onUpdateTrackingInterval, modifier)
}

@Composable
fun LocationIntervalContent(
    state: LocationSettingsState,
    onBack: () -> Unit,
    onUpdateTrackingInterval: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onRetry: (LocationSetting) -> Unit = {},
    onReload: () -> Unit = {},
) {
    val available = state as? LocationSettingsState.Available
    FoldableBookLayout(
        modifier = modifier.fillMaxSize(),
        minPaneWidth = 320.dp,
        startPane = {
            SettingsSection(
                title = stringResource(Res.string.location_update_interval),
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = Spacing.lg, vertical = Spacing.lg),
            ) {
                if (available == null) {
                    LocationSettingAvailability(stringResource(Res.string.location_update_interval), state, onReload)
                } else {
                    Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        Text(
                            stringResource(Res.string.location_update_frequency, available.settings.minimumPersistIntervalMinutes),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            stringResource(Res.string.location_tracking_battery_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        endPane = {
            SettingsSection(
                title = stringResource(Res.string.tracking_interval),
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = Spacing.lg, vertical = Spacing.lg),
            ) {
                LocationIntervalEditor(state, onUpdateTrackingInterval, onRetry, onReload, showBatteryNote = false)
            }
        },
        standardContent = {
            SettingsScaffold(title = stringResource(Res.string.location_update_interval), onBack = onBack, modifier = modifier) {
                item {
                    SettingsSection(
                        title = stringResource(Res.string.tracking_interval),
                        modifier = Modifier.padding(horizontal = Spacing.lg),
                    ) {
                        LocationIntervalEditor(state, onUpdateTrackingInterval, onRetry, onReload, showBatteryNote = true)
                    }
                }
            }
        },
    )
}

@Composable
private fun LocationIntervalEditor(
    state: LocationSettingsState,
    onUpdate: (Long) -> Unit,
    onRetry: (LocationSetting) -> Unit,
    onReload: () -> Unit,
    showBatteryNote: Boolean,
) {
    val available = state as? LocationSettingsState.Available
    if (available == null) {
        LocationSettingAvailability(stringResource(Res.string.tracking_interval), state, onReload)
        return
    }
    val intervalOptions = listOf(2L, 5L, 10L, 15L, 30L, 60L, 120L)
    val persistedMinutes = available.settings.minimumPersistIntervalMinutes
    val persistedIndex = intervalOptions.indexOfLast { it <= persistedMinutes }.coerceAtLeast(0)
    var selectedIndex by remember(persistedMinutes) { mutableFloatStateOf(persistedIndex.toFloat()) }
    val selectedMinutes = intervalOptions[selectedIndex.toInt().coerceIn(0, intervalOptions.lastIndex)]

    Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        LocationReadFeedback(state, onReload)
        Text(stringResource(Res.string.location_update_frequency, persistedMinutes), style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = selectedIndex,
            onValueChange = { selectedIndex = it },
            onValueChangeFinished = { onUpdate(intervalOptions[selectedIndex.toInt().coerceIn(0, intervalOptions.lastIndex)]) },
            enabled = !available.isSaving(LocationSetting.INTERVAL),
            valueRange = 0f..intervalOptions.lastIndex.toFloat(),
            steps = intervalOptions.size - 2,
            modifier = Modifier.fillMaxWidth(),
        )
        if (selectedMinutes != persistedMinutes) {
            Text(
                stringResource(Res.string.location_interval_selected_frequency, selectedMinutes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LocationOperationFeedback(available, LocationSetting.INTERVAL, onRetry)
        if (showBatteryNote) {
            Text(
                stringResource(Res.string.location_tracking_battery_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
