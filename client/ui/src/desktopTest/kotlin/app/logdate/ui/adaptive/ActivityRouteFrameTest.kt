package app.logdate.ui.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.logdate.ui.foldable.FoldableHingeBounds
import app.logdate.ui.foldable.FoldableHingeInfo
import app.logdate.ui.foldable.FoldableHingeOrientation
import app.logdate.ui.foldable.FoldableHingeState
import app.logdate.ui.foldable.FoldableLayoutInfo
import app.logdate.ui.foldable.FoldableOcclusionType
import app.logdate.ui.foldable.FoldablePosture
import app.logdate.ui.foldable.LocalFoldableLayoutInfoOverride
import app.logdate.ui.theme.LogDateTheme
import app.logdate.ui.workspace.AdaptiveWorkspaceLayout
import app.logdate.ui.workspace.PanelConstraints
import app.logdate.ui.workspace.WorkspaceSearchScope
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ActivityRouteFrameTest {
    @Test
    fun bookSearchStaysInsideItsCollectionPane() = verifyBookSearch(false, LayoutDirection.Ltr)

    @Test
    fun rtlBookSearchStaysInsideItsCollectionPane() = verifyBookSearch(false, LayoutDirection.Rtl)

    @Test
    fun relatedBookSearchAlignsWithTheSourceCollection() = verifyBookSearch(true, LayoutDirection.Ltr)

    @Test
    fun relatedRtlBookSearchAlignsWithTheSourceCollection() = verifyBookSearch(true, LayoutDirection.Rtl)

    private fun verifyBookSearch(
        related: Boolean,
        direction: LayoutDirection,
    ) = runDesktopComposeUiTest(width = if (related) 1400 else 1000, height = 900) {
        val width = if (related) 1400 else 1000
        val hingeLeft = width / 2f - 10f
        val hingeRight = hingeLeft + 20f
        val foldable =
            FoldableLayoutInfo(
                isFoldable = true,
                posture = FoldablePosture.Book,
                hinge =
                    FoldableHingeInfo(
                        orientation = FoldableHingeOrientation.Vertical,
                        state = FoldableHingeState.HalfOpened,
                        occlusionType = FoldableOcclusionType.Full,
                        bounds =
                            FoldableHingeBounds(
                                hingeLeft.dp,
                                0.dp,
                                hingeRight.dp,
                                900.dp,
                                20.dp,
                                900.dp,
                            ),
                        isSeparating = true,
                    ),
            )
        setContent {
            LogDateTheme {
                CompositionLocalProvider(
                    LocalFoldableLayoutInfoOverride provides foldable,
                    LocalLayoutDirection provides direction,
                ) {
                    ActivityRouteFrame(composePanels = !related) {
                        if (related) {
                            AdaptiveWorkspaceLayout(
                                Modifier.fillMaxSize(),
                                browseOnStart = true,
                                browseConstraints = PanelConstraints(320.dp, 400.dp, 440.dp),
                                focusConstraints = PanelConstraints(480.dp, 720.dp, 960.dp),
                                browse = {
                                    WorkspaceSearchScope("", "Search journals", {})
                                    Box(Modifier.fillMaxSize().testTag("collection"))
                                },
                                focus = { Box(Modifier.fillMaxSize().testTag("detail")) },
                            )
                        } else {
                            WorkspaceSearchScope("", "Search journals", {})
                            Box(Modifier.fillMaxSize().testTag("collection"))
                        }
                    }
                }
            }
        }
        onAllNodesWithTag("workspace_search").assertCountEquals(1)
        val search = onNodeWithTag("workspace_search").fetchSemanticsNode().boundsInRoot
        val collection = onNodeWithTag("collection").fetchSemanticsNode().boundsInRoot
        assertTrue(search.right <= hingeLeft || search.left >= hingeRight, "Search must avoid the physical hinge")
        assertTrue(search.left >= collection.left && search.right <= collection.right, "Search must align with its source pane")
        System.getenv("LOGDATE_ACTIVITY_SEARCH_SCREENSHOT_DIR")?.let { directory ->
            val image = onRoot().captureToImage()
            val pixels = image.toPixelMap()
            val output = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until image.height) for (x in 0 until image.width) output.setRGB(x, y, pixels[x, y].toArgb())
            val file = File(directory, "${if (related) "related" else "standalone"}-${direction.name}.png")
            file.parentFile.mkdirs()
            ImageIO.write(output, "png", file)
        }
    }

    @Test
    fun readingActivityIsBoundedWithoutHomeChrome() =
        runDesktopComposeUiTest(width = 1600, height = 1000) {
            setContent {
                LogDateTheme {
                    ActivityRouteFrame { Box(Modifier.fillMaxSize().testTag("reading")) }
                }
            }
            val bounds = onNodeWithTag("reading").fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.width <= 960f, "Reading content should not become a full-width 1600dp panel")
            assertTrue(bounds.left > 0f, "A bounded reading activity should be placed deliberately within the window")
        }
}
