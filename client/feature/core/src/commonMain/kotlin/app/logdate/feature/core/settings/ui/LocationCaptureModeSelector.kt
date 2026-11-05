@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.logdate.client.location.settings.LocationCaptureMode
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.location_capture_mode
import logdate.client.feature.core.generated.resources.location_capture_mode_active
import logdate.client.feature.core.generated.resources.location_capture_mode_active_description
import logdate.client.feature.core.generated.resources.location_capture_mode_passive
import logdate.client.feature.core.generated.resources.location_capture_mode_passive_description
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun CaptureModeSelector(
    currentMode: LocationCaptureMode,
    onModeSelected: (LocationCaptureMode) -> Unit,
    state: LocationSettingsState,
    onRetry: (LocationSetting) -> Unit,
    modifier: Modifier = Modifier,
) {
    val available = state as? LocationSettingsState.Available
    val enabled = available != null && !available.isSaving(LocationSetting.CAPTURE_MODE)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(
            text = stringResource(Res.string.location_capture_mode),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(vertical = Spacing.sm),
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = currentMode == LocationCaptureMode.PASSIVE,
                onClick = { onModeSelected(LocationCaptureMode.PASSIVE) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) { Text(stringResource(Res.string.location_capture_mode_passive)) }
            SegmentedButton(
                selected = currentMode == LocationCaptureMode.ACTIVE,
                onClick = { onModeSelected(LocationCaptureMode.ACTIVE) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) { Text(stringResource(Res.string.location_capture_mode_active)) }
        }
        Text(
            text =
                stringResource(
                    if (currentMode == LocationCaptureMode.ACTIVE) {
                        Res.string.location_capture_mode_active_description
                    } else {
                        Res.string.location_capture_mode_passive_description
                    },
                ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (available != null) LocationOperationFeedback(available, LocationSetting.CAPTURE_MODE, onRetry)
    }
}
