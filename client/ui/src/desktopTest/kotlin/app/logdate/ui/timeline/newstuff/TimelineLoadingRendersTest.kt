package app.logdate.ui.timeline.newstuff

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import app.logdate.ui.common.formatting.LocalToday
import app.logdate.ui.platform.LocalReduceMotionOverride
import app.logdate.ui.theme.LogDateTheme
import app.logdate.ui.timeline.ImageNoteUiState
import app.logdate.ui.timeline.TextNoteUiState
import app.logdate.ui.timeline.TimelineDayUiState
import app.logdate.ui.timeline.TimelineLoadingState
import app.logdate.ui.timeline.TimelinePane
import app.logdate.ui.timeline.TimelineUiState
import app.logdate.ui.workspace.WorkspaceDestination
import app.logdate.ui.workspace.WorkspaceScaffold
import kotlinx.datetime.LocalDate
import org.junit.Test
import java.awt.Color
import java.awt.GradientPaint
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Optional output uses real production components; these are offscreen renders, not device captures. */
@OptIn(ExperimentalTestApi::class)
class TimelineLoadingRendersTest {
    @Test fun phone() = render("phone", 411, 891)

    @Test fun foldableSized() = render("foldable-sized", 720, 960)

    @Test fun landscapeTablet() = render("landscape-tablet", 1280, 800)

    @Test fun largeTextAndRtl() = render("phone-large-text-rtl", 411, 891, 2f, LayoutDirection.Rtl)

    private fun render(
        name: String,
        width: Int,
        height: Int,
        scale: Float = 1f,
        direction: LayoutDirection = LayoutDirection.Ltr,
    ) {
        val directory = System.getenv("LOGDATE_TIMELINE_SCREENSHOT_DIR") ?: return
        for (loading in listOf(false, true)) {
            runDesktopComposeUiTest(width = width, height = height) {
                val fixture = fixture(directory)
                setContent {
                    CompositionLocalProvider(
                        LocalDensity provides Density(1f, scale),
                        LocalLayoutDirection provides direction,
                        LocalReduceMotionOverride provides true,
                        LocalToday provides LocalDate(2026, 10, 28),
                    ) {
                        LogDateTheme(darkTheme = true, dynamicColor = false) {
                            WorkspaceScaffold(
                                listOf(
                                    WorkspaceDestination("timeline", "Timeline", Icons.Default.History),
                                    WorkspaceDestination("journals", "Journals", Icons.Default.Book),
                                    WorkspaceDestination("library", "Library", Icons.Default.PhotoLibrary),
                                    WorkspaceDestination("rewind", "Rewind", Icons.Default.CalendarMonth),
                                ),
                                "timeline",
                                {},
                                onCreate = {},
                                onSearch = {},
                            ) {
                                TimelinePane(
                                    TimelineUiState(
                                        if (loading) emptyList() else fixture,
                                        if (loading) TimelineLoadingState.InitialLoading else TimelineLoadingState.Loaded,
                                    ),
                                    onNewEntry = {},
                                    onOpenDay = {},
                                )
                            }
                        }
                    }
                }
                waitForIdle()
                val image = onRoot().captureToImage()
                val pixels = image.toPixelMap()
                val output = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
                for (y in 0 until image.height) for (x in 0 until image.width) output.setRGB(x, y, pixels[x, y].toArgb())
                ImageIO.write(output, "png", File(directory, "$name-${if (loading) "loading" else "populated"}.png"))
            }
        }
    }

    private fun fixture(directory: String): List<TimelineDayUiState> {
        val file = File(directory, "synthetic-landscape-fixture.png")
        file.parentFile.mkdirs()
        if (!file.exists()) {
            val image = BufferedImage(640, 360, BufferedImage.TYPE_INT_RGB)
            val graphics = image.createGraphics()
            graphics.paint = GradientPaint(0f, 0f, Color(224, 173, 138), 0f, 360f, Color(62, 97, 112))
            graphics.fillRect(0, 0, 640, 360)
            graphics.color = Color(49, 69, 66)
            graphics.fillPolygon(intArrayOf(0, 180, 370, 500, 640), intArrayOf(360, 140, 300, 175, 360), 5)
            graphics.dispose()
            ImageIO.write(image, "png", file)
        }
        val at = Instant.parse("2026-10-28T18:30:00Z")
        return listOf(
            TimelineDayUiState(
                summary = "A quiet afternoon outdoors, then a few notes to carry into tomorrow.",
                supportingSummary = "A quiet afternoon outdoors, then a few notes to carry into tomorrow.",
                date = LocalDate(2026, 10, 28),
                notes =
                    listOf(
                        TextNoteUiState(
                            Uuid.parse("00000000-0000-0000-0000-000000000001"),
                            "The light changed just as I reached the trail. I want to remember how still everything felt.",
                            at,
                        ),
                        ImageNoteUiState(
                            Uuid.parse("00000000-0000-0000-0000-000000000002"),
                            file.toURI().toString(),
                            at - kotlin.time.Duration.parse("15m"),
                        ),
                        TextNoteUiState(
                            Uuid.parse("00000000-0000-0000-0000-000000000003"),
                            "An earlier thought remains in the same day’s history.",
                            at - kotlin.time.Duration.parse("1h"),
                        ),
                    ),
            ),
            TimelineDayUiState(
                summary = "",
                date = LocalDate(2026, 10, 27),
                notes =
                    listOf(
                        TextNoteUiState(
                            Uuid.parse("00000000-0000-0000-0000-000000000004"),
                            "Made time for a long conversation and a slow walk home.",
                            at - kotlin.time.Duration.parse("24h"),
                        ),
                    ),
            ),
        )
    }
}
