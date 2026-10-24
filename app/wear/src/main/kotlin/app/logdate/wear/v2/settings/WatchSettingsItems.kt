package app.logdate.wear.v2.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TitleCard
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import app.logdate.wear.R
import app.logdate.wear.v2.haptics.HapticsPreference
import app.logdate.wear.v2.today.HandoffState

object WatchSettingsTags {
    const val STATUS = "settings_status"
    const val HAPTICS_ALL = "settings_haptics_all"
    const val HAPTICS_ESSENTIAL = "settings_haptics_essential"
    const val PLACES = "settings_places"
    const val REMINDERS = "settings_reminders"
    const val MORE_ON_PHONE = "settings_more_on_phone"
}

/** What the settings section lets the person do. */
data class WatchSettingsActions(
    val onHaptics: (HapticsPreference) -> Unit = {},
    val onPlaces: (Boolean) -> Unit = {},
    val onReminders: (Boolean) -> Unit = {},
    val onRetrySync: () -> Unit = {},
    val onOpenPhoneSettings: () -> Unit = {},
)

/** The settings at the end of the moment list: the status card when something is wrong, then the settings. */
fun TransformingLazyColumnScope.watchSettingsItems(
    state: WatchSettingsUiState,
    actions: WatchSettingsActions,
    spec: TransformationSpec,
) {
    statusItem(state, actions, spec)
    item {
        ListHeader(modifier = Modifier.transformedHeight(this, spec), transformation = SurfaceTransformation(spec)) {
            Text(stringResource(R.string.wear_settings_title))
        }
    }
    item {
        HapticsChoice(
            HapticsPreference.ALL,
            state.haptics,
            actions.onHaptics,
            Modifier.transformedHeight(this, spec),
            SurfaceTransformation(spec),
        )
    }
    item {
        HapticsChoice(
            HapticsPreference.ESSENTIAL,
            state.haptics,
            actions.onHaptics,
            Modifier.transformedHeight(this, spec),
            SurfaceTransformation(spec),
        )
    }
    item { PlacesSwitch(state.places, actions.onPlaces, Modifier.transformedHeight(this, spec), SurfaceTransformation(spec)) }
    item { RemindersSwitch(state.reminders, actions.onReminders, Modifier.transformedHeight(this, spec), SurfaceTransformation(spec)) }
    if (state.phoneSettingsNodeId != null) {
        item {
            MoreOnPhoneButton(
                state.handoff,
                actions.onOpenPhoneSettings,
                Modifier.transformedHeight(this, spec),
                SurfaceTransformation(spec),
            )
        }
    }
}

private fun TransformingLazyColumnScope.statusItem(
    state: WatchSettingsUiState,
    actions: WatchSettingsActions,
    spec: TransformationSpec,
) {
    when (val status = state.status) {
        WatchStatus.Healthy -> {
            Unit
        }

        is WatchStatus.WaitingForPhone -> {
            item { WaitingForPhone(status.pending) }
        }

        WatchStatus.NeedsDeviceLink -> {
            item { NeedsDeviceLink() }
        }

        is WatchStatus.SendFailing -> {
            item {
                RetryCard(
                    R.string.wear_settings_send_failing,
                    state.retrying,
                    actions.onRetrySync,
                    Modifier.transformedHeight(this, spec),
                    SurfaceTransformation(spec),
                )
            }
        }

        is WatchStatus.BackupFailing -> {
            item {
                RetryCard(
                    R.string.wear_settings_backup_failing,
                    state.retrying,
                    actions.onRetrySync,
                    Modifier.transformedHeight(this, spec),
                    SurfaceTransformation(spec),
                )
            }
        }
    }
}

@Composable
private fun WaitingForPhone(pending: Int) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag(WatchSettingsTags.STATUS),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = pluralStringResource(R.plurals.wear_settings_waiting_for_phone, pending, pending),
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.wear_settings_waiting_for_phone_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun NeedsDeviceLink() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag(WatchSettingsTags.STATUS),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.wear_settings_needs_link),
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.wear_settings_needs_link_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun RetryCard(
    @StringRes title: Int,
    retrying: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier,
    transformation: SurfaceTransformation,
) {
    TitleCard(
        onClick = onRetry,
        title = { Text(stringResource(title)) },
        modifier = modifier.fillMaxWidth().testTag(WatchSettingsTags.STATUS),
        enabled = !retrying,
        transformation = transformation,
    ) {
        Text(stringResource(if (retrying) R.string.wear_settings_send_retrying else R.string.wear_settings_send_retry))
    }
}

@Composable
private fun HapticsChoice(
    option: HapticsPreference,
    current: HapticsPreference,
    onSelect: (HapticsPreference) -> Unit,
    modifier: Modifier,
    transformation: SurfaceTransformation,
) {
    val essential = option == HapticsPreference.ESSENTIAL
    RadioButton(
        selected = current == option,
        onSelect = { onSelect(option) },
        modifier =
            modifier
                .fillMaxWidth()
                .testTag(if (essential) WatchSettingsTags.HAPTICS_ESSENTIAL else WatchSettingsTags.HAPTICS_ALL),
        transformation = transformation,
        secondaryLabel = {
            Text(
                text =
                    stringResource(
                        if (essential) R.string.wear_settings_haptics_essential_body else R.string.wear_settings_haptics_all_body,
                    ),
                maxLines = 3,
            )
        },
        label = { Text(stringResource(if (essential) R.string.wear_settings_haptics_essential else R.string.wear_settings_haptics_all)) },
    )
}

@Composable
private fun PlacesSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier,
    transformation: SurfaceTransformation,
) {
    SwitchButton(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier.fillMaxWidth().testTag(WatchSettingsTags.PLACES),
        transformation = transformation,
        secondaryLabel = { Text(stringResource(R.string.wear_settings_places_body), maxLines = 4) },
        label = { Text(stringResource(R.string.wear_settings_places)) },
    )
}

@Composable
private fun RemindersSwitch(
    setting: RemindersSetting,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier,
    transformation: SurfaceTransformation,
) {
    SwitchButton(
        checked = setting.enabled,
        onCheckedChange = onCheckedChange,
        modifier = modifier.fillMaxWidth().testTag(WatchSettingsTags.REMINDERS),
        transformation = transformation,
        secondaryLabel = {
            Text(
                stringResource(
                    when {
                        !setting.enabled -> R.string.wear_settings_reminders_off
                        setting.afterWorkouts -> R.string.wear_settings_reminders_workouts
                        else -> R.string.wear_settings_reminders_evening
                    },
                ),
                maxLines = 4,
            )
        },
        label = { Text(stringResource(R.string.wear_settings_reminders)) },
    )
}

@Composable
private fun MoreOnPhoneButton(
    handoff: HandoffState,
    onClick: () -> Unit,
    modifier: Modifier,
    transformation: SurfaceTransformation,
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().testTag(WatchSettingsTags.MORE_ON_PHONE),
        enabled = handoff != HandoffState.SENDING,
        transformation = transformation,
        icon = { Icon(imageVector = Icons.Filled.PhoneAndroid, contentDescription = null) },
        secondaryLabel =
            if (handoff == HandoffState.FAILED) {
                { Text(stringResource(R.string.wear_settings_more_on_phone_failed)) }
            } else {
                null
            },
        label = { Text(stringResource(R.string.wear_settings_more_on_phone)) },
    )
}
