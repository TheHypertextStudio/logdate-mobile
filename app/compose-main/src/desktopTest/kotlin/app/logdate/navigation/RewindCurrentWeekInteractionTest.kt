package app.logdate.navigation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import app.logdate.feature.rewind.ui.RewindScreenContent
import app.logdate.feature.rewind.ui.overview.RewindOverviewPeriod
import app.logdate.feature.rewind.ui.overview.RewindOverviewScreenUiState
import app.logdate.feature.rewind.ui.overview.RewindPreviewUiState
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
            onNodeWithContentDescription("Choose section").performClick()
            onNodeWithText("This week so far").performClick()
            onNodeWithText("Update Rewind").performClick()
            onNodeWithText("Updating…").assertIsDisplayed()
            onNodeWithText("This week's story").assertIsDisplayed()
            assertEquals(1, updates)
            runOnUiThread { state.value = state.value.copy(isRefreshing = false, refreshFailed = true) }
            onNodeWithText("Your saved Rewind is still here. The update couldn't be finished. Try again.").assertIsDisplayed()
            onNodeWithText("This week's story").assertIsDisplayed()
            onNodeWithText("This week's story").performClick()
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

    @Test fun phone() = populatedCapture("phone", 411, 891)

    @Test fun tablet() = populatedCapture("tablet", 1280, 800)

    @Test fun darkPhone() = populatedCapture("dark-phone", 411, 891, dark = true)

    @Test fun compactPhone() = populatedCapture("compact-phone", 320, 640)

    @Test fun landscape() = populatedCapture("landscape", 891, 411)

    @Test fun largeText() = populatedCapture("large-text", 411, 891, fontScale = 2f)

    @Test fun rightToLeft() = populatedCapture("rtl", 411, 891, rtl = true)

    private fun populatedCapture(
        name: String,
        width: Int,
        height: Int,
        dark: Boolean = false,
        fontScale: Float = 1f,
        rtl: Boolean = false,
    ) = runDesktopComposeUiTest(width = width, height = height) {
        setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                LogDateTheme(darkTheme = dark) {
                    RewindScreenContent(
                        ready(),
                        onOpenRewind = {},
                        selectedPeriod = RewindOverviewPeriod.ThisWeek,
                        onSelectPeriod = {},
                        onRefreshCurrentWeek = {},
                    )
                }
            }
        }
        onNodeWithText("Update Rewind").assertIsDisplayed()
        onNodeWithText("This week's story").assertIsDisplayed()
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
                    heroImageUri = File("../android-main/src/debug/res/drawable/workspace_sample_photo.jpg").toURI().toString(),
                ),
        )
}
