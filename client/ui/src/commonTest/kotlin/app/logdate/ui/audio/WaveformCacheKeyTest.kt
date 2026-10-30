package app.logdate.ui.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class WaveformCacheKeyTest {
    @Test
    fun recordingsWhoseUrisShareAHashCodeGetSeparateWaveforms() {
        val first = "file:///recordings/Aa.m4a"
        val second = "file:///recordings/BB.m4a"
        assertEquals(first.hashCode(), second.hashCode())

        assertNotEquals(waveformCacheKey(first), waveformCacheKey(second))
    }

    @Test
    fun keyIsTheHexSha256OfTheUri() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            waveformCacheKey(""),
        )
        assertEquals(waveformCacheKey("file:///a.m4a"), waveformCacheKey("file:///a.m4a"))
    }
}
