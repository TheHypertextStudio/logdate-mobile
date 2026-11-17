@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui.devices

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.logdate.client.text.toUiText
import app.logdate.ui.theme.LogDateTheme
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.devices_platform_android
import logdate.client.feature.core.generated.resources.devices_platform_wear_os
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class DevicesScreenLayoutTest {
    private val phone =
        DeviceInfoUiState(
            id = Uuid.parse("10000000-0000-0000-0000-000000000001"),
            name = "Pixel 9 Pro".toUiText(),
            platformName = Res.string.devices_platform_android,
            lastActiveFormatted = "Today".toUiText(),
            appVersion = "1.0.0",
            isCurrentDevice = true,
        )
    private val watch =
        LinkedDeviceUiState(
            id = "watch-session",
            name = "Pixel Watch 3".toUiText(),
            platformName = Res.string.devices_platform_wear_os,
            lastSeenFormatted = "Oct 16, 2026".toUiText(),
            pairedWatch = false,
            erasesData = true,
        )

    @Test
    fun `the current device and linked devices are rows of one section above the connect action`() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            setContent {
                LogDateTheme(dynamicColor = false, darkTheme = false) {
                    DevicesScreenContent(
                        onBackClick = {},
                        uiState = loaded(linked = LinkedDevicesUiState.Listed(listOf(watch))),
                        connectionAction = {
                            DeviceApprovalContent(
                                state = DeviceApprovalUiState.Idle,
                                onConnectClick = {},
                                onApprove = {},
                                onReject = {},
                                onDismiss = {},
                            )
                        },
                    )
                }
            }

            val section = onNodeWithTag(LinkedDevicesTags.SECTION).fetchSemanticsNode().boundsInRoot
            val current = onNodeWithTag("existing-device-card").fetchSemanticsNode().boundsInRoot
            val linked = onNodeWithTag(LinkedDevicesTags.DEVICE).fetchSemanticsNode().boundsInRoot
            val connect = onNodeWithTag("connect-device-action").fetchSemanticsNode().boundsInRoot

            assertTrue(section.encloses(current) && section.encloses(linked))
            assertTrue(linked.top > current.bottom)
            assertTrue(connect.top > section.bottom)
            assertEquals(current.left, linked.left)
            assertEquals(current.right, linked.right)
            assertEquals(current.left, connect.left)
            assertEquals(current.right, connect.right)
        }

    @Test
    fun `the device list has no heading repeating the page title`() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            setContent { Screen(loaded(linked = LinkedDevicesUiState.Listed(listOf(watch)))) }

            assertEquals(1, onAllNodesWithText("Devices").fetchSemanticsNodes().size)
        }

    @Test
    fun `every device row offers its actions from one options button`() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            setContent { Screen(loaded(linked = LinkedDevicesUiState.Listed(listOf(watch)))) }

            val rows = listOf("existing-device-card", LinkedDevicesTags.DEVICE)
            rows.forEach { tag ->
                onNode(hasContentDescription("Device options") and hasAnyAncestor(hasTestTag(tag))).assertExists()
            }
            onNodeWithText("Remove").assertDoesNotExist()
        }

    @Test
    fun `the current device row names this device instead of a last active time`() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            setContent { Screen(loaded()) }

            onNode(hasTestTag("existing-device-card") and hasText("This device")).assertExists()
            onNodeWithText("Last active: Today").assertDoesNotExist()
            onNodeWithText("Android").assertExists()
        }

    @Test
    fun `a failed rename is reported below the device list, not inside the row`() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            setContent { Screen(loaded(operations = mapOf(phone.id to DeviceOperationState.Failed(DeviceAction.Rename)))) }

            val failure = "Couldn’t rename this device. Try again."
            val row = onNodeWithTag("existing-device-card").fetchSemanticsNode().boundsInRoot
            val message = onNodeWithText(failure).fetchSemanticsNode().boundsInRoot
            assertTrue(message.top > row.bottom)
            onNode(hasTestTag("existing-device-card") and hasText(failure)).assertDoesNotExist()
        }

    @Test
    fun `a rename in progress replaces the options button`() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            setContent { Screen(loaded(operations = mapOf(phone.id to DeviceOperationState.Running(DeviceAction.Rename)))) }

            onNodeWithContentDescription("Device options").assertDoesNotExist()
            onNode(
                hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate),
                useUnmergedTree = true,
            ).assertExists()
        }

    @Composable
    private fun Screen(state: DevicesUiState) {
        LogDateTheme(dynamicColor = false, darkTheme = false) {
            DevicesScreenContent(onBackClick = {}, uiState = state, connectionAction = {})
        }
    }

    private fun loaded(
        linked: LinkedDevicesUiState = LinkedDevicesUiState.Unavailable,
        operations: Map<Uuid, DeviceOperationState> = emptyMap(),
    ) = DevicesUiState(
        availability = DeviceListAvailability.Available,
        devices = listOf(phone),
        operations = operations,
        linked = linked,
    )

    private fun Rect.encloses(other: Rect): Boolean =
        left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom
}
