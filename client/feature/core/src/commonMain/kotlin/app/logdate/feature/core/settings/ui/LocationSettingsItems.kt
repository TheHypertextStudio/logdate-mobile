@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.logdate.client.location.settings.LocationTrackingSettings
import app.logdate.ui.common.SettingsRow
import app.logdate.ui.common.ToggleSettingsItem
import app.logdate.ui.platform.rememberSystemReduceMotion
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.location_setting_save_failed
import logdate.client.feature.core.generated.resources.location_setting_saving
import logdate.client.feature.core.generated.resources.location_settings_loading
import logdate.client.feature.core.generated.resources.location_settings_read_failed
import logdate.client.feature.core.generated.resources.location_settings_refreshing
import logdate.client.ui.generated.resources.common_retry
import org.jetbrains.compose.resources.stringResource
import logdate.client.ui.generated.resources.Res as UiRes

@Composable
internal fun LocationToggleSetting(
    title: String,
    description: String,
    state: LocationSettingsState,
    setting: LocationSetting,
    value: (LocationTrackingSettings) -> Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onRetry: (LocationSetting) -> Unit,
    onReload: () -> Unit,
) {
    val available = state as? LocationSettingsState.Available
    if (available == null) {
        LocationSettingAvailability(title, state, onReload)
    } else {
        ToggleSettingsItem(
            title = title,
            description = description,
            checked = value(available.settings),
            onCheckedChange = onCheckedChange,
            enabled = !available.isSaving(setting),
        )
        LocationOperationFeedback(available, setting, onRetry)
    }
}

@Composable
internal fun LocationSettingAvailability(
    title: String,
    state: LocationSettingsState,
    onReload: () -> Unit,
) {
    SettingsRow(
        headlineContent = { Text(title) },
        supportingContent = {
            Text(
                stringResource(
                    if (state ==
                        LocationSettingsState.Unavailable
                    ) {
                        Res.string.location_settings_read_failed
                    } else {
                        Res.string.location_settings_loading
                    },
                ),
            )
        },
        trailingContent = {
            if (state == LocationSettingsState.Unavailable) {
                TextButton(onClick = onReload) { Text(stringResource(UiRes.string.common_retry)) }
            } else {
                LocationSettingProgress()
            }
        },
    )
}

@Composable
internal fun LocationReadFeedback(
    state: LocationSettingsState,
    onReload: () -> Unit,
) {
    val available = state as? LocationSettingsState.Available ?: return
    if (available.isRefreshing || available.refreshFailed) {
        LocationFeedbackRow(
            message =
                stringResource(
                    if (available.refreshFailed) Res.string.location_settings_read_failed else Res.string.location_settings_refreshing,
                ),
            pending = available.isRefreshing,
            failed = available.refreshFailed,
            onRetry = onReload,
        )
    }
}

@Composable
internal fun LocationOperationFeedback(
    state: LocationSettingsState.Available,
    setting: LocationSetting,
    onRetry: (LocationSetting) -> Unit,
) {
    val operation = state.operation(setting)
    if (operation == LocationSettingOperation.Idle) return
    LocationFeedbackRow(
        message =
            stringResource(
                if (operation is LocationSettingOperation.Failed) {
                    Res.string.location_setting_save_failed
                } else {
                    Res.string.location_setting_saving
                },
            ),
        pending = operation is LocationSettingOperation.Saving,
        failed = operation is LocationSettingOperation.Failed,
        onRetry = { onRetry(setting) },
    )
}

@Composable
private fun LocationFeedbackRow(
    message: String,
    pending: Boolean,
    failed: Boolean,
    onRetry: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
                .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (pending) LocationSettingProgress()
        Text(
            text = message,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (failed) TextButton(onClick = onRetry) { Text(stringResource(UiRes.string.common_retry)) }
    }
}

@Composable
private fun LocationSettingProgress() {
    val reduceMotion by rememberSystemReduceMotion()
    if (reduceMotion) {
        Icon(Icons.Default.HourglassEmpty, contentDescription = null, modifier = Modifier.size(20.dp))
    } else {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}
