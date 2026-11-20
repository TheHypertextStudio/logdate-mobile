@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui.devices

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
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
            linkedAtFormatted = "Oct 3, 2026, 9:41 AM".toUiText(),
            lastSeenFormatted = "Oct 16, 2026, 8:02 AM".toUiText(),
            pairedWatch = false,
            erasesData = true,
        )

    @Test
    fun `a linked device shows its platform and times`() =
        runComposeUiTest {
            setContent { Section(LinkedDevicesUiState.Listed(listOf(watch))) }

            onNodeWithText("Pixel Watch 3").assertExists()
            onNodeWithText("Wear OS").assertExists()
            onNodeWithText("Linked Oct 3, 2026, 9:41 AM · Last seen Oct 16, 2026, 8:02 AM").assertExists()
        }

    @Test
    fun `remove asks first and then removes that device`() =
        runComposeUiTest {
            val removed = mutableListOf<String>()
            setContent { Section(LinkedDevicesUiState.Listed(listOf(watch)), onRemove = { removed += it }) }

            onNode(hasText("Remove") and hasClickAction()).performClick()
            assertTrue(removed.isEmpty())
            onNodeWithText("Remove Device").assertExists()
            onAllNodesWithText("Remove").fetchSemanticsNodes().let { assertEquals(2, it.size) }
            clickLast("Remove")

            assertEquals(listOf("watch-session"), removed)
        }

    @Test
    fun `removing a watch warns that it erases what it has not sent`() =
        runComposeUiTest {
            setContent { Section(LinkedDevicesUiState.Listed(listOf(watch))) }

            onNode(hasText("Remove") and hasClickAction()).performClick()

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
            onNode(hasText("Remove") and hasClickAction()).assertExists()
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

            onNodeWithText("Pixel Watch 3 couldn’t be removed from your account.").assertExists()
            onNodeWithText("Try again").performClick()
            assertTrue(removed.isEmpty())
            onNodeWithText("Remove Device").assertExists()
            clickLast("Remove")

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
            onNodeWithText("Watch").assertExists()
            onNodeWithText("Remove").assertExists()
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
            onNodeWithText("Remove").assertExists()
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
                onNodeWithText("Remove").assertExists()
            }
        }
    }

    private fun loadedWith(linked: LinkedDeviceUiState) =
        DevicesUiState(availability = DeviceListAvailability.Available, linked = LinkedDevicesUiState.Listed(listOf(linked)))

    private fun SemanticsNodeInteractionsProvider.clickLast(text: String) {
        val nodes = onAllNodesWithText(text)
        nodes[nodes.fetchSemanticsNodes().size - 1].performClick()
    }

    @Composable
    private fun Section(
        state: LinkedDevicesUiState,
        onRemove: (String) -> Unit = {},
        onRetry: () -> Unit = {},
    ) {
        LogDateTheme {
            LinkedDevicesSection(
                state = state,
                onRemove = onRemove,
                onRetry = onRetry,
                onDismissNotice = {},
            )
        }
    }
}
