@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.logdate.client.domain.dayboundary.HealthConnectGateKind
import app.logdate.client.domain.dayboundary.HealthConnectGateState
import app.logdate.client.domain.dayboundary.HealthConnectMissingRequirement
import app.logdate.ui.theme.Spacing
import app.logdate.util.asTime
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.todayIn
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.day_schedule_health_checking
import logdate.client.feature.core.generated.resources.day_schedule_health_checking_detail
import logdate.client.feature.core.generated.resources.day_schedule_health_grant_access
import logdate.client.feature.core.generated.resources.day_schedule_health_not_available
import logdate.client.feature.core.generated.resources.day_schedule_health_not_available_detail
import logdate.client.feature.core.generated.resources.day_schedule_health_open_settings
import logdate.client.feature.core.generated.resources.day_schedule_health_permission_denied_detail
import logdate.client.feature.core.generated.resources.day_schedule_health_permissions_needed
import logdate.client.feature.core.generated.resources.day_schedule_health_permissions_needed_detail
import logdate.client.feature.core.generated.resources.day_schedule_health_recovery_permissions_detail
import logdate.client.feature.core.generated.resources.day_schedule_health_recovery_setup_detail
import logdate.client.feature.core.generated.resources.day_schedule_health_recovery_title
import logdate.client.feature.core.generated.resources.day_schedule_health_recovery_unavailable_detail
import logdate.client.feature.core.generated.resources.day_schedule_health_set_up
import logdate.client.feature.core.generated.resources.day_schedule_health_setup_required
import logdate.client.feature.core.generated.resources.day_schedule_health_setup_required_detail
import logdate.client.feature.core.generated.resources.day_schedule_health_turn_off
import logdate.client.ui.generated.resources.common_cancel
import logdate.client.ui.generated.resources.common_confirm
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import logdate.client.ui.generated.resources.Res as UiRes

internal fun shouldShowSleepBasedToggle(gateState: HealthConnectGateState): Boolean = gateState.kind == HealthConnectGateKind.READY

@Composable
internal fun HealthConnectGateCard(
    gateState: HealthConnectGateState,
    isRequestInFlight: Boolean,
    onRequestPermissions: () -> Unit,
    onSetUpHealthConnect: () -> Unit,
    onOpenHealthConnectPermissions: () -> Unit,
    onDisableSleepBased: () -> Unit,
    isChangingPreference: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val titleRes =
        when (gateState.kind) {
            HealthConnectGateKind.CHECKING -> Res.string.day_schedule_health_checking
            HealthConnectGateKind.NEEDS_SETUP -> Res.string.day_schedule_health_setup_required
            HealthConnectGateKind.NEEDS_PERMISSION,
            HealthConnectGateKind.PERMISSION_DENIED,
            -> Res.string.day_schedule_health_permissions_needed
            HealthConnectGateKind.UNAVAILABLE -> Res.string.day_schedule_health_not_available
            HealthConnectGateKind.RECOVERY_REQUIRED -> Res.string.day_schedule_health_recovery_title
            HealthConnectGateKind.READY -> Res.string.day_schedule_health_checking
        }
    val descriptionRes = resolveDayBoundaryGateDescription(gateState)
    val primaryActionLabelRes = resolveDayBoundaryPrimaryActionLabel(gateState)
    val showDisableAction = gateState.kind == HealthConnectGateKind.RECOVERY_REQUIRED

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
                text = stringResource(titleRes),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(descriptionRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            primaryActionLabelRes?.let { labelRes ->
                FilledTonalButton(
                    onClick = {
                        when (gateState.kind) {
                            // First-time ask: show the system permission dialog.
                            HealthConnectGateKind.NEEDS_PERMISSION -> onRequestPermissions()
                            // Already denied or revoked: re-requesting the dialog is pointless.
                            // Send the user to Health Connect settings to grant manually.
                            HealthConnectGateKind.PERMISSION_DENIED,
                            HealthConnectGateKind.RECOVERY_REQUIRED,
                            ->
                                when (gateState.missingRequirement) {
                                    HealthConnectMissingRequirement.PERMISSION -> onOpenHealthConnectPermissions()
                                    HealthConnectMissingRequirement.SETUP -> onSetUpHealthConnect()
                                    HealthConnectMissingRequirement.UNAVAILABLE, null -> Unit
                                }
                            HealthConnectGateKind.NEEDS_SETUP -> onSetUpHealthConnect()
                            HealthConnectGateKind.CHECKING,
                            HealthConnectGateKind.UNAVAILABLE,
                            HealthConnectGateKind.READY,
                            -> Unit
                        }
                    },
                    enabled = !isRequestInFlight,
                ) {
                    Text(stringResource(labelRes))
                }
            }
            if (showDisableAction) {
                TextButton(onClick = onDisableSleepBased, enabled = !isChangingPreference) {
                    Text(stringResource(Res.string.day_schedule_health_turn_off))
                }
            }
        }
    }
}

