@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui.devices

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import app.logdate.client.sync.peer.devices.LinkedDevicesFailure
import app.logdate.client.text.toUiText
import app.logdate.feature.core.settings.ui.watch.WatchStatus
import app.logdate.ui.theme.LogDateTheme
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.devices_platform_wear_os
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class LinkedDevicesSectionTest {
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
    fun `a linked device shows its platform and when it was last active`() =
        runComposeUiTest {
            setContent { Section(LinkedDevicesUiState.Listed(listOf(watch))) }

            onNodeWithText("Pixel Watch 3").assertExists()
            onNodeWithText("Wear OS").assertExists()
            onNodeWithText("Last active: Oct 16, 2026").assertExists()
        }

    @Test
    fun `remove asks first and then removes that device`() =
        runComposeUiTest {
            val removed = mutableListOf<String>()
            setContent { Section(LinkedDevicesUiState.Listed(listOf(watch)), onRemove = { removed += it }) }

            openOptions()
            onNodeWithText("Remove watch").performClick()
            assertTrue(removed.isEmpty())
            onNodeWithText("Remove Device").assertExists()
            onNode(hasText("Remove") and hasClickAction()).performClick()

            assertEquals(listOf("watch-session"), removed)
        }

    @Test
    fun `removing a watch warns that it erases what it has not sent`() =
        runComposeUiTest {
            setContent { Section(LinkedDevicesUiState.Listed(listOf(watch))) }

            openOptions()
            onNodeWithText("Remove watch").performClick()

            onNodeWithText(
                "Remove \"Pixel Watch 3\" from your account? It stops syncing and erases its LogDate data, " +
                    "including any moments it hasn’t sent yet.",
            ).assertExists()
        }

    @Test
    fun `a paired watch keeps its removal action even when a companion slot exists`() =
        runComposeUiTest {
            setContent { Section(LinkedDevicesUiState.Listed(listOf(watch.copy(pairedWatch = true)))) }

            onNodeWithText("Pixel Watch 3").assertExists()
            openOptions()
            onNode(hasText("Remove watch") and hasClickAction()).assertExists()
        }

    @Test
    fun `the paired watch is listed when the page shows no watch card`() =
        runComposeUiTest {
            setContent { Section(LinkedDevicesUiState.Listed(listOf(watch.copy(pairedWatch = true)))) }

            onNodeWithText("Pixel Watch 3").assertExists()
        }

    @Test
    fun `a failed load says why and can be tried again`() =
        runComposeUiTest {
            var retries = 0
            setContent { Section(LinkedDevicesUiState.Failed(LinkedDevicesFailure.Refused(503)), onRetry = { retries++ }) }

            onNodeWithText("The LogDate server couldn’t list linked devices (HTTP 503).").assertExists()
            onNodeWithText("Try again").performClick()

            assertEquals(1, retries)
        }

    @Test
    fun `a watch the account could not release says so and asks again before retrying`() =
        runComposeUiTest {
            val removed = mutableListOf<String>()
            val notice = LinkedDeviceNotice.RemoveFailed(watch.id, "Pixel Watch 3", LinkedDevicesFailure.WatchRemovalStopped("revoke"))
            setContent { Section(LinkedDevicesUiState.Listed(listOf(watch), notice = notice), onRemove = { removed += it }) }

            val message = "Pixel Watch 3 couldn’t be removed from your account."
            onNodeWithText(message).assertExists()
            val row = onNodeWithTag(LinkedDevicesTags.DEVICE).fetchSemanticsNode().boundsInRoot
            assertTrue(onNodeWithText(message).fetchSemanticsNode().boundsInRoot.top > row.bottom)
            onNodeWithText("Try again").performClick()
            assertTrue(removed.isEmpty())
            onNodeWithText("Remove Device").assertExists()
            onNode(hasText("Remove") and hasClickAction()).performClick()

            assertEquals(listOf("watch-session"), removed)
        }

    @Test
    fun `a removed watch says how many unsynced moments were cleared with it`() =
        runComposeUiTest {
            setContent {
                Section(LinkedDevicesUiState.Listed(emptyList(), notice = LinkedDeviceNotice.ClearedUnsent("Pixel Watch 3", 2)))
            }

            onNodeWithText("Pixel Watch 3 was removed. 2 moments that couldn’t sync were cleared with it.").assertExists()
        }

    @Test
    fun `a removed watch that could not count its moments still says they were cleared`() =
        runComposeUiTest {
            setContent {
                Section(LinkedDevicesUiState.Listed(emptyList(), notice = LinkedDeviceNotice.ClearedUnsent("Pixel Watch 3", null)))
            }

            onNodeWithText("Pixel Watch 3 was removed. Anything on it that hadn’t synced was cleared with it.").assertExists()
        }

    @Test
    fun `nothing is shown while linking is unavailable or nothing is linked`() =
        runComposeUiTest {
            setContent {
                Section(LinkedDevicesUiState.Unavailable)
                Section(LinkedDevicesUiState.Listed(emptyList()))
            }

            assertTrue(onAllNodesWithTag(LinkedDevicesTags.SECTION).fetchSemanticsNodes().isEmpty())
            assertTrue(onAllNodesWithTag(LinkedDevicesTags.DEVICE).fetchSemanticsNodes().isEmpty())
        }

    @Test
    fun `the exact paired row retains current disconnected state and both actions`() =
        runComposeUiTest {
            val linked = watch.copy(pairedWatch = true, pairedWatchNodeId = "watch-node")
            setContent {
                LogDateTheme {
                    DevicesScreenContent(
                        onBackClick = {},
                        uiState = loadedWith(linked),
                        watchDevice = { Text("Local companion card") },
                        connectionAction = {},
                        onManagePairedWatch = {},
                        pairedWatchStatus = WatchStatus.Ready("Pixel Watch 3", false, nodeId = "watch-node"),
                    )
                }
            }
            onNodeWithText("Out of range").assertExists()
            openOptions()
            onNodeWithText("Watch settings").assertExists()
            onNodeWithText("Remove watch").assertExists()
            onNodeWithText("Local companion card").assertDoesNotExist()
            onNodeWithText("Connected devices").assertDoesNotExist()
            onNodeWithText("Linked devices").assertDoesNotExist()
        }

    @Test
    fun `the exact paired row shows actual current phone connection`() =
        runComposeUiTest {
            val linked = watch.copy(pairedWatch = true, pairedWatchNodeId = "watch-node")
            setContent {
                LogDateTheme {
                    DevicesScreenContent(
                        onBackClick = {},
                        uiState = loadedWith(linked),
                        connectionAction = {},
                        onManagePairedWatch = {},
                        pairedWatchStatus = WatchStatus.Ready("Pixel Watch 3", true, nodeId = "watch-node"),
                    )
                }
            }
            onNodeWithText("Connected").assertExists()
            openOptions()
            onNodeWithText("Remove watch").assertExists()
        }

    @Test
    fun `a stale or unidentified paired row never receives another nodes current state`() {
        for (pairedNode in listOf("old-node", null)) {
            runComposeUiTest {
                val linked = watch.copy(pairedWatch = true, pairedWatchNodeId = pairedNode)
                setContent {
                    LogDateTheme {
                        DevicesScreenContent(
                            onBackClick = {},
                            uiState = loadedWith(linked),
                            watchDevice = { Text("Current companion card") },
                            connectionAction = {},
                            onManagePairedWatch = {},
                            pairedWatchStatus = WatchStatus.Ready("Same display name", true, nodeId = "different-node"),
                        )
                    }
                }
                onNodeWithText("Connected").assertDoesNotExist()
                onNodeWithText("Current companion card").assertExists()
                openOptions()
                onNodeWithText("Remove watch").assertExists()
            }
        }
    }

    private fun loadedWith(linked: LinkedDeviceUiState) =
        DevicesUiState(availability = DeviceListAvailability.Available, linked = LinkedDevicesUiState.Listed(listOf(linked)))

    private fun SemanticsNodeInteractionsProvider.openOptions() {
        onNodeWithContentDescription("Device options").performClick()
    }

    @Composable
    private fun Section(
        state: LinkedDevicesUiState,
        onRemove: (String) -> Unit = {},
        onRetry: () -> Unit = {},
    ) {
        LogDateTheme {
            DevicesScreenContent(
                onBackClick = {},
                uiState = DevicesUiState(availability = DeviceListAvailability.Available, linked = state),
                connectionAction = {},
                onRemoveLinkedDevice = onRemove,
                onRetryLinkedDevices = onRetry,
            )
        }
    }
}
