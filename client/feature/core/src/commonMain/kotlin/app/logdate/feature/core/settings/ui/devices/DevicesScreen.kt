@file:OptIn(ExperimentalMaterial3Api::class)
@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui.devices

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import app.logdate.client.text.asString
import app.logdate.client.text.toUiText
import app.logdate.feature.core.settings.ui.watch.WatchDeviceCard
import app.logdate.feature.core.settings.ui.watch.WatchSettingsViewModel
import app.logdate.feature.core.settings.ui.watch.WatchStatus
import app.logdate.ui.common.DropdownMenu
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.logdate.ui.common.SettingsScaffold
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.device_state_load_failed
import logdate.client.feature.core.generated.resources.device_state_refreshing
import logdate.client.feature.core.generated.resources.device_state_retry
import logdate.client.feature.core.generated.resources.devices
import logdate.client.feature.core.generated.resources.devices_connected
import logdate.client.feature.core.generated.resources.devices_platform_wear_os
import logdate.client.feature.core.generated.resources.devices_wear_os
import logdate.client.feature.core.generated.resources.last_active_label
import logdate.client.feature.core.generated.resources.loading_devices
import logdate.client.feature.core.generated.resources.sync_device_options_label
import logdate.client.feature.core.generated.resources.sync_device_rename_label
import logdate.client.feature.core.generated.resources.this_device
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * Screen that displays device information and allows management of devices.
 */
@Composable
fun DevicesScreen(
    onBackClick: () -> Unit,
    viewModel: DevicesViewModel = koinInject(),
    onNavigateToWatch: (() -> Unit)? = null,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val flags = koinInject<FeatureFlagStore>()
    val watchEnabled by remember(flags) { flags.observe(FeatureFlag.WATCH_EXPERIENCE_V2) }.collectAsStateWithLifecycle(false)

    LifecycleStartEffect(viewModel) {
        viewModel.loadDevices()
        onStopOrDispose { viewModel.stopObserving() }
    }

    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var selectedDevice by remember { mutableStateOf<DeviceInfoUiState?>(null) }
    var newDeviceName by remember { mutableStateOf("") }
    val deviceNames = uiState.devices.associate { it.id to it.name.asString() }

    DevicesScreenContent(
        onBackClick = onBackClick,
        uiState = uiState,
        watchDevice =
            if (watchEnabled && onNavigateToWatch != null) {
                { CompanionDeviceInDevices(uiState, onNavigateToWatch) }
            } else {
                null
            },
        showRenameDialog = showRenameDialog,
        showDeleteDialog = showDeleteDialog,
        showResetDialog = showResetDialog,
        selectedDevice = selectedDevice,
        newDeviceName = newDeviceName,
        onNewDeviceNameChange = { newDeviceName = it },
        onRenameClick = { device ->
            viewModel.clearOperation(device.id)
            selectedDevice = device
            newDeviceName = deviceNames[device.id].orEmpty()
            showRenameDialog = true
        },
        onRemoveClick = { device ->
            viewModel.clearOperation(device.id)
            selectedDevice = device
            showDeleteDialog = true
        },
        onRenameConfirm = {
            viewModel.renameDevice(newDeviceName)
        },
        onRemoveConfirm = {
            selectedDevice?.let { viewModel.removeDevice(it.id) }
        },
        onResetDeviceId = {
            viewModel.resetDeviceId()
        },
        onShowResetDialog = {
            uiState.devices.firstOrNull { it.isCurrentDevice }?.let { viewModel.clearOperation(it.id) }
            showResetDialog = true
        },
        onDismissRenameDialog = {
            showRenameDialog = false
            selectedDevice?.let { viewModel.clearOperation(it.id) }
        },
        onDismissDeleteDialog = {
            showDeleteDialog = false
            selectedDevice?.let { viewModel.clearOperation(it.id) }
        },
        onDismissResetDialog = {
            showResetDialog = false
            uiState.devices.firstOrNull { it.isCurrentDevice }?.let { viewModel.clearOperation(it.id) }
        },
        onRefresh = viewModel::loadDevices,
        onRemoveLinkedDevice = viewModel::removeLinkedDevice,
        onRetryLinkedDevices = viewModel::loadLinkedDevices,
        onDismissLinkedDeviceNotice = viewModel::dismissLinkedDeviceNotice,
    )
}