private fun resolveDayBoundaryGateDescription(gateState: HealthConnectGateState): StringResource =
    when (gateState.kind) {
        HealthConnectGateKind.CHECKING -> Res.string.day_schedule_health_checking_detail
        HealthConnectGateKind.NEEDS_SETUP -> Res.string.day_schedule_health_setup_required_detail
        HealthConnectGateKind.NEEDS_PERMISSION -> Res.string.day_schedule_health_permissions_needed_detail
        HealthConnectGateKind.PERMISSION_DENIED -> Res.string.day_schedule_health_permission_denied_detail
        HealthConnectGateKind.UNAVAILABLE -> Res.string.day_schedule_health_not_available_detail
        HealthConnectGateKind.READY -> Res.string.day_schedule_health_checking_detail
        HealthConnectGateKind.RECOVERY_REQUIRED ->
            when (gateState.missingRequirement) {
                HealthConnectMissingRequirement.PERMISSION -> Res.string.day_schedule_health_recovery_permissions_detail
                HealthConnectMissingRequirement.SETUP -> Res.string.day_schedule_health_recovery_setup_detail
                HealthConnectMissingRequirement.UNAVAILABLE,
                null,
                -> Res.string.day_schedule_health_recovery_unavailable_detail
            }
    }

private fun resolveDayBoundaryPrimaryActionLabel(gateState: HealthConnectGateState): StringResource? =
    when (gateState.kind) {
        // First-time ask: the system dialog will show.
        HealthConnectGateKind.NEEDS_PERMISSION -> Res.string.day_schedule_health_grant_access
        // Already denied: open Health Connect settings so the user can grant manually.
        HealthConnectGateKind.PERMISSION_DENIED -> Res.string.day_schedule_health_open_settings
        HealthConnectGateKind.NEEDS_SETUP -> Res.string.day_schedule_health_set_up
        HealthConnectGateKind.RECOVERY_REQUIRED ->
            when (gateState.missingRequirement) {
                HealthConnectMissingRequirement.PERMISSION -> Res.string.day_schedule_health_open_settings
                HealthConnectMissingRequirement.SETUP -> Res.string.day_schedule_health_set_up
                HealthConnectMissingRequirement.UNAVAILABLE,
                null,
                -> null
            }
        HealthConnectGateKind.CHECKING,
        HealthConnectGateKind.UNAVAILABLE,
        HealthConnectGateKind.READY,
        -> null
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimePickerDialog(
    initialHour: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val state =
        rememberTimePickerState(
            initialHour = initialHour,
            initialMinute = 0,
        )

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour) }) {
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

internal fun formatHour(hour: Int): String {
    val tz = TimeZone.currentSystemDefault()
    val today = Clock.System.todayIn(tz)
    return LocalDateTime(today, LocalTime(hour, 0)).toInstant(tz).asTime
}
