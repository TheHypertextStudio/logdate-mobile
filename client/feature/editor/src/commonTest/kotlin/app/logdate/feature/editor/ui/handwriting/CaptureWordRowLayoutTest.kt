package app.logdate.feature.editor.ui.handwriting

import kotlin.test.Test
import kotlin.test.assertEquals

class CaptureWordRowLayoutTest {
    private val widths = listOf(50, 90, 70, 50, 60)

    @Test
    fun theSelectedWordSitsInTheMiddleOfTheRow() {
        val start = captureWordRowStart(widths, selected = 2, gap = 10, containerWidth = 400)
        // Camera starts after Write, Handwrite and two gaps, and is centered in 400.
        val cameraLeft = start + 50 + 10 + 90 + 10
        assertEquals(200, cameraLeft + 70 / 2)
    }

    @Test
    fun theFirstWordCanSitInTheMiddleWithEmptySpaceBeforeIt() {
        val start = captureWordRowStart(widths, selected = 0, gap = 10, containerWidth = 400)
        assertEquals(175, start)
    }

    @Test
    fun theLastWordCanSitInTheMiddleWithTheRestRunningOffTheLeadingEdge() {
        val start = captureWordRowStart(widths, selected = 4, gap = 10, containerWidth = 400)
        val total = widths.sum() + 4 * 10
        assertEquals(200, start + total - 60 / 2)
    }
}
