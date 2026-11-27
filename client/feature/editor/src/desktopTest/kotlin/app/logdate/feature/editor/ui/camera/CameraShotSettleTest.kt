package app.logdate.feature.editor.ui.camera

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import app.logdate.feature.editor.ui.editor.CameraBlockUiState
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class CameraShotSettleTest {
    @Test(timeout = 60_000)
    fun aNewShotSettlesFromTheViewfinderFrameIntoItsCard() {
        val photo = File.createTempFile("logdate-settle-", ".png").apply { deleteOnExit() }
        val image = BufferedImage(900, 1600, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            paint = Color(0x4E, 0x7A, 0x5C)
            fillRect(0, 0, 900, 1600)
            paint = Color(0xF4, 0xE9, 0xD8)
            fillOval(340, 160, 220, 220)
            fillRect(0, 1060, 900, 26)
            dispose()
        }
        ImageIO.write(image, "png", photo)
        runDesktopComposeUiTest(width = 360, height = 640) {
            var block by mutableStateOf(CameraBlockUiState())
            setContent {
                MaterialTheme {
                    CameraBlockEditor(block, { block = it }, {}, modifier = Modifier.requiredSize(360.dp, 640.dp))
                }
            }
            waitForIdle()
            // Set LOGDATE_CAMERA_SETTLE_FRAMES to a directory to keep the frames for review sheets.
            val frames = System.getenv("LOGDATE_CAMERA_SETTLE_FRAMES")?.let(::File)?.apply { mkdirs() }

            fun keep(name: String) = frames?.let { ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "$name.png")) }
            keep("000")
            mainClock.autoAdvance = false
            runOnIdle { block = block.copy(uri = photo.toURI().toString()) }
            var elapsed = 0L
            var settling = 0f
            for (step in listOf(32L, 32L, 32L, 64L, 96L, 160L, 240L)) {
                mainClock.advanceTimeBy(step)
                elapsed += step
                val width = onNodeWithContentDescription("Captured photo").fetchSemanticsNode().boundsInRoot.width
                if (elapsed <= 96) settling = maxOf(settling, width)
                keep("%03d".format(elapsed))
            }
            mainClock.autoAdvance = true
            waitForIdle()
            val settled = onNodeWithContentDescription("Captured photo").fetchSemanticsNode().boundsInRoot.width
            assertTrue(settling > settled + 1f, "The shot starts at the viewfinder's frame ($settling) and settles to its card ($settled)")
        }
    }
}
