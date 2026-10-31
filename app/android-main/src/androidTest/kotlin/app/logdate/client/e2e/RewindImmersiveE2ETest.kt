@file:Suppress("ktlint:standard:function-naming")

package app.logdate.client.e2e

import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.logdate.client.text.toUiText
import app.logdate.feature.core.main.HomeRoute
import app.logdate.feature.rewind.navigation.RewindDetailRoute
import app.logdate.feature.rewind.ui.RewindDetailUiState
import app.logdate.feature.rewind.ui.SubtitledRewindPanelUiState
import app.logdate.feature.rewind.ui.detail.RewindDetailScreenContent
import app.logdate.navigation.rememberWorkspaceRouteDecorator
import app.logdate.navigation.scenes.RelatedContentSceneStrategy
import app.logdate.ui.foldable.FoldableHingeBounds
import app.logdate.ui.foldable.FoldableHingeInfo
import app.logdate.ui.foldable.FoldableHingeOrientation
import app.logdate.ui.foldable.FoldableHingeState
import app.logdate.ui.foldable.FoldableLayoutInfo
import app.logdate.ui.foldable.FoldableOcclusionType
import app.logdate.ui.foldable.provideFoldableLayoutInfo
import app.logdate.ui.navigation.taggedEntry
import app.logdate.ui.theme.LogDateTheme
import app.logdate.ui.workspace.LocalWorkspaceSearchAction
import app.logdate.ui.workspace.WorkspaceDestination
import app.logdate.ui.workspace.WorkspaceScaffold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Exercises production route framing and playback on Gradle Managed Devices only. */
class RewindImmersiveE2ETest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val folded = mutableStateOf(false)
    private val externallyPaused = mutableStateOf(true)

    @Before fun configureSystemBars() {
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        compose.setContent { PlaybackNavigation() }
    }

    @Test fun playbackOccupiesTheWindowAndReturnsToHome() {
        compose.onNodeWithText("Open rewind").performClick()
        compose.onNodeWithText("First moment").assertIsDisplayed()
        compose.onAllNodesWithTag("workspace_search").assertCountEquals(0)
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val playback = compose.onNodeWithTag("rewind_fullscreen").fetchSemanticsNode().boundsInRoot
        assertEquals(root, playback)
        capture("rewind-immersive")
        compose.onNodeWithContentDescription("Close rewind").performClick()
        compose.onNodeWithText("Open rewind").assertIsDisplayed()
        compose.onAllNodesWithTag("workspace_search").assertCountEquals(1)
    }

    @Test fun foldingPreservesTheMomentAndKeepsCloseWithTheStory() {
        compose.onNodeWithText("Open rewind").performClick()
        advance()
        compose.onNodeWithText("Second moment").assertIsDisplayed()
        compose.runOnUiThread { folded.value = true }
        compose.onNodeWithText("Second moment").assertIsDisplayed()
        val window = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val close = compose.onNodeWithContentDescription("Close rewind").fetchSemanticsNode().boundsInRoot
        val narrowFold = with(compose.density) { (window.width.toDp() / 2 - 10.dp) < 560.dp }
        if (narrowFold) {
            assertTrue("Narrow folds must retain full-width playback controls", close.left > window.width / 2)
            val moment = compose.onNodeWithText("Second moment").fetchSemanticsNode().boundsInRoot
            assertEquals("The story must stay centered across a narrow fold", window.center.x, moment.center.x, 2f)
        } else {
            assertTrue("Close must remain in the tablet story's hinge-safe region", close.right < window.width / 2)
        }
        capture("rewind-immersive-book")
        compose.runOnUiThread { folded.value = false }
        compose.onNodeWithText("Second moment").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close rewind").performClick()
        compose.onNodeWithText("Open rewind").assertIsDisplayed()
    }

    @Test fun completingAndWatchingAgainRestartsTheSameViewer() {
        compose.onNodeWithText("Open rewind").performClick()
        repeat(3) { advance() }
        compose.onNodeWithText("Watch again").performClick()
        compose.onNodeWithText("First moment").assertIsDisplayed()
        repeat(3) { advance() }
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Open rewind").assertIsDisplayed()
    }

    @Test fun systemBackDismissesPlaybackAndReturnsToHome() {
        compose.onNodeWithText("Open rewind").performClick()
        compose.runOnUiThread { externallyPaused.value = false }
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Open rewind").assertIsDisplayed()
    }

    private fun advance() {
        compose.onNodeWithTag("rewind_fullscreen").performTouchInput {
            click(Offset(width * 0.75f, height * 0.5f))
        }
        compose.waitForIdle()
    }

    @Composable
    private fun PlaybackNavigation() {
        val backStack = remember { mutableStateListOf<NavKey>(HomeRoute) }
        val window = LocalWindowInfo.current.containerSize
        val photo =
            remember {
                val target = InstrumentationRegistry.getInstrumentation().targetContext
                val file = File(target.cacheDir, "rewind-immersive-fixture.jpg")
                target.resources.openRawResource(app.logdate.client.R.drawable.workspace_sample_photo).use { input ->
                    file.outputStream().use { input.copyTo(it) }
                }
                file.toURI().toString()
            }
        val density = LocalDensity.current
        val fold =
            with(density) {
                if (folded.value) {
                    val width = window.width.toDp()
                    val height = window.height.toDp()
                    FoldableLayoutInfo(
                        isFoldable = true,
                        hinge =
                            FoldableHingeInfo(
                                FoldableHingeOrientation.Vertical,
                                FoldableHingeState.HalfOpened,
                                FoldableOcclusionType.Full,
                                FoldableHingeBounds(width / 2 - 10.dp, 0.dp, width / 2 + 10.dp, height, 20.dp, height),
                                true,
                            ),
                    )
                } else {
                    FoldableLayoutInfo()
                }
            }
        LogDateTheme {
            CompositionLocalProvider(LocalWorkspaceSearchAction provides {}) {
                provideFoldableLayoutInfo(fold) {
                    NavDisplay(
                        backStack = backStack,
                        onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
                        sceneStrategies = listOf(RelatedContentSceneStrategy<NavKey>(supportsDualPane = { true })),
                        entryDecorators = listOf(rememberWorkspaceRouteDecorator()),
                        entryProvider =
                            entryProvider {
                                taggedEntry<HomeRoute> {
                                    WorkspaceScaffold(
                                        destinations = listOf(WorkspaceDestination("rewind", "Rewind", Icons.Default.History)),
                                        selectedKey = "rewind",
                                        onSelect = {},
                                        onSearch = {},
                                    ) {
                                        TextButton(onClick = { backStack.add(RewindDetailRoute(REWIND_ID)) }) { Text("Open rewind") }
                                    }
                                }
                                taggedEntry<RewindDetailRoute> {
                                    RewindDetailScreenContent(
                                        uiState =
                                            RewindDetailUiState.Success(
                                                listOf(
                                                    SubtitledRewindPanelUiState("First moment", "Monday".toUiText(), photo),
                                                    SubtitledRewindPanelUiState("Second moment", "Tuesday".toUiText(), photo),
                                                    SubtitledRewindPanelUiState("Third moment", "Wednesday".toUiText(), photo),
                                                ),
                                            ),
                                        onExitRewind = { backStack.removeLastOrNull() },
                                        externalPause = externallyPaused.value,
                                        modifier = Modifier.fillMaxSize().testTag("rewind_fullscreen"),
                                    )
                                }
                            },
                    )
                }
            }
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val directory =
            InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                ?: error("Runtime screenshots require a Gradle Managed Device task")
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val file = File(directory, "$name-${device.displayWidth}x${device.displayHeight}.png")
        file.parentFile?.mkdirs()
        compose.waitUntil(timeoutMillis = 10000) {
            check(device.takeScreenshot(file))
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            val colorfulPixels =
                (0 until bitmap.width step 32).sumOf { x ->
                    (0 until bitmap.height step 32).count { y ->
                        val color = bitmap.getPixel(x, y)
                        val red = (color shr 16) and 255
                        val green = (color shr 8) and 255
                        val blue = color and 255
                        maxOf(red, green, blue) - minOf(red, green, blue) > 25
                    }
                }
            bitmap.recycle()
            colorfulPixels > 50
        }
    }

    private companion object {
        const val REWIND_ID = "00000000-0000-0000-0000-000000000020"
    }
}
