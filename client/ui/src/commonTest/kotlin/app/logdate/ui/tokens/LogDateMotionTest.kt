package app.logdate.ui.tokens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the springs both apps move with. A change here changes how every LogDate surface feels, so
 * it should be a deliberate one.
 */
class LogDateMotionTest {
    @Test
    fun `quick never overshoots and is stiffer than expressive`() {
        val quick = LogDateMotion.quick<Float>()

        assertEquals(1f, quick.dampingRatio)
        assertTrue(quick.stiffness > LogDateMotion.expressive<Float>().stiffness)
    }

    @Test
    fun `expressive is the only spring with a visible bounce`() {
        val expressive = LogDateMotion.expressive<Float>()

        assertEquals(0.75f, expressive.dampingRatio)
        assertTrue(expressive.dampingRatio < LogDateMotion.quick<Float>().dampingRatio)
    }
}
