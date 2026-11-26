package app.logdate.wear.presentation.settings

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PhonelinkOff
import androidx.compose.material.icons.filled.Sync
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import app.logdate.client.location.settings.LocationCaptureMode
import app.logdate.wear.R
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun WearSettingsScreen(viewModel: WearSettingsViewModel = koinViewModel()) {
    val settingsState by viewModel.uiState.collectAsState()

    // The location permission can change in system settings while this screen is away.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) viewModel.onResume()
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    WearSettingsContent(
        settingsState = settingsState,
        onSyncNow = { viewModel.syncNow() },
        onBackgroundLocationChanged = { enabled ->
            viewModel.setBackgroundLocationEnabled(enabled)
        },
        onLocationCaptureModeChanged = { mode ->
            viewModel.setLocationCaptureMode(mode)
        },
        onOpenLocationPermissions = { viewModel.openLocationPermissions() },
    )
}

@Composable
internal fun WearSettingsContent(
    settingsState: WearSettingsUiState = WearSettingsUiState(),
    onSyncNow: () -> Unit = {},
    onBackgroundLocationChanged: (Boolean) -> Unit = {},
    onLocationCaptureModeChanged: (LocationCaptureMode) -> Unit = {},
    onOpenLocationPermissions: () -> Unit = {},
) {
    val listState = rememberScalingLazyListState()

    ScreenScaffold(
        timeText = { TimeText() },
        scrollState = listState,
    ) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
        ) {
            item(key = "header") {
                Text(
                    text = stringResource(R.string.wear_settings_title),
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp),
                )
            }

            // Phone & Sync section
            item(key = "phone_sync_header") {
                Text(
                    text = stringResource(R.string.wear_settings_phone_sync),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 4.dp),
                )
            }

            item(key = "connection_status") {
                val icon =
                    if (settingsState.isPhoneConnected) {
                        Icons.Default.PhoneAndroid
                    } else {
                        Icons.Default.PhonelinkOff
                    }
                val label =
                    if (settingsState.isPhoneConnected) {
                        val name = settingsState.phoneName
                        if (name != null) {
                            stringResource(R.string.wear_settings_connected_to, name)
                        } else {
                            stringResource(R.string.wear_settings_connected_to, "phone")
                        }
                    } else {
                        stringResource(R.string.wear_settings_phone_not_connected)
                    }
                val color =
                    if (settingsState.isPhoneConnected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }

                Card(
                    onClick = {},
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = color,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = color,
                        )
                    }
                }
            }

            item(key = "last_sync") {
                val lastSyncText =
                    if (settingsState.lastSyncTime != null) {
                        val relativeTime =
                            DateUtils
                                .getRelativeTimeSpanString(
                                    settingsState.lastSyncTime.toEpochMilliseconds(),
                                    System.currentTimeMillis(),
                                    DateUtils.MINUTE_IN_MILLIS,
                                ).toString()
                        stringResource(R.string.wear_settings_last_synced, relativeTime)
                    } else {
                        stringResource(R.string.wear_settings_never_synced)
                    }
                Text(
                    text = lastSyncText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                )
            }

            if (settingsState.pendingCount > 0) {
                item(key = "pending_count") {
                    Text(
                        text =
                            stringResource(
                                R.string.wear_settings_pending_entries,
                                settingsState.pendingCount,
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                    )
                }
            }

            if (settingsState.hasErrors) {
                item(key = "sync_errors") {
                    Text(
                        text = stringResource(R.string.wear_settings_sync_error, 1),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                    )
                }
            }

            item(key = "sync_now") {
                val label =
                    if (settingsState.isSyncingNow) {
                        stringResource(R.string.wear_settings_syncing)
                    } else {
                        stringResource(R.string.wear_settings_sync_now)
                    }
                Button(
                    onClick = onSyncNow,
                    enabled = !settingsState.isSyncingNow,
                    modifier = Modifier.fillMaxWidth(),
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = null,
                        )
                    },
                    label = { Text(label) },
                )
            }

            item(key = "location_header") {
                Text(
                    text = stringResource(R.string.wear_settings_location),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp, bottom = 4.dp),
                )
            }

            item(key = "location_permission") {
                val label =
                    if (settingsState.locationPermissionGranted) {
                        stringResource(R.string.wear_settings_location_permission_granted)
                    } else {
                        stringResource(R.string.wear_settings_location_permission_required)
                    }
                Button(
                    onClick = onOpenLocationPermissions,
                    modifier = Modifier.fillMaxWidth(),
                    icon = {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = null,
                            tint =
                                if (settingsState.locationPermissionGranted) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    label = { Text(label) },
                )
            }

            item(key = "location_autotag") {
                Text(
                    text =
                        if (settingsState.autoTagJournalEntries) {
                            stringResource(R.string.wear_settings_location_autotag_on)
                        } else {
                            stringResource(R.string.wear_settings_location_autotag_off)
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                )
            }

            item(key = "background_location") {
                SwitchButton(
                    checked = settingsState.backgroundLocationEnabled,
                    onCheckedChange = onBackgroundLocationChanged,
                    label = {
                        Text(stringResource(R.string.wear_settings_background_location))
                    },
                    secondaryLabel = {
                        Text(stringResource(R.string.wear_settings_background_location_detail))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item(key = "active_location_mode") {
                val activeMode = settingsState.locationCaptureMode == LocationCaptureMode.ACTIVE
                SwitchButton(
                    checked = activeMode,
                    onCheckedChange = { enabled ->
                        onLocationCaptureModeChanged(
                            if (enabled) {
                                LocationCaptureMode.ACTIVE
                            } else {
                                LocationCaptureMode.PASSIVE
                            },
                        )
                    },
                    label = {
                        Text(stringResource(R.string.wear_settings_activity_aware_location))
                    },
                    secondaryLabel = {
                        Text(stringResource(R.string.wear_settings_activity_aware_location_detail))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            settingsState.appVersion?.takeIf { it.isNotBlank() }?.let { version ->
                item(key = "app_version") {
                    Text(
                        text = stringResource(R.string.wear_settings_version, version),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp, bottom = 8.dp),
                    )
                }
            }
        }
    }
}
