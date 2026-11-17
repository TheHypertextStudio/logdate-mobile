package app.logdate.wear.haptic

import android.os.VibrationEffect
import android.os.Vibrator
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Tests [WearHapticEngine]: every cue the watch plays reaches the vibrator. */
class WearHapticEngineTest {
    private lateinit var vibrator: Vibrator
    private lateinit var engine: WearHapticEngine
    private val mockEffect: VibrationEffect = mockk()

    @Before
    fun setUp() {
        mockkStatic(VibrationEffect::class)
        every { VibrationEffect.createOneShot(any(), any()) } returns mockEffect
        every { VibrationEffect.createWaveform(any(), any<IntArray>(), any()) } returns mockEffect

        vibrator = mockk(relaxed = true)
        engine = WearHapticEngine(vibrator)
    }

    @After
    fun tearDown() {
        unmockkStatic(VibrationEffect::class)
    }

    @Test
    fun `every cue vibrates once`() {
        engine.startRecording()
        engine.stopRecording()
        engine.pause()
        engine.resume()
        engine.rejection()
        engine.success()
        engine.warning()

        verify(exactly = 7) { vibrator.vibrate(any<VibrationEffect>()) }
    }
}
