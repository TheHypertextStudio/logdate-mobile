package app.logdate.feature.editor.ui.camera

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CameraViewfinderFitTest {
    @Test
    fun `a tall phone gives the image all the height between the header and the dock`() {
        val fit = cameraViewfinderFit(412.dp, 915.dp, topReserve = 96.dp, bottomReserve = 56.dp)

        val card = assertIs<CameraViewfinderFit.Card>(fit)
        assertEquals(104.dp, card.top)
        assertEquals(396.dp, card.width)
        assertEquals(747.dp, card.height)
    }

    @Test
    fun `nothing is left between the card and the dock`() {
        val fit = assertIs<CameraViewfinderFit.Card>(cameraViewfinderFit(360.dp, 800.dp, topReserve = 80.dp, bottomReserve = 64.dp))

        assertEquals(800.dp - 64.dp - ViewfinderCardGap, fit.top + fit.height)
    }

    @Test
    fun `a tablet in portrait fills the panel with one card`() {
        val fit = cameraViewfinderFit(800.dp, 1280.dp, topReserve = 96.dp, bottomReserve = 56.dp)

        val card = assertIs<CameraViewfinderFit.Card>(fit)
        assertEquals(784.dp, card.width)
        assertEquals(1112.dp, card.height)
    }

    @Test
    fun `a panel too short for a portrait card goes edge to edge`() {
        assertEquals(CameraViewfinderFit.Overlay, cameraViewfinderFit(915.dp, 412.dp, topReserve = 64.dp, bottomReserve = 56.dp))
        assertEquals(CameraViewfinderFit.Overlay, cameraViewfinderFit(600.dp, 700.dp, topReserve = 64.dp, bottomReserve = 56.dp))
    }
}
