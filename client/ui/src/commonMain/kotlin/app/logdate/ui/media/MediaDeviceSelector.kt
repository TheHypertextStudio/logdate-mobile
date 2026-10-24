@file:OptIn(ExperimentalMaterial3Api::class)
@file:Suppress("ktlint:standard:function-naming")

package app.logdate.ui.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BluetoothAudio
import androidx.compose.material.icons.rounded.CameraFront
import androidx.compose.material.icons.rounded.CameraRear
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.SettingsInputComponent
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ChipColors
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.logdate.client.media.device.MediaDeviceCategory
import app.logdate.client.media.device.MediaDeviceKind
import app.logdate.client.media.device.MediaDeviceSelectionUiState
import app.logdate.client.media.device.MediaDeviceUiState
import app.logdate.client.text.asString
import app.logdate.ui.platform.PlatformSheet
import logdate.client.ui.generated.resources.Res
import logdate.client.ui.generated.resources.common_done
import logdate.client.ui.generated.resources.media_device_audio_output
import logdate.client.ui.generated.resources.media_device_camera
import logdate.client.ui.generated.resources.media_device_microphone
import logdate.client.ui.generated.resources.media_device_selected
import logdate.client.ui.generated.resources.media_device_status_available
import logdate.client.ui.generated.resources.media_device_status_detected
import logdate.client.ui.generated.resources.media_device_status_external
import logdate.client.ui.generated.resources.media_device_status_in_use
import logdate.client.ui.generated.resources.media_device_status_unavailable
import logdate.client.ui.generated.resources.media_device_switch_hint
import org.jetbrains.compose.resources.stringResource

/**
 * Opens a media-device selector from the compact route chip.
 *
 * The platform route action intentionally defaults from [selection.kind]. For system-controlled
 * microphone and output routes, the settings/output-switcher handoff is part of the selector's
 * user contract: the user opened this control to change a route, so the common production path
 * should always provide the best platform next step without requiring each caller to remember the
 * same boilerplate. Pass `null` or a custom action only for previews, tests, screenshots, or a
 * surface with an explicit UX reason to suppress the platform handoff.
 *
 * Prefer this default-parameter form over making every caller pass
 * `rememberMediaRouteSettingsAction(selection.kind)`. The selector owns the route-state context,
 * and colocating the fallback here prevents production surfaces from accidentally showing
 * system-controlled explanatory copy with no useful next action. Do not move this default into
 * individual call sites unless a surface needs a deliberately different platform handoff.
 */
