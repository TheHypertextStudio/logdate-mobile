@file:Suppress("ktlint:standard:function-naming")

package app.logdate.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.logdate.client.text.toUiText
import app.logdate.feature.rewind.ui.RewindPanelUiState
import app.logdate.feature.rewind.ui.SubtitledRewindPanelUiState
import app.logdate.feature.rewind.ui.detail.AudioNotePanel
import app.logdate.feature.rewind.ui.detail.ImageNotePanel
import app.logdate.feature.rewind.ui.detail.RewindStoryView
import app.logdate.ui.foldable.FoldableHingeBounds
import app.logdate.ui.foldable.FoldableHingeInfo
import app.logdate.ui.foldable.FoldableHingeOrientation
import app.logdate.ui.foldable.FoldableHingeState
import app.logdate.ui.foldable.FoldableLayoutInfo
import app.logdate.ui.foldable.FoldableOcclusionType
import app.logdate.ui.foldable.provideFoldableLayoutInfo
import app.logdate.ui.theme.LogDateTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class RewindPlaybackInteractionTest {
    @Test fun audioDateAndDurationStayVisibleAtLargeTextSizes() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                    LogDateTheme {
                        AudioNotePanel(
                            sourceId = Uuid.parse("00000000-0000-0000-0000-000000000001"),
                            uri = "fixture://audio",
                            durationMs = 42000,
                            transcriptionText = "A peaceful afternoon on the walk home.",
                            dateFormatted = "Thursday, September 24",
                            recordedAt = Instant.parse("2026-09-24T15:00:00Z"),
                            cachedAmplitudes = listOf(0.2f, 0.8f),
                            isPlaying = false,
                            playbackProgress = 0f,
                            onTogglePlayback = { _, _ -> },
                        )
                    }
                }
            }
            onNodeWithText("0:42").assertIsDisplayed()
            onNodeWithText("Thursday, September 24").assertIsDisplayed()
            val transcript = onNodeWithText("A peaceful afternoon on the walk home.").captureToImage().toPixelMap()
            val hasLightText =
                (0 until transcript.width).any { x ->
                    (0 until transcript.height).any { y ->
                        val pixel = transcript[x, y]
                        pixel.red > 0.9f && pixel.green > 0.9f && pixel.blue > 0.9f
                    }
                }
            assertTrue(hasLightText, "Audio transcripts need light text on their dark story background")
        }

    @Test fun interactiveStoryContentReceivesTapsWithoutAdvancing() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            var taps = 0
            setContent {
                LogDateTheme {
                    RewindStoryView(panels, onExit = {}, externalPause = true) { panel ->
                        Box(Modifier.fillMaxSize()) {
                            androidx.compose.material3.TextButton(
                                onClick = { taps++ },
                                modifier = Modifier.testTag("audio_play"),
                            ) { Text((panel as SubtitledRewindPanelUiState).title) }
                        }
                    }
                }
            }
            onNodeWithTag("audio_play").performTouchInput { click() }
            assertEquals(1, taps)
            onNodeWithText("First moment").assertIsDisplayed()
        }

    @Test fun videoLabelSitsBelowPlaybackActions() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            setContent {
                LogDateTheme {
                    RewindStoryView(panels, onExit = {}, externalPause = true, onSharePanel = {}) {
                        ImageNotePanel("fixture://video-frame", "Afternoon outside", "Thursday", isVideoFrame = true)
                    }
                }
            }
            val badge = onNodeWithText("Video still").fetchSemanticsNode().boundsInRoot
            val close = onNodeWithContentDescription("Close rewind").fetchSemanticsNode().boundsInRoot
            assertTrue(badge.top > close.bottom, "Video label must stay below playback controls")
        }

    @Test fun narrowPlaybackKeepsCloseFullyReachable() =
        runDesktopComposeUiTest(width = 200, height = 891) {
            var shares = 0
            var exits = 0
            setContent {
                LogDateTheme {
                    RewindStoryView(
                        panels,
                        onExit = { exits++ },
                        externalPause = true,
                        onSharePanel = { shares++ },
                        onShareRewindStats = {},
                        onDeleteRewind = {},
                    ) { Text((it as SubtitledRewindPanelUiState).title) }
                }
            }
            val close = onNodeWithContentDescription("Close rewind").fetchSemanticsNode().boundsInRoot
            assertTrue(close.width >= 48f, "Close's visible button must not clip: $close")
            onNodeWithContentDescription("More rewind actions").performClick()
            onNodeWithText("Share this moment").performClick()
            assertEquals(1, shares)
            onNodeWithText("First moment").assertIsDisplayed()
            onRoot().performTouchInput { click(Offset(close.left + 1f, close.center.y)) }
            assertEquals(1, exits, "Close keeps its full 48dp touch area")
        }

    @Test fun narrowActionMenuPausesPlaybackWhileChoosingAnAction() =
        runDesktopComposeUiTest(width = 200, height = 891) {
            mainClock.autoAdvance = false
            setContent {
                LogDateTheme {
                    RewindStoryView(panels, onExit = {}, onSharePanel = {}, onShareRewindStats = {}, onDeleteRewind = {}) {
                        Text((it as SubtitledRewindPanelUiState).title)
                    }
                }
            }
            onNodeWithContentDescription("More rewind actions").performClick()
            mainClock.advanceTimeBy(6000)
            onNodeWithText("First moment").assertIsDisplayed()
        }

    @Test fun closeIsClickableAboveTheStoryTapLayer() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            var exits = 0
            setContent { Story(onExit = { exits++ }) }
            onNodeWithContentDescription("Close rewind").performClick()
            assertEquals(1, exits)
            onNodeWithText("First moment").assertIsDisplayed()
        }

    @Test fun sharingTargetsTheActivePanelWithoutAdvancingIt() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            var shared: RewindPanelUiState? = null
            setContent { Story(onShare = { shared = it }) }
            onNodeWithTag("moment").performTouchInput { click(Offset(width * 0.75f, height * 0.5f)) }
            onNodeWithText("Second moment").assertIsDisplayed()
            onNodeWithContentDescription("Share this moment").performClick()
            assertEquals(panels[1], shared)
            onNodeWithText("Second moment").assertIsDisplayed()
        }

    @Test fun foldingKeepsTheCurrentMoment() =
        runDesktopComposeUiTest(width = 1200, height = 800) {
            val fold = mutableStateOf(FoldableLayoutInfo())
            setContent {
                provideFoldableLayoutInfo(fold.value) { Story() }
            }
            onNodeWithTag("moment").performTouchInput { click(Offset(width * 0.75f, height * 0.5f)) }
            onNodeWithText("Second moment").assertIsDisplayed()
            runOnIdle {
                fold.value =
                    FoldableLayoutInfo(
                        isFoldable = true,
                        hinge =
                            FoldableHingeInfo(
                                FoldableHingeOrientation.Vertical,
                                FoldableHingeState.HalfOpened,
                                FoldableOcclusionType.Full,
                                FoldableHingeBounds(590.dp, 0.dp, 610.dp, 800.dp, 20.dp, 800.dp),
                                true,
                            ),
                    )
            }
            onNodeWithText("Second moment").assertIsDisplayed()
            val momentBounds = onNodeWithTag("moment").fetchSemanticsNode().boundsInRoot
            assertTrue(momentBounds.right <= 590f, "The story must stay clear of the hinge")
            assertTrue(momentBounds.width > 500f)
            runOnIdle { fold.value = FoldableLayoutInfo() }
            onNodeWithText("Second moment").assertIsDisplayed()
            onNodeWithTag("moment").performTouchInput { click(Offset(width * 0.25f, height * 0.5f)) }
            onNodeWithText("First moment").assertIsDisplayed()
        }

    @Test fun externalSheetsPauseAutoAdvanceUntilDismissed() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            mainClock.autoAdvance = false
            val paused = mutableStateOf(true)
            setContent { Story(paused = paused.value) }
            mainClock.advanceTimeBy(6000)
            onNodeWithText("First moment").assertIsDisplayed()
            runOnIdle { paused.value = false }
            mainClock.advanceTimeBy(6000)
            onNodeWithText("Second moment").assertIsDisplayed()
        }

    @Composable
    private fun Story(
        paused: Boolean = true,
        onExit: () -> Unit = {},
        onShare: ((RewindPanelUiState) -> Unit)? = null,
    ) {
        LogDateTheme {
            RewindStoryView(
                panels = panels,
                modifier = Modifier.fillMaxSize(),
                onExit = onExit,
                externalPause = paused,
                onSharePanel = onShare,
            ) { panel ->
                Box(Modifier.fillMaxSize().testTag("moment")) {
                    Text((panel as SubtitledRewindPanelUiState).title)
                }
            }
        }
    }

    private val panels =
        listOf(
            SubtitledRewindPanelUiState("First moment", "Monday".toUiText()),
            SubtitledRewindPanelUiState("Second moment", "Tuesday".toUiText()),
            SubtitledRewindPanelUiState("Third moment", "Wednesday".toUiText()),
        )
}