@Composable
fun DevicesScreenContent(
    onBackClick: () -> Unit,
    uiState: DevicesUiState,
    showRenameDialog: Boolean = false,
    showDeleteDialog: Boolean = false,
    showResetDialog: Boolean = false,
    selectedDevice: DeviceInfoUiState? = null,
    newDeviceName: String = selectedDevice?.name?.asString().orEmpty(),
    onNewDeviceNameChange: (String) -> Unit = {},
    onRenameClick: (DeviceInfoUiState) -> Unit = {},
    onRemoveClick: (DeviceInfoUiState) -> Unit = {},
    onRenameConfirm: () -> Unit = {},
    onRemoveConfirm: () -> Unit = {},
    onResetDeviceId: () -> Unit = {},
    onShowResetDialog: () -> Unit = {},
    onDismissRenameDialog: () -> Unit = {},
    onDismissDeleteDialog: () -> Unit = {},
    onDismissResetDialog: () -> Unit = {},
    onRefresh: () -> Unit = {},
    connectionAction: @Composable () -> Unit = { DeviceApprovalAction() },
    modifier: Modifier = Modifier,
    watchDevice: (@Composable () -> Unit)? = null,
    onRemoveLinkedDevice: (String) -> Unit = {},
    onRetryLinkedDevices: () -> Unit = {},
    onDismissLinkedDeviceNotice: () -> Unit = {},
) {
    val selectedOperation = selectedDevice?.let { uiState.operations[it.id] }
    val resetOperation = uiState.devices.firstOrNull { it.isCurrentDevice }?.let { uiState.operations[it.id] }
    LaunchedEffect(selectedOperation, resetOperation) {
        if (selectedOperation is DeviceOperationState.Succeeded) {
            when (selectedOperation.action) {
                DeviceAction.Rename -> if (showRenameDialog) onDismissRenameDialog()
                DeviceAction.Remove -> if (showDeleteDialog) onDismissDeleteDialog()
                DeviceAction.ResetIdentity -> Unit
            }
        }
        if (resetOperation is DeviceOperationState.Succeeded && resetOperation.action == DeviceAction.ResetIdentity && showResetDialog) {
            onDismissResetDialog()
        }
    }
    if (showRenameDialog && selectedDevice != null) {
        RenameDeviceDialog(
            currentName = newDeviceName,
            operation = selectedOperation?.takeIf { it.action == DeviceAction.Rename },
            onNameChange = onNewDeviceNameChange,
            onConfirm = onRenameConfirm,
            onDismiss = onDismissRenameDialog,
        )
    }

    if (showDeleteDialog && selectedDevice != null) {
        RemoveDeviceDialog(
            deviceName = selectedDevice.name.asString(),
            operation = selectedOperation?.takeIf { it.action == DeviceAction.Remove },
            onConfirm = onRemoveConfirm,
            onDismiss = onDismissDeleteDialog,
        )
    }

    if (showResetDialog) {
        ResetDeviceIdDialog(
            operation = resetOperation?.takeIf { it.action == DeviceAction.ResetIdentity },
            onConfirm = onResetDeviceId,
            onDismiss = onDismissResetDialog,
        )
    }

    Box(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        SettingsScaffold(
            title = stringResource(Res.string.devices),
            onBack = onBackClick,
            modifier = Modifier.widthIn(max = 680.dp).fillMaxSize(),
        ) {
            if (uiState.availability == DeviceListAvailability.Loading) {
                item { LoadingState() }
            } else if (uiState.availability == DeviceListAvailability.Unavailable) {
                item { DeviceLoadFailure(onRefresh) }
            } else {
                if (uiState.refresh == DeviceRefreshState.Running) {
                    item {
                        Row(
                            modifier = Modifier.padding(horizontal = Spacing.lg),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(stringResource(Res.string.device_state_refreshing))
                        }
                    }
                } else if (uiState.refresh == DeviceRefreshState.Failed) {
                    item { DeviceLoadFailure(onRefresh) }
                }
                item {
                    Text(
                        stringResource(Res.string.devices_connected),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = Spacing.lg),
                    )
                }
                items(uiState.devices, key = { it.id }) { device ->
                    val operation = uiState.operations[device.id]
                    val dialogOwnsFeedback =
                        (
                            device.id == selectedDevice?.id &&
                                (
                                    (showRenameDialog && operation?.action == DeviceAction.Rename) ||
                                        (showDeleteDialog && operation?.action == DeviceAction.Remove)
                                )
                        ) ||
                            (device.isCurrentDevice && showResetDialog && operation?.action == DeviceAction.ResetIdentity)
                    DeviceItem(
                        device = device,
                        operation = operation,
                        showOperationFeedback = !dialogOwnsFeedback,
                        onRenameClick = { onRenameClick(device) },
                        modifier = Modifier.padding(horizontal = Spacing.lg),
                    )
                }
                watchDevice?.let { card -> item { card() } }
                val hidePairedWatch = watchDevice != null
                if (uiState.linked.isShown(hidePairedWatch)) {
                    item {
                        LinkedDevicesSection(
                            state = uiState.linked,
                            hidePairedWatch = hidePairedWatch,
                            onRemove = onRemoveLinkedDevice,
                            onRetry = onRetryLinkedDevices,
                            onDismissNotice = onDismissLinkedDeviceNotice,
                            modifier = Modifier.padding(horizontal = Spacing.lg),
                        )
                    }
                }
                item {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg)) {
                        connectionAction()
                    }
                }
            }
        }
    }
}

