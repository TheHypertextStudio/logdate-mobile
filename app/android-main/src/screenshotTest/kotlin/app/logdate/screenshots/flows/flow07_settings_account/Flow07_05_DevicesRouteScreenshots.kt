// Preserve the screenshot matrix's stable scene and golden identifiers.
@file:Suppress("ktlint:standard:filename", "ktlint:standard:package-name", "ktlint:standard:function-naming")

package app.logdate.screenshots.flows.flow07_settings_account

import androidx.compose.runtime.Composable
import app.logdate.client.text.asString
import app.logdate.client.text.toUiText
import app.logdate.feature.core.settings.ui.devices.DeviceApprovalContent
import app.logdate.feature.core.settings.ui.devices.DeviceApprovalFailure
import app.logdate.feature.core.settings.ui.devices.DeviceApprovalUiState
import app.logdate.feature.core.settings.ui.devices.DeviceInfoUiState
import app.logdate.feature.core.settings.ui.devices.DeviceListAvailability
import app.logdate.feature.core.settings.ui.devices.DevicesScreenContent
import app.logdate.feature.core.settings.ui.devices.DevicesUiState
import app.logdate.screenshots.common.ScreenshotPreviewMatrix
import app.logdate.screenshots.common.ScreenshotTheme
import com.android.tools.screenshot.PreviewTest
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.devices_platform_android
import logdate.client.feature.core.generated.resources.devices_platform_ios
import kotlin.uuid.Uuid

private val devices =
    listOf(
        DeviceInfoUiState(
            id = Uuid.parse("00000000-0000-0000-0000-000000000071"),
            name = "Pixel 9 Pro".toUiText(),
            platformName = Res.string.devices_platform_android,
            lastActiveFormatted = "Today".toUiText(),
            appVersion = "0.1.0",
            isCurrentDevice = true,
        ),
        DeviceInfoUiState(
            id = Uuid.parse("00000000-0000-0000-0000-000000000072"),
            name = "iPad mini".toUiText(),
            platformName = Res.string.devices_platform_ios,
            lastActiveFormatted = "Yesterday".toUiText(),
            appVersion = "0.1.0",
            isCurrentDevice = false,
        ),
    )

@PreviewTest
@ScreenshotPreviewMatrix
@Composable
fun S01_DevicesLoading() {
    ScreenshotTheme {
        DevicesScreenContent(
            onBackClick = {},
            uiState = DevicesUiState(availability = DeviceListAvailability.Loading),
        )
    }
}

@PreviewTest
@ScreenshotPreviewMatrix
@Composable
fun S02_DevicesPopulated() {
    ScreenshotTheme {
        DevicesScreenContent(
            onBackClick = {},
            uiState = DevicesUiState(availability = DeviceListAvailability.Available, devices = devices),
        )
    }
}

@PreviewTest
@ScreenshotPreviewMatrix
@Composable
fun S03_DevicesRenameDialog() {
    ScreenshotTheme {
        DevicesScreenContent(
            onBackClick = {},
            uiState = DevicesUiState(availability = DeviceListAvailability.Available, devices = devices),
            showRenameDialog = true,
            selectedDevice = devices.first(),
            newDeviceName = devices.first().name.asString(),
            onNewDeviceNameChange = {},
        )
    }
}

@PreviewTest
@ScreenshotPreviewMatrix
@Composable
fun S04_DevicesRemoveDialog() {
    ScreenshotTheme {
        DevicesScreenContent(
            onBackClick = {},
            uiState = DevicesUiState(availability = DeviceListAvailability.Available, devices = devices),
            showDeleteDialog = true,
            selectedDevice = devices.last(),
        )
    }
}

@PreviewTest
@ScreenshotPreviewMatrix
@Composable
fun S05_DevicesResetDialog() {
    ScreenshotTheme {
        DevicesScreenContent(
            onBackClick = {},
            uiState = DevicesUiState(availability = DeviceListAvailability.Available, devices = devices),
            showResetDialog = true,
        )
    }
}

@PreviewTest
@ScreenshotPreviewMatrix
@Composable
fun S06_ConnectionConfirm() = ConnectionScene(DeviceApprovalUiState.Confirm("Test device", "Morgan", "123456"))

@PreviewTest
@ScreenshotPreviewMatrix
@Composable
fun S07_ConnectionWorking() = ConnectionScene(DeviceApprovalUiState.Working("Test device", connecting = true))

@PreviewTest
@ScreenshotPreviewMatrix
@Composable
fun S08_ConnectionApproved() = ConnectionScene(DeviceApprovalUiState.Done("Test device", connected = true))

@PreviewTest
@ScreenshotPreviewMatrix
@Composable
fun S09_ConnectionRejected() = ConnectionScene(DeviceApprovalUiState.Done("Test device", connected = false))

@PreviewTest
@ScreenshotPreviewMatrix
@Composable
fun S10_ConnectionExpired() = ConnectionScene(DeviceApprovalUiState.Failed(DeviceApprovalFailure.Expired))

@Composable
private fun ConnectionScene(state: DeviceApprovalUiState) {
    ScreenshotTheme {
        DevicesScreenContent(
            onBackClick = {},
            uiState = DevicesUiState(availability = DeviceListAvailability.Available, devices = devices),
            connectionAction = {
                DeviceApprovalContent(state, onConnectClick = {}, onApprove = {}, onReject = {}, onDismiss = {})
            },
        )
    }
}
