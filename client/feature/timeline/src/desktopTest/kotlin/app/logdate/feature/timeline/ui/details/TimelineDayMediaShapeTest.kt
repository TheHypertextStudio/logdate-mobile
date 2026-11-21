package app.logdate.feature.timeline.ui.details

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import app.logdate.ui.notes.NoteMediaTags
import app.logdate.ui.timeline.ImageNoteUiState
import app.logdate.ui.timeline.TimelineDayUiState
import kotlinx.datetime.LocalDate
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class TimelineDayMediaShapeTest {
    @Test
    fun portraitAndLandscapePhotosKeepTheirOwnShapeOnADay() =
        runComposeUiTest {
            val portrait = photo(width = 900, height = 1600, Color(0x4E, 0x7A, 0x5C))
            val landscape = photo(width = 1600, height = 900, Color(0x8A, 0x6A, 0x4A))
            val at = Instant.parse("2025-02-20T21:20:00Z")
            val day =
                TimelineDayUiState(
                    summary = "Walked the waterfront after work.",
                    date = LocalDate(2025, 2, 20),
                    notes =
                        listOf(
                            ImageNoteUiState(Uuid.random(), portrait.absolutePath, at, caption = "The pier at dusk"),
                            ImageNoteUiState(Uuid.random(), landscape.absolutePath, at + 30.minutes),
                        ),
                )
            setContent {
                MaterialTheme {
                    TimelineDayDetailPanel(uiState = day, onExit = {}, modifier = Modifier.size(412.dp, 1500.dp))
                }
            }
            waitUntil(timeoutMillis = 10_000) {
                onAllNodes(hasTestTag(NoteMediaTags.READY), useUnmergedTree = true).fetchSemanticsNodes().size == 2
            }
            waitForIdle()
            // Set LOGDATE_TIMELINE_RENDER_OUT to a .png path to keep this frame for review sheets.
            System.getenv("LOGDATE_TIMELINE_RENDER_OUT")?.let { out ->
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out))
            }

            val media = onAllNodesWithTag(NoteMediaTags.READY, useUnmergedTree = true)
            val portraitBounds = media[0].getUnclippedBoundsInRoot()
            val landscapeBounds = media[1].getUnclippedBoundsInRoot()
            // A 9:16 camera shot shows at the tallest allowed shape, 3:4, not a 4:3 crop.
            assertEquals(3f / 4f, portraitBounds.width.value / portraitBounds.height.value, 0.01f)
            assertEquals(16f / 9f, landscapeBounds.width.value / landscapeBounds.height.value, 0.01f)
        }

    private fun photo(
        width: Int,
        height: Int,
        color: Color,
    ): File {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            paint = color
            fillRect(0, 0, width, height)
            paint = Color(0xF4, 0xE9, 0xD8)
            // A sun near the top and a horizon line, so a crop is visible in a render.
            fillOval(width / 2 - width / 8, height / 10, width / 4, width / 4)
            fillRect(0, height * 2 / 3, width, height / 60)
            dispose()
        }
        return File.createTempFile("timeline-photo", ".png").apply {
            deleteOnExit()
            ImageIO.write(image, "png", this)
        }
    }
}
