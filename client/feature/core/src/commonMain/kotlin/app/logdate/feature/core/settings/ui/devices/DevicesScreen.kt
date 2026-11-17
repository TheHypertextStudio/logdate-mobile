@file:OptIn(ExperimentalMaterial3Api::class)
@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui.devices

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import app.logdate.client.text.asString
import app.logdate.feature.core.settings.ui.watch.WatchDeviceRow
import app.logdate.feature.core.settings.ui.watch.WatchSettingsViewModel
import app.logdate.feature.core.settings.ui.watch.WatchStatus
import app.logdate.ui.common.DropdownMenu
import app.logdate.ui.common.MaterialContainer
import app.logdate.ui.common.SettingsRow
import app.logdate.ui.common.SettingsScaffold
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.device_state_load_failed
import logdate.client.feature.core.generated.resources.device_state_refreshing
import logdate.client.feature.core.generated.resources.device_state_retry
import logdate.client.feature.core.generated.resources.devices
import logdate.client.feature.core.generated.resources.devices_platform_wear_os
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
    viewModel: DevicesViewModel = koinViewModel(),
    onNavigateToWatch: (() -> Unit)? = null,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val flags = koinInject<FeatureFlagStore>()
    val watchEnabled by remember(flags) { flags.observe(FeatureFlag.WATCH_EXPERIENCE_V2) }.collectAsStateWithLifecycle(false)
    val companionStatus: WatchStatus? =
        if (watchEnabled && onNavigateToWatch != null) {
            val watchModel = koinViewModel<WatchSettingsViewModel>()
            val observed by watchModel.status.collectAsStateWithLifecycle()
            observed
        } else {
            null
        }

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
                { CompanionDeviceInDevices(companionStatus, onNavigateToWatch) }
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
        onRenameConfirm = {
            viewModel.renameDevice(newDeviceName)
        },
        onRemoveConfirm = {
            selectedDevice?.let { viewModel.removeDevice(it.id) }
        },
        onResetDeviceId = {
            viewModel.resetDeviceId()
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
        onManagePairedWatch = onNavigateToWatch?.takeIf { watchEnabled },
        pairedWatchStatus = companionStatus,
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
    onRenameConfirm: () -> Unit = {},
    onRemoveConfirm: () -> Unit = {},
    onResetDeviceId: () -> Unit = {},
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
    onManagePairedWatch: (() -> Unit)? = null,
    pairedWatchStatus: WatchStatus? = null,
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

    var confirmingLinkedRemoval by remember { mutableStateOf<LinkedDeviceUiState?>(null) }
    confirmingLinkedRemoval?.let { device ->
        RemoveDeviceDialog(
            deviceName = device.name.asString(),
            onConfirm = {
                confirmingLinkedRemoval = null
                onRemoveLinkedDevice(device.id)
            },
            onDismiss = { confirmingLinkedRemoval = null },
            erasesData = device.erasesData,
        )
    }

    // A dialog that is open for an operation reports its progress and failure itself.
    val rowOperations =
        uiState.devices
            .mapNotNull { device ->
                uiState.operations[device.id]
                    ?.takeUnless {
                        it.isReportedByDialog(device, selectedDevice, showRenameDialog, showDeleteDialog, showResetDialog)
                    }?.let { device.id to it }
            }.toMap()

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
                    // The page title already names this list, so it has no section heading of its own.
                    MaterialContainer(
                        modifier =
                            Modifier
                                .padding(horizontal = Spacing.lg)
                                .then(if (uiState.linked.isShown()) Modifier.testTag(LinkedDevicesTags.SECTION) else Modifier),
                    ) {
                        uiState.devices.forEach { device ->
                            val operation = uiState.operations[device.id]
                            DeviceItem(
                                device = device,
                                running = rowOperations[device.id] is DeviceOperationState.Running,
                                optionsEnabled = operation !is DeviceOperationState.Running,
                                onRenameClick = { onRenameClick(device) },
                            )
                        }
                        val pairedRowShown =
                            (uiState.linked as? LinkedDevicesUiState.Listed)?.devices?.any {
                                it.pairedWatch &&
                                    it.pairedWatchNodeId != null &&
                                    it.pairedWatchNodeId == pairedWatchStatus?.nodeId
                            } ==
                                true
                        if (!pairedRowShown || onManagePairedWatch == null) watchDevice?.invoke()
                        LinkedDeviceRows(
                            state = uiState.linked,
                            onRemove = { confirmingLinkedRemoval = it },
                            onRetry = onRetryLinkedDevices,
                            onManagePairedWatch = onManagePairedWatch,
                            pairedWatchStatus = pairedWatchStatus,
                        )
                    }
                }
                val failures = rowOperations.values.filterIsInstance<DeviceOperationState.Failed>()
                val linkedNotice = (uiState.linked as? LinkedDevicesUiState.Listed)?.notice
                val linkedDevices = (uiState.linked as? LinkedDevicesUiState.Listed)?.devices.orEmpty()
                if (failures.isNotEmpty() || linkedNotice != null) {
                    item {
                        Column(
                            modifier = Modifier.padding(horizontal = Spacing.lg),
                            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                        ) {
                            failures.forEach { failure ->
                                DeviceOperationFeedback(failure, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                            }
                            linkedNotice?.let { notice ->
                                LinkedDeviceNoticeMessage(
                                    notice = notice,
                                    onRetryRemove = { id -> confirmingLinkedRemoval = linkedDevices.firstOrNull { it.id == id } },
                                    onDismiss = onDismissLinkedDeviceNotice,
                                )
                            }
                        }
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
    status: WatchStatus?,
    onClick: () -> Unit,
) {
    val name =
        when (val current = status) {
            is WatchStatus.Ready -> current.watchName
            is WatchStatus.NeedsAttention -> current.watchName
            is WatchStatus.NotInstalled -> current.watchName
            is WatchStatus.Unknown -> current.watchName ?: return
            else -> return
        }
    WatchDeviceRow(
        status = status,
        onClick = onClick,
        deviceName = name,
        overline = stringResource(Res.string.devices_platform_wear_os),
        icon = { Icon(Icons.Outlined.Watch, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
    )
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

private fun DeviceOperationState.isReportedByDialog(
    device: DeviceInfoUiState,
    selectedDevice: DeviceInfoUiState?,
    showRenameDialog: Boolean,
    showDeleteDialog: Boolean,
    showResetDialog: Boolean,
): Boolean =
    when (action) {
        DeviceAction.Rename -> showRenameDialog && device.id == selectedDevice?.id
        DeviceAction.Remove -> showDeleteDialog && device.id == selectedDevice?.id
        DeviceAction.ResetIdentity -> showResetDialog && device.isCurrentDevice
    }

/**
 * One account device as a row of the Devices list: its platform, its name, and either "This device"
 * or when it was last active. The list's container rounds the first and last rows, so the row has no
 * shape of its own. An operation in progress replaces the options button; a failure is reported
 * below the list.
 */
@Composable
private fun DeviceItem(
    device: DeviceInfoUiState,
    running: Boolean,
    optionsEnabled: Boolean,
    onRenameClick: () -> Unit,
) {
    SettingsRow(
        overlineContent = { Text(stringResource(device.platformName)) },
        headlineContent = { Text(device.name.asString()) },
        supportingContent = {
            Text(
                text =
                    if (device.isCurrentDevice) {
                        stringResource(Res.string.this_device)
                    } else {
                        stringResource(Res.string.last_active_label, device.lastActiveFormatted.asString())
                    },
                modifier = Modifier.testTag("device-supplemental-row"),
            )
        },
        leadingContent = { Icon(Icons.Outlined.Devices, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent =
            when {
                running -> {
                    { DeviceActionProgress() }
                }

                device.isCurrentDevice -> {
                    { DeviceOptions(enabled = optionsEnabled, onRenameClick = onRenameClick) }
                }

                else -> {
                    null
                }
            },
        modifier = Modifier.testTag("existing-device-card"),
    )
}

@Composable
private fun DeviceOptions(
    enabled: Boolean,
    onRenameClick: () -> Unit,
) {
    DeviceOptionsMenu(enabled = enabled) { close ->
        DropdownMenuItem(
            text = { Text(stringResource(Res.string.sync_device_rename_label)) },
            onClick = {
                close()
                onRenameClick()
            },
        )
    }
}

/** The one trailing action of a device row: an options button that opens [items], each given a way to close the menu. */
@Composable
internal fun DeviceOptionsMenu(
    enabled: Boolean = true,
    items: @Composable (close: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = enabled) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(Res.string.sync_device_options_label))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items { expanded = false }
        }
    }
}

/** Takes the place of a device row's options while an operation on that device runs. */
@Composable
internal fun DeviceActionProgress() {
    Box(Modifier.minimumInteractiveComponentSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
    }
}
