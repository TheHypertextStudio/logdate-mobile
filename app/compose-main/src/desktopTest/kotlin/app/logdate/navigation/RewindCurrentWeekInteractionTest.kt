package app.logdate.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.logdate.feature.rewind.ui.RewindScreenContent
import app.logdate.feature.rewind.ui.overview.RewindHistoryUiState
import app.logdate.feature.rewind.ui.overview.RewindOverviewPeriod
import app.logdate.feature.rewind.ui.overview.RewindOverviewScreenUiState
import app.logdate.feature.rewind.ui.overview.RewindPreviewUiState
import app.logdate.feature.rewind.ui.overview.RewindSceneMemory
import app.logdate.ui.theme.LogDateTheme
import kotlinx.datetime.LocalDate
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class RewindCurrentWeekInteractionTest {
    @Test
    fun currentWeekCanBeSelectedUpdatedAndOpenedWithoutLosingTheSavedStory() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            val period = mutableStateOf(RewindOverviewPeriod.PreviousWeek)
            val state = mutableStateOf(ready())
            var updates = 0
            var opened: Uuid? = null
            setContent {
                LogDateTheme {
                    RewindScreenContent(
                        state.value,
                        onOpenRewind = { opened = it },
                        selectedPeriod = period.value,
                        onSelectPeriod = { period.value = it },
                        onRefreshCurrentWeek = {
                            updates++
                            state.value = state.value.copy(isRefreshing = true)
                        },
                    )
                }
            }
            onNodeWithContentDescription("Jump to week").performClick()
            onNodeWithText("This week so far").performClick()
            onNodeWithText("Update Rewind").assertDoesNotExist()
            onNodeWithText("Include latest memories").assertDoesNotExist()
            onNodeWithContentDescription("Rewind options").performClick()
            onNodeWithText("Include latest memories").performClick()
            onNodeWithText("Including latest memories…").assertIsDisplayed()
            onNodeWithText("Play Rewind").assertIsDisplayed()
            assertEquals(1, updates)
            runOnUiThread { state.value = state.value.copy(isRefreshing = false, refreshFailed = true) }
            onNodeWithText("Couldn't include the latest memories. Your saved Rewind is still available.").assertIsDisplayed()
            onNodeWithText("Play Rewind").assertIsDisplayed()
            onNodeWithText("Play Rewind").performClick()
            assertEquals(state.value.mostRecentRewind.rewindId, opened)
        }

    @Test
    fun emptyPreviousWeekLeadsToCurrentWeekAndThenMemoryCreation() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            val period = mutableStateOf(RewindOverviewPeriod.PreviousWeek)
            var creations = 0
            setContent {
                LogDateTheme {
                    RewindScreenContent(
                        RewindOverviewScreenUiState.NotReady(emptyList()),
                        onOpenRewind = {},
                        selectedPeriod = period.value,
                        onSelectPeriod = { period.value = it },
                        onCreateMemory = { creations++ },
                    )
                }
            }
            onNodeWithText("This week so far").performClick()
            onNodeWithText("Add a memory").performClick()
            assertEquals(1, creations)
        }

    @Test
    fun weeksRemainScrollableAndOpenTheWeekUnderTheMemory() =
        runDesktopComposeUiTest(width = 1440, height = 1000) {
            val narrow = mutableStateOf(false)
            val olderId = Uuid.parse("00000000-0000-0000-0000-000000000045")
            var opened: Uuid? = null
            setContent {
                LogDateTheme {
                    Box {
                        RewindScreenContent(
                            ready().copy(
                                pastRewinds =
                                    listOf(
                                        RewindHistoryUiState(
                                            uid = olderId,
                                            title = "October 19–25",
                                            label = "Last week",
                                            startDate = LocalDate(2026, 10, 19),
                                            endDate = LocalDate(2026, 10, 25),
                                            message = "",
                                            memories = listOf(RewindSceneMemory.Writing("We spent Saturday on the trail.")),
                                        ),
                                    ),
                            ),
                            onOpenRewind = { opened = it },
                            selectedPeriod = RewindOverviewPeriod.ThisWeek,
                            onSelectPeriod = {},
                            modifier = Modifier.size(if (narrow.value) 411.dp else 1280.dp, if (narrow.value) 891.dp else 800.dp),
                        )
                    }
                }
            }
            onNode(hasScrollAction()).performScrollToNode(hasText("We spent Saturday on the trail."))
            onNodeWithText("We spent Saturday on the trail.").assertIsDisplayed().performClick()
            assertEquals(olderId, opened)
            runOnUiThread { narrow.value = true }
            onNodeWithText("We spent Saturday on the trail.").assertIsDisplayed()
            runOnUiThread { narrow.value = false }
            onNodeWithText("We spent Saturday on the trail.").assertIsDisplayed()
            onNodeWithContentDescription("Jump to week").performClick()
            onNodeWithText("This week so far").performClick()
            onNodeWithText("October 26 – October 28").assertIsDisplayed()
            assertEquals(olderId, opened, "Jumping through weeks must not automatically open playback")
        }

    @Test fun phone() = populatedCapture("phone", 411, 891)

    @Test fun tablet() = populatedCapture("tablet", 1280, 800)

    @Test fun desktop() = populatedCapture("desktop", 1600, 1000)

    @Test fun tabletPortrait() = populatedCapture("tablet-portrait", 800, 1280)

    @Test fun desktopLargeText() = populatedCapture("desktop-large-text", 1440, 900, fontScale = 2f)

    @Test fun darkPhone() = populatedCapture("dark-phone", 411, 891, dark = true)

    @Test fun compactPhone() = populatedCapture("compact-phone", 320, 640)

    @Test fun landscape() = populatedCapture("landscape", 891, 411)

    @Test fun largeText() = populatedCapture("large-text", 411, 891, fontScale = 2f)

    @Test fun textOnlyPhone() = populatedCapture("text-only-phone", 411, 891, textOnly = true)

    @Test fun textOnlyTablet() = populatedCapture("text-only-tablet", 1280, 800, textOnly = true)

    @Test fun textOnlyLargeText() = populatedCapture("text-only-large-text", 411, 891, fontScale = 2f, textOnly = true)

    @Test fun rightToLeft() = populatedCapture("rtl", 411, 891, rtl = true)

    private fun populatedCapture(
        name: String,
        width: Int,
        height: Int,
        dark: Boolean = false,
        fontScale: Float = 1f,
        rtl: Boolean = false,
        textOnly: Boolean = false,
    ) = runDesktopComposeUiTest(width = width, height = height) {
        setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                LogDateTheme(darkTheme = dark) {
                    RewindScreenContent(
                        ready().let { state ->
                            if (textOnly) {
                                state.copy(
                                    mostRecentRewind =
                                        state.mostRecentRewind.copy(
                                            heroImageUri = null,
                                            highlightedQuote = "I took the long way home and stopped by the park.",
                                            memories =
                                                listOf(
                                                    RewindSceneMemory.Writing("I took the long way home and stopped by the park."),
                                                    RewindSceneMemory.Writing("A slower start. The walk was worth keeping."),
                                                ),
                                        ),
                                )
                            } else {
                                state
                            }
                        },
                        onOpenRewind = {},
                        selectedPeriod = RewindOverviewPeriod.ThisWeek,
                        onSelectPeriod = {},
                        onRefreshCurrentWeek = {},
                    )
                }
            }
        }
        onNodeWithText("A little room to breathe").assertIsDisplayed()
        onNodeWithText("3 entries").assertIsDisplayed()
        onNodeWithContentDescription("Rewind options").assertIsDisplayed()
        onNodeWithText("Update Rewind").assertDoesNotExist()
        onNodeWithText("Play Rewind").assertIsDisplayed()
        val outputRoot = System.getenv("LOGDATE_REWIND_CAPTURE_DIR") ?: return@runDesktopComposeUiTest
        mainClock.advanceTimeBy(1000)
        waitForIdle()
        val image = onRoot().captureToImage()
        val pixels = image.toPixelMap()
        val output = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) for (x in 0 until image.width) output.setRGB(x, y, pixels[x, y].toArgb())
        val file = File(outputRoot, "$name.png")
        file.parentFile.mkdirs()
        check(ImageIO.write(output, "png", file))
    }

    private fun ready() =
        RewindOverviewScreenUiState.Ready(
            pastRewinds = emptyList(),
            mostRecentRewind =
                RewindPreviewUiState(
                    rewindId = Uuid.parse("00000000-0000-0000-0000-000000000044"),
                    label = "This week so far",
                    title = "A little room to breathe",
                    message = "A quiet walk and a slower morning.",
                    start = LocalDate(2026, 10, 26),
                    end = LocalDate(2026, 10, 28),
                    rewindAvailable = true,
                    entryCount = 3,
                    photoCount = 1,
                    audioCount = 1,
                    memories =
                        listOf(
                            RewindSceneMemory.Visual(
                                File("../android-main/src/debug/res/drawable/workspace_sample_photo.jpg").toURI().toString(),
                            ),
                            RewindSceneMemory.Writing("I took the long way home and stopped by the park."),
                            RewindSceneMemory.Recording(42000, "A few thoughts from the walk home."),
                        ),
                    heroImageUri = File("../android-main/src/debug/res/drawable/workspace_sample_photo.jpg").toURI().toString(),
                ),
        )
}
