package app.logdate.ui.workspace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.logdate.ui.theme.LogDateTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class WorkspaceSupportingAvailabilityTest {
    @Test fun unavailableFocusMakesTheCollectionFillPhoneAndTabletWithoutDeadSpace() {
        for (width in listOf(411, 1280)) {
            runDesktopComposeUiTest(width = width, height = 800) {
                setContent {
                    LogDateTheme {
                        WorkspaceSupportingSheet(
                            "Places",
                            focusAvailable = false,
                            header = { Text("Your places") },
                            focus = { Text("Map probe") },
                            supporting = { Box(Modifier.fillMaxSize().testTag("collection")) { Text("Riverside memory") } },
                        )
                    }
                }
                val bounds = onNodeWithTag("collection").fetchSemanticsNode().boundsInRoot
                assertEquals(width.toFloat(), bounds.width, 1f)
                assertTrue(bounds.top < 80f, "Collection starts below its header, without a reserved map area")
                assertTrue(bounds.bottom >= 799f)
                onNodeWithText("Map probe").assertDoesNotExist()
                onNodeWithContentDescription("Show map").assertDoesNotExist()
                onNodeWithContentDescription("Browse").assertDoesNotExist()
            }
        }
    }

    @Test fun mapRecoveryKeepsTheHostMountedAndRestoresTheRequestedExtent() =
        runDesktopComposeUiTest(width = 411, height = 800) {
            var available by mutableStateOf(true)
            var mounts = 0
            var mapClicks = 0
            setContent {
                LogDateTheme {
                    WorkspaceSupportingSheet(
                        "Places",
                        focusAvailable = available,
                        initialExtent = SupportingExtent.Browsing,
                        header = { Text("Your places") },
                        focus = {
                            DisposableEffect(Unit) {
                                mounts++
                                onDispose {}
                            }
                            Box(Modifier.fillMaxSize().clickable { mapClicks++ }) { Text("Map probe") }
                        },
                        supporting = { Box(Modifier.fillMaxSize().testTag("collection")) { Text("Selected Riverside memory") } },
                    )
                }
            }
            val initialTop = onNodeWithText("Your places").fetchSemanticsNode().boundsInRoot.top
            runOnIdle { available = false }
            onNodeWithTag("collection").performTouchInput { click(bottomCenter) }
            assertEquals(0, mapClicks, "Covered map must not receive collection input")
            onNodeWithText("Selected Riverside memory").fetchSemanticsNode()
            runOnIdle { available = true }
            assertEquals(initialTop, onNodeWithText("Your places").fetchSemanticsNode().boundsInRoot.top, 1f)
            assertEquals(1, mounts, "Keep the real map mounted so a late ready callback can recover")
        }

    @Test fun hostedCollectionDoesNotPassTrailingBlankAreaInputToTheHiddenMap() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            var mapClicks = 0
            setContent {
                LogDateTheme {
                    WorkspacePanel(Modifier.fillMaxSize()) {
                        WorkspaceSupportingSheet(
                            "Places",
                            focusAvailable = false,
                            supportingContainment = PanelContainment.Collection,
                            header = { Text("Your places") },
                            focus = { Box(Modifier.fillMaxSize().clickable { mapClicks++ }) },
                            supporting = { Box(Modifier.width(720.dp).fillMaxHeight()) { Text("Saved place") } },
                        )
                    }
                }
            }
            onRoot().performTouchInput { click(Offset(850f, 400f)) }
            assertEquals(0, mapClicks, "A hidden map is not an input target, including outside bounded cards")
        }

    @Test fun hiddenMapKeepsItsEventualViewportForAConsistentReadyCameraFit() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            var available by mutableStateOf(true)
            var size = IntSize.Zero
            setContent {
                LogDateTheme {
                    WorkspaceSupportingSheet(
                        "Places",
                        focusAvailable = available,
                        initialExtent = SupportingExtent.Browsing,
                        header = { Text("Your places") },
                        focus = { Box(Modifier.fillMaxSize().onSizeChanged { size = it }) },
                        supporting = { Text("Saved place") },
                    )
                }
            }
            onNodeWithText("Saved place").fetchSemanticsNode()
            val readySize = size
            assertTrue(readySize.width > 0)
            runOnIdle { available = false }
            onNodeWithText("Saved place").fetchSemanticsNode()
            assertEquals(readySize, size, "Late readiness must fit against the eventual map viewport")
            runOnIdle { available = true }
            onNodeWithText("Saved place").fetchSemanticsNode()
            assertEquals(readySize, size)
        }
}
