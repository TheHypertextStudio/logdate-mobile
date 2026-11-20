package app.logdate.feature.editor.ui.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShutterPressTrackerTest {
    private fun tracker(trailingSign: Float = 1f) =
        ShutterPressTracker(
            lockDistancePx = 100f,
            zoomDoublingDistancePx = 200f,
            trailingSign = trailingSign,
        )

    @Test
    fun `a quick tap takes a photo`() {
        val shutter = tracker()

        assertEquals(emptyList(), shutter.press())
        assertEquals(listOf(ShutterAction.TakePhoto), shutter.release())
    }

    @Test
    fun `holding past the threshold starts recording and releasing stops it`() {
        val shutter = tracker()

        shutter.press()
        assertEquals(listOf(ShutterAction.StartRecording), shutter.holdElapsed())
        assertEquals(listOf(ShutterAction.StopRecording), shutter.release())
    }

    @Test
    fun `the hold timer firing after release does nothing`() {
        val shutter = tracker()

        shutter.press()
        shutter.release()

        assertEquals(emptyList(), shutter.holdElapsed())
    }

    @Test
    fun `sliding toward the trailing edge while recording locks it`() {
        val shutter = tracker()

        shutter.press()
        shutter.holdElapsed()

        assertEquals(listOf(ShutterAction.LockRecording), shutter.drag(dx = 120f, dy = 0f))
        assertEquals(emptyList(), shutter.release())
        assertTrue(shutter.isLocked)
    }

    @Test
    fun `a tap while locked stops the recording`() {
        val shutter = tracker()
        shutter.press()
        shutter.holdElapsed()
        shutter.drag(dx = 120f, dy = 0f)
        shutter.release()

        assertEquals(emptyList(), shutter.press())
        assertEquals(listOf(ShutterAction.StopRecording), shutter.release())
        assertTrue(!shutter.isLocked)
    }

    @Test
    fun `a flick to the lock from rest starts a locked recording without holding`() {
        val shutter = tracker()

        shutter.press()

        assertEquals(
            listOf(ShutterAction.StartRecording, ShutterAction.LockRecording),
            shutter.drag(dx = 110f, dy = 4f),
        )
        assertEquals(emptyList(), shutter.release())
    }

    @Test
    fun `the lock follows the layout direction`() {
        val shutter = tracker(trailingSign = -1f)

        shutter.press()
        shutter.holdElapsed()

        assertEquals(emptyList(), shutter.drag(dx = 120f, dy = 0f))
        assertEquals(listOf(ShutterAction.LockRecording), shutter.drag(dx = -120f, dy = 0f))
    }

    @Test
    fun `sliding up while recording zooms in from where the gesture started`() {
        val shutter = tracker()

        shutter.press()
        shutter.holdElapsed()

        assertEquals(listOf(ShutterAction.Zoom(2f)), shutter.drag(dx = 0f, dy = -200f))
        assertEquals(listOf(ShutterAction.Zoom(1f)), shutter.drag(dx = 0f, dy = 40f))
    }

    @Test
    fun `small movement during a tap still takes a photo`() {
        val shutter = tracker()

        shutter.press()
        assertEquals(emptyList(), shutter.drag(dx = 12f, dy = -8f))
        assertEquals(listOf(ShutterAction.TakePhoto), shutter.release())
    }

    @Test
    fun `a cancelled hold stops the recording`() {
        val shutter = tracker()

        shutter.press()
        shutter.holdElapsed()

        assertEquals(listOf(ShutterAction.StopRecording), shutter.cancel())
    }

    @Test
    fun `a cancelled tap does nothing`() {
        val shutter = tracker()

        shutter.press()

        assertEquals(emptyList(), shutter.cancel())
    }

    @Test
    fun `recording that ends elsewhere resets the tracker`() {
        val shutter = tracker()
        shutter.press()
        shutter.holdElapsed()
        shutter.drag(dx = 120f, dy = 0f)
        shutter.release()

        shutter.recordingEnded()

        shutter.press()
        assertEquals(listOf(ShutterAction.TakePhoto), shutter.release())
    }

    @Test
    fun `a tap stops a recording that started elsewhere`() {
        val shutter = tracker()

        shutter.recordingStartedElsewhere()

        assertEquals(emptyList(), shutter.press())
        assertEquals(listOf(ShutterAction.StopRecording), shutter.release())
    }
}
