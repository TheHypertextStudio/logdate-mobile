package app.logdate.feature.editor.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import app.logdate.feature.editor.ui.editor.CameraBlockUiState
import org.jetbrains.skia.Image
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.assertTrue

/** Decodes an actual capture URI and measures four image-local fiducials, not its container. */
@OptIn(ExperimentalTestApi::class)
@RunWith(Parameterized::class)
class CapturedPhotoFitTest(
    private val sourceWidth: Int,
    private val sourceHeight: Int,
) {
    @Test(timeout = 60_000)
    fun acceptedPhotoKeepsEveryFiducialAndItsRatioThroughViewportChanges() {
        val file = File.createTempFile("logdate-fit-fiducials-", ".png")
        try {
            val image = BufferedImage(sourceWidth, sourceHeight, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until sourceHeight) {
                for (x in 0 until sourceWidth) {
                    val left = x in sourceWidth / 8 until sourceWidth / 4
                    val right = x in sourceWidth * 3 / 4 until sourceWidth * 7 / 8
                    val top = y in sourceHeight / 8 until sourceHeight / 4
                    val bottom = y in sourceHeight * 3 / 4 until sourceHeight * 7 / 8
                    image.setRGB(
                        x,
                        y,
                        when {
                            left && top -> 0xF02040
                            right && top -> 0x10E060
                            left && bottom -> 0x2840E8
                            right && bottom -> 0xF0D018
                            else -> 0x888888
                        },
                    )
                }
            }
            check(ImageIO.write(image, "png", file))
            runDesktopComposeUiTest(width = 800, height = 800) {
                var viewport by mutableStateOf(400 to 760)
                val block = CameraBlockUiState(uri = file.toURI().toString())
                setContent {
                    MaterialTheme {
                        Box(Modifier.fillMaxSize().background(Color.DarkGray)) {
                            CameraBlockEditor(block, {
                            }, {
                                error(
                                    "Layout must not discard media",
                                )
                            }, modifier = Modifier.requiredSize(viewport.first.dp, viewport.second.dp))
                        }
                    }
                }
                waitUntil(timeoutMillis = 10_000) {
                    onNodeWithContentDescription("Captured photo").fetchSemanticsNode().boundsInRoot.width > 0
                }
                // Wait for the real Coil decode, while preserving a strict rendering assertion.
                waitUntil(timeoutMillis = 10_000) {
                    val center = onNodeWithContentDescription("Captured photo").fetchSemanticsNode().boundsInRoot.center
                    val color = onRoot().captureToImage().toPixelMap()[center.x.toInt(), center.y.toInt()]
                    abs(color.red - 136f / 255) < 0.02f && abs(color.green - 136f / 255) < 0.02f && abs(color.blue - 136f / 255) < 0.02f
                }
                for (size in listOf(400 to 760, 600 to 600, 760 to 400, 320 to 260)) {
                    runOnIdle { viewport = size }
                    waitForIdle()
                    val snapshot = onRoot().captureToImage()
                    System.getProperty("logdate.media.test.evidence")?.let { output ->
                        val destination = File(output, "photo-${sourceWidth}x$sourceHeight-${size.first}x${size.second}.png")
                        destination.parentFile.mkdirs()
                        Image.makeFromBitmap(snapshot.asSkiaBitmap()).use { rendered ->
                            requireNotNull(rendered.encodeToData()).use { encoded -> destination.writeBytes(encoded.bytes) }
                        }
                    }
                    val points = snapshot.toPixelMap().fiducialCenters()
                    assertTrue(points.all { it != null }, "All four original-image fiducials must remain visible at $size: $points")
                    val red = points[0]!!
                    val green = points[1]!!
                    val blue = points[2]!!
                    val dx = abs(green.first - red.first)
                    val dy = abs(blue.second - red.second)
                    val aspect = sourceWidth.toFloat() / sourceHeight
                    // Each rasterized marker center has at most one pixel of rounding uncertainty.
                    assertTrue(abs(dx - aspect * dy) <= 1f + aspect, "Decoded photo must preserve its aspect ratio at $size: ${dx / dy}")
                }
            }
        } finally {
            file.delete()
        }
    }

    private fun PixelMap.fiducialCenters(): List<Pair<Float, Float>?> {
        val sums = Array(4) { FloatArray(3) }
        val targets = listOf(Color(0xFFF02040), Color(0xFF10E060), Color(0xFF2840E8), Color(0xFFF0D018))
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = this[x, y]
                targets.forEachIndexed { index, target ->
                    // The real caption adds a black gradient; hue remains image-local under it.
                    val scale = maxOf(pixel.red, pixel.green, pixel.blue) / maxOf(target.red, target.green, target.blue)
                    if (scale > 0.2f &&
                        scale <= 1.05f &&
                        abs(pixel.red / scale - target.red) < 0.035f &&
                        abs(pixel.green / scale - target.green) < 0.035f &&
                        abs(pixel.blue / scale - target.blue) < 0.035f
                    ) {
                        sums[index][0] += x
                        sums[index][1] += y
                        sums[index][2]++
                    }
                }
            }
        }
        return sums.map { if (it[2] < 3) null else (it[0] / it[2]) to (it[1] / it[2]) }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "image-{0}x{1}")
        fun ratios() = listOf(arrayOf(64, 192), arrayOf(192, 64), arrayOf(128, 128), arrayOf(384, 48))
    }
}
