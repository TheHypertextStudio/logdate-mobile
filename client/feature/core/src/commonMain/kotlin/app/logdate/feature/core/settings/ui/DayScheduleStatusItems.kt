@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HourglassEmpty
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
import app.logdate.ui.common.SettingsScaffold
import app.logdate.ui.platform.rememberSystemReduceMotion
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.day_schedule_health_failed
import logdate.client.feature.core.generated.resources.settings_retry_action
import logdate.client.feature.core.generated.resources.settings_value_loading
import logdate.client.feature.core.generated.resources.settings_values_failed
import logdate.client.feature.core.generated.resources.settings_write_failed
import logdate.client.feature.core.generated.resources.settings_write_pending
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DayScheduleReadContent(
    title: String,
    state: DaySchedulePreferencesState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier,
) {
    SettingsScaffold(title, onBack, modifier) {
        item {
            Column(modifier = Modifier.padding(horizontal = Spacing.lg)) {
                DayScheduleReadFeedback(state, onRetry)
            }
        }
    }
}

@Composable
internal fun DayScheduleReadFeedback(
    state: DaySchedulePreferencesState?,
    onRetry: () -> Unit,
) {
    when (state) {
        is DaySchedulePreferencesState.Loading ->
            DaySettingsStatusRow(stringResource(Res.string.settings_value_loading), pending = true)
        is DaySchedulePreferencesState.Unavailable ->
            DaySettingsStatusRow(stringResource(Res.string.settings_values_failed), onRetry = onRetry)
        else -> Unit
    }
}

@Composable
internal fun DaySettingFeedback(
    state: DaySettingWriteState<*>,
    onRetry: () -> Unit,
) {
    when (state) {
        is DaySettingWriteState.Saving ->
            DaySettingsStatusRow(stringResource(Res.string.settings_write_pending), pending = true)
        is DaySettingWriteState.Failed ->
            DaySettingsStatusRow(stringResource(Res.string.settings_write_failed), onRetry = onRetry)
        DaySettingWriteState.Idle -> Unit
    }
}

@Composable
internal fun SleepAccessFeedback(
    state: SleepAccessCheckState,
    onRetry: () -> Unit,
) {
    if (state == SleepAccessCheckState.Failed) {
        DaySettingsStatusRow(stringResource(Res.string.day_schedule_health_failed), onRetry = onRetry)
    }
}

@Composable
private fun DaySettingsStatusRow(
    message: String,
    pending: Boolean = false,
    onRetry: (() -> Unit)? = null,
) {
    val reduceMotion by rememberSystemReduceMotion()
    Row(
        modifier =
            Modifier.fillMaxWidth().animateContentSize(tween(if (reduceMotion) 0 else 160)).semantics {
                liveRegion = LiveRegionMode.Polite
            },
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (pending) {
            if (reduceMotion) {
                Icon(Icons.Outlined.HourglassEmpty, null, Modifier.size(16.dp))
            } else {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }
        Text(
            message,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = if (onRetry != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onRetry != null) TextButton(onClick = onRetry) { Text(stringResource(Res.string.settings_retry_action)) }
    }
}
