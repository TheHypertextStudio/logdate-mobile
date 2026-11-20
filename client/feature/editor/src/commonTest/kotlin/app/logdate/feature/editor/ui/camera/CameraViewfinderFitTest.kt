package app.logdate.feature.editor.ui.camera

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CameraViewfinderFitTest {
    @Test
    fun `a tall phone fits a full-width 3 by 4 card under the header`() {
        val fit = cameraViewfinderFit(412.dp, 915.dp, topReserve = 96.dp, bottomReserve = 200.dp, ratio = 3f / 4f)

        val card = assertIs<CameraViewfinderFit.Card>(fit)
        assertEquals(104.dp, card.top)
        assertEquals(396.dp, card.width)
        assertEquals(528.dp, card.height)
    }

    @Test
    fun `a short phone goes edge to edge instead of shrinking the image to a stamp`() {
        val fit = cameraViewfinderFit(360.dp, 640.dp, topReserve = 96.dp, bottomReserve = 200.dp, ratio = 3f / 4f)

        assertEquals(CameraViewfinderFit.Overlay, fit)
    }

    @Test
    fun `a tablet narrows the card a little instead of covering the image`() {
        val fit = cameraViewfinderFit(800.dp, 1280.dp, topReserve = 96.dp, bottomReserve = 200.dp, ratio = 3f / 4f)

        val card = assertIs<CameraViewfinderFit.Card>(fit)
        assertEquals(968.dp, card.height)
        assertEquals(726.dp, card.width)
    }

    @Test
    fun `a short landscape panel goes edge to edge`() {
        val fit = cameraViewfinderFit(915.dp, 412.dp, topReserve = 64.dp, bottomReserve = 160.dp, ratio = 3f / 4f)

        assertEquals(CameraViewfinderFit.Overlay, fit)
    }
}