@Composable
private fun CompanionDeviceInDevices(
    uiState: DevicesUiState,
    onClick: () -> Unit,
    model: WatchSettingsViewModel = koinViewModel(),
) {
    val status by model.status.collectAsStateWithLifecycle()
    val name =
        when (val current = status) {
            is WatchStatus.Ready -> current.watchName
            is WatchStatus.NeedsAttention -> current.watchName
            is WatchStatus.NotInstalled -> current.watchName
            else -> return
        }
    if (uiState.devices.any { it.platformName == Res.string.devices_platform_wear_os && it.name == name.toUiText() }) return
    Column(Modifier.padding(horizontal = Spacing.lg)) {
        Text(stringResource(Res.string.devices_wear_os), style = MaterialTheme.typography.labelMedium)
        WatchDeviceCard(status, onClick)
    }
}

@Composable
private fun DeviceLoadFailure(onRetry: () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(stringResource(Res.string.device_state_load_failed), color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onRetry) { Text(stringResource(Res.string.device_state_retry)) }
    }
}

@Composable
private fun LoadingState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(stringResource(Res.string.loading_devices))
    }
}

@Composable
private fun DeviceItem(
    device: DeviceInfoUiState,
    onRenameClick: () -> Unit,
    operation: DeviceOperationState? = null,
    showOperationFeedback: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var showOptions by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxWidth().testTag("existing-device-card"),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(Spacing.lg)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(48.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Devices, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(device.name.asString(), style = MaterialTheme.typography.titleMedium)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(device.platformName), style = MaterialTheme.typography.bodyMedium)
                        if (device.isCurrentDevice) {
                            Text(
                                text = stringResource(Res.string.this_device),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier =
                                    Modifier
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(50))
                                        .padding(horizontal = 7.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                if (device.isCurrentDevice) {
                    Box {
                        IconButton(onClick = { showOptions = true }, enabled = operation !is DeviceOperationState.Running) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(Res.string.sync_device_options_label))
                        }
                        DropdownMenu(expanded = showOptions, onDismissRequest = { showOptions = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.sync_device_rename_label)) },
                                onClick = {
                                    showOptions = false
                                    onRenameClick()
                                },
                            )
                        }
                    }
                }
            }
            if (showOperationFeedback && operation is DeviceOperationState.Running) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else if (showOperationFeedback && operation is DeviceOperationState.Failed) {
                DeviceOperationFeedback(operation)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(Res.string.last_active_label, device.lastActiveFormatted.asString()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("device-supplemental-row"),
            )
        }
    }
}
