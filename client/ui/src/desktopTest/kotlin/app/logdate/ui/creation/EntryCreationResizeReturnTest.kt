@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package app.logdate.ui.creation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import app.logdate.ui.LocalNavAnimatedVisibilityScope
import app.logdate.ui.LocalSharedTransitionScope
import app.logdate.ui.theme.LogDateTheme
import app.logdate.ui.workspace.WorkspaceDestination
import app.logdate.ui.workspace.WorkspaceScaffold
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class EntryCreationResizeReturnTest {
    @Test
    fun compactLaunchReturningToARailUsesTheCurrentSourceOutlineForTheSameVisit() =
        runSkikoComposeUiTest(size = Size(1000f, 800f)) {
            val launcher = EntryCreationLauncher()
            var editor by mutableStateOf(false)
            var viewportWidth by mutableStateOf(390.dp)
            var launchId: String? = null
            var launchShape = EntryCreationShape.Large
            var destinationShape = EntryCreationShape.Large
            var destinationOutline: Outline? = null
            var expectedLargeOutline: Outline? = null
            var sharedTransitionActive = false
            mainClock.autoAdvance = false
            setContent {
                LogDateTheme(dynamicColor = false) {
                    val holder = rememberSaveableStateHolder()
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.width(viewportWidth).height(780.dp).testTag("creation_viewport")) {
                            SharedTransitionLayout(Modifier.fillMaxSize()) {
                                sharedTransitionActive = isTransitionActive
                                CompositionLocalProvider(
                                    LocalSharedTransitionScope provides this,
                                    LocalEntryCreationLauncher provides launcher,
                                ) {
                                    AnimatedContent(editor) { destination ->
                                        CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                                            holder.SaveableStateProvider(destination) {
                                                if (destination) {
                                                    val id = launchId
                                                    val kind = if (id == null) launchShape else launcher.sourceShape(id, launchShape)
                                                    val base = entryCreationBaseShape(kind)
                                                    val density = LocalDensity.current
                                                    val direction = LocalLayoutDirection.current
                                                    val extent = with(density) { 48.dp.toPx() }
                                                    val outline = base.createOutline(Size(extent, extent), direction, density)
                                                    val expected =
                                                        MaterialTheme.shapes.large.createOutline(
                                                            Size(extent, extent),
                                                            direction,
                                                            density,
                                                        )
                                                    SideEffect {
                                                        destinationShape = kind
                                                        destinationOutline = outline
                                                        expectedLargeOutline = expected
                                                    }
                                                    Surface(
                                                        Modifier.fillMaxSize().entryCreationBounds(
                                                            id,
                                                            destination = true,
                                                            sourceShape = launchShape,
                                                        ),
                                                    ) { Box(Modifier.fillMaxSize()) }
                                                } else {
                                                    WorkspaceScaffold(
                                                        destinations = listOf(WorkspaceDestination("home", "Timeline", Icons.Default.Home)),
                                                        selectedKey = "home",
                                                        onSelect = {},
                                                        onCreate = {
                                                            launchId = launcher.currentSourceId
                                                            launchShape = launcher.currentSourceShape
                                                            editor = true
                                                        },
                                                        content = {},
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            waitForIdle()
            val initialViewport = onNodeWithTag("creation_viewport").getUnclippedBoundsInRoot()
            assertEquals(390.dp, initialViewport.right - initialViewport.left, "Fixture must actually be compact before launch")
            onNodeWithContentDescription("Add a memory").performClick()
            mainClock.advanceTimeBy(1_000) // The Expressive container spring settles after its small overshoot.
            waitForIdle()
            val sourceId = assertNotNull(launchId)
            assertEquals(EntryCreationShape.Round, launchShape, "Fixture must launch from the real compact header")
            val compactOutline = assertIs<Outline.Rounded>(assertNotNull(destinationOutline)).roundRect

            runOnIdle { viewportWidth = 800.dp }
            repeat(3) { mainClock.advanceTimeByFrame() }
            val viewport = onNodeWithTag("creation_viewport").getUnclippedBoundsInRoot()
            assertEquals(800.dp, viewport.right - viewport.left)
            runOnIdle { editor = false }
            repeat(3) { mainClock.advanceTimeByFrame() }
            waitForIdle()
            assertTrue(sharedTransitionActive, "Inspect the return while the matched transition is still running")
            val sourceBounds = onNodeWithContentDescription("Add a memory").getUnclippedBoundsInRoot()
            assertTrue(sourceBounds.right - sourceBounds.left > 48.dp, "The source must now be the actual rail FAB")
            assertEquals(
                EntryCreationShape.Large,
                launcher.sourceShape(sourceId, launchShape),
                "Return must register the same saved visit ID",
            )
            assertEquals(
                EntryCreationShape.Large,
                destinationShape,
                "The destination must observe the current source geometry before settling",
            )
            val actual = assertIs<Outline.Rounded>(assertNotNull(destinationOutline)).roundRect
            val expected = assertIs<Outline.Rounded>(assertNotNull(expectedLargeOutline)).roundRect
            assertEquals(expected, actual)
            assertNotEquals(compactOutline, actual, "The launch-time round fallback must not mask a resized source")
        }
}