@Composable
fun MediaDeviceSelector(
    selection: MediaDeviceSelectionUiState,
    onDeviceSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = defaultSelectorLabel(selection.kind),
    enabled: Boolean = true,
    colors: ChipColors = AssistChipDefaults.assistChipColors(),
    systemSettingsAction: MediaRouteSettingsAction? = rememberMediaRouteSettingsAction(selection.kind),
    visibilityState: MutableState<Boolean> = remember { mutableStateOf(false) },
) {
    var isSheetVisible by visibilityState
    val selected = selection.selectedDevice
    val chipLabel = selected?.label?.asString() ?: label

    AssistChip(
        onClick = { isSheetVisible = true },
        enabled = enabled,
        colors = colors,
        label = {
            Text(
                text = chipLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingIcon = {
            Icon(
                imageVector = selected.iconFor(selection.kind),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        },
        modifier = modifier.testTag(MediaDeviceSelectorTags.chip(label)),
    )

    if (isSheetVisible) {
        PlatformSheet(
            onDismissRequest = { isSheetVisible = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Box(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                MediaDeviceSelectorSheet(
                    selection = selection,
                    title = label,
                    onDeviceSelected = { deviceId ->
                        onDeviceSelected(deviceId)
                        isSheetVisible = false
                    },
                    systemSettingsAction = systemSettingsAction,
                    onDismiss = { isSheetVisible = false },
                )
            }
        }
    }
}

/**
 * Sheet variant of [MediaDeviceSelector].
 *
 * Keep [systemSettingsAction] defaulted to [rememberMediaRouteSettingsAction] so standalone sheet
 * callers preserve the same system-controlled route UX as the compact selector. Requiring each
 * sheet caller to pass the same value makes the route-settings escape hatch easier to omit and
 * creates avoidable drift between the compact selector and direct sheet entry points.
 */
@Composable
fun MediaDeviceSelectorSheet(
    selection: MediaDeviceSelectionUiState,
    title: String = defaultSelectorLabel(selection.kind),
    onDeviceSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    systemSettingsAction: MediaRouteSettingsAction? = rememberMediaRouteSettingsAction(selection.kind),
    onDismiss: () -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .selectableGroup()
                .testTag(MediaDeviceSelectorTags.sheet(title))
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        selection.routeControlMessage?.let { message ->
            Text(
                text = message.asString(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        selection.devices.forEach { device ->
            val selected = device.id == selection.selectedDeviceId
            val rowEnabled = selection.isSelectionControllable && device.isAvailable
            val supportingText = device.supportingText(selection)
            ListItem(
                headlineContent = { Text(device.label.asString()) },
                supportingContent = {
                    Text(
                        text = supportingText,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                leadingContent = {
                    Icon(
                        imageVector = device.iconFor(selection.kind),
                        contentDescription = null,
                    )
                },
                trailingContent = {
                    if (selected) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = stringResource(Res.string.media_device_selected),
                        )
                    }
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag(MediaDeviceSelectorTags.deviceRow(title, device.id))
                        .semantics {
                            this.selected = selected
                            this.stateDescription = supportingText
                        }.selectable(
                            selected = selected,
                            enabled = rowEnabled,
                            role = Role.RadioButton,
                            onClick = { onDeviceSelected(device.id) },
                        ),
            )
        }

        val hasSelectableDevice =
            selection.isSelectionControllable &&
                selection.devices.any { it.isAvailable && it.id != selection.selectedDeviceId }
        if (hasSelectableDevice) {
            Text(
                text = stringResource(Res.string.media_device_switch_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        if (!selection.isSelectionControllable && systemSettingsAction != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = systemSettingsAction.onClick,
                    modifier = Modifier.testTag(MediaDeviceSelectorTags.systemSettingsButton(title)),
                ) {
                    Text(systemSettingsAction.label)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.common_done))
            }
        }
    }
}

private fun MediaDeviceUiState?.iconFor(kind: MediaDeviceKind): ImageVector =
    when (this?.category) {
        MediaDeviceCategory.FRONT_CAMERA -> {
            Icons.Rounded.CameraFront
        }

        MediaDeviceCategory.BACK_CAMERA -> {
            Icons.Rounded.CameraRear
        }

        MediaDeviceCategory.USB,
        MediaDeviceCategory.EXTERNAL,
        -> {
            Icons.Rounded.SettingsInputComponent
        }

        MediaDeviceCategory.BLUETOOTH -> {
            Icons.Rounded.BluetoothAudio
        }

        MediaDeviceCategory.WIRED -> {
            Icons.Rounded.Headphones
        }

        MediaDeviceCategory.BUILT_IN -> {
            when (kind) {
                MediaDeviceKind.CAMERA -> Icons.Rounded.CameraRear
                MediaDeviceKind.AUDIO_INPUT -> Icons.Rounded.Mic
                MediaDeviceKind.AUDIO_OUTPUT -> Icons.Rounded.Speaker
            }
        }

        MediaDeviceCategory.SYSTEM_DEFAULT,
        null,
        -> {
            when (kind) {
                MediaDeviceKind.CAMERA -> Icons.Rounded.CameraRear
                MediaDeviceKind.AUDIO_INPUT -> Icons.Rounded.Mic
                MediaDeviceKind.AUDIO_OUTPUT -> Icons.Rounded.Speaker
            }
        }
    }

@Composable
private fun MediaDeviceUiState.supportingText(selection: MediaDeviceSelectionUiState): String =
    when {
        !isAvailable -> stringResource(Res.string.media_device_status_unavailable)
        id == selection.selectedDeviceId -> stringResource(Res.string.media_device_status_in_use)
        !selection.isSelectionControllable -> stringResource(Res.string.media_device_status_detected)
        isExternal -> stringResource(Res.string.media_device_status_external)
        else -> stringResource(Res.string.media_device_status_available)
    }

@Composable
private fun defaultSelectorLabel(kind: MediaDeviceKind): String =
    when (kind) {
        MediaDeviceKind.CAMERA -> stringResource(Res.string.media_device_camera)
        MediaDeviceKind.AUDIO_INPUT -> stringResource(Res.string.media_device_microphone)
        MediaDeviceKind.AUDIO_OUTPUT -> stringResource(Res.string.media_device_audio_output)
    }

data class MediaRouteSettingsAction(
    val label: String,
    val onClick: () -> Unit,
)

object MediaDeviceSelectorTags {
    fun chip(label: String): String = "media_device_selector_chip_${label.toStableTagSuffix()}"

    fun sheet(label: String): String = "media_device_selector_sheet_${label.toStableTagSuffix()}"

    fun deviceRow(
        label: String,
        deviceId: String,
    ): String = "media_device_selector_row_${label.toStableTagSuffix()}_${deviceId.toStableTagSuffix()}"

    fun systemSettingsButton(label: String): String = "media_device_selector_settings_${label.toStableTagSuffix()}"

    private fun String.toStableTagSuffix(): String =
        lowercase()
            .map { char -> if (char.isLetterOrDigit()) char else '_' }
            .joinToString("")
            .replace(Regex("_+"), "_")
            .trim('_')
            .ifBlank { "route" }
}

@Composable
expect fun rememberMediaRouteSettingsAction(kind: MediaDeviceKind): MediaRouteSettingsAction?
