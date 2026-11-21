package app.logdate.ui.notes

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ProportionalNoteMediaTest {
    @Test
    fun portraitCameraShotsKeepMostOfTheirHeight() {
        // A 9:16 shot is shown at the tallest allowed shape, 3:4, instead of a 4:3 crop that
        // would keep only its middle third.
        assertEquals(3f / 4f, mediaAspectRatio(Size(1080f, 1920f)))
    }

    @Test
    fun landscapeAndSquareMediaKeepTheirOwnShape() {
        assertEquals(16f / 9f, mediaAspectRatio(Size(1920f, 1080f)))
        assertEquals(1f, mediaAspectRatio(Size(1000f, 1000f)))
        assertEquals(2f, mediaAspectRatio(Size(3000f, 1000f)))
    }

    @Test
    fun unknownSizesHaveNoRatio() {
        assertEquals(null, mediaAspectRatio(Size(0f, 0f)))
    }

    @Test
    fun aLoadedPortraitPhotoTakesItsOwnShapeAcrossTheWidth() =
        runComposeUiTest {
            val photo = portraitPhoto()
            setContent {
                MaterialTheme {
                    ProportionalNoteMedia(
                        mediaRef = photo.absolutePath,
                        kind = NoteMediaKind.Photo,
                        contentDescription = "Morning walk",
                        modifier = Modifier.width(240.dp),
                    )
                }
            }
            waitUntil(timeoutMillis = 10_000) {
                onAllNodes(hasTestTag(NoteMediaTags.READY))
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            waitForIdle()
            val bounds = onNodeWithTag(NoteMediaTags.READY).getBoundsInRoot()
            assertEquals(240f, bounds.width.value, 0.5f)
            assertEquals(320f, bounds.height.value, 0.5f)
        }

    private fun portraitPhoto(): File {
        val file = File.createTempFile("portrait", ".png").apply { deleteOnExit() }
        ImageIO.write(BufferedImage(90, 160, BufferedImage.TYPE_INT_RGB), "png", file)
        return file
    }
}
