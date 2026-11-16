package app.logdate.ui.motion

import androidx.compose.ui.geometry.Offset
import app.logdate.client.sensor.GyroOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeviceParallaxTest {
    private val still = GyroOffset(0f, 0f)

    @Test
    fun `turning the device shifts the layers along the matching axis`() {
        val turnedSideways = nextParallax(Offset.Zero, GyroOffset(x = 0f, y = 1f), seconds = 0.02f)
        assertTrue(turnedSideways.x > 0f)
        assertEquals(0f, turnedSideways.y)

        val tippedBack = nextParallax(Offset.Zero, GyroOffset(x = -1f, y = 0f), seconds = 0.02f)
        assertTrue(tippedBack.y < 0f)
        assertEquals(0f, tippedBack.x)
    }

    @Test
    fun `a device held still lets the layers drift back to rest`() {
        var offset = Offset(0.8f, -0.6f)
        repeat(500) { offset = nextParallax(offset, still, seconds = 0.02f) }
        assertTrue(offset.getDistance() < 0.01f, "still $offset after ten seconds")
    }

    @Test
    fun `the layers never move past their limit`() {
        var offset = Offset.Zero
        repeat(200) { offset = nextParallax(offset, GyroOffset(x = 9f, y = -9f), seconds = 0.02f) }
        assertEquals(Offset(-1f, 1f), offset)
    }

    @Test
    fun `a late reading counts for no more than a tenth of a second`() {
        val late = nextParallax(Offset.Zero, GyroOffset(x = 0f, y = 1f), seconds = 5f)
        val capped = nextParallax(Offset.Zero, GyroOffset(x = 0f, y = 1f), seconds = 0.1f)
        assertEquals(capped, late)
    }
}
