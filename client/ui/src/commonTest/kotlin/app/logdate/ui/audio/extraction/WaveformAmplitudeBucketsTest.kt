package app.logdate.ui.audio.extraction

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WaveformAmplitudeBucketsTest {
    @Test
    fun `later loud audio never rescales a published quiet bucket`() {
        val buckets = WaveformAmplitudeBuckets(3)
        buckets.add(0, 8192.0 * 8192, 1)
        buckets.add(1, 16384.0 * 16384, 1)
        val early = buckets.snapshot()[0]
        buckets.add(2, 32767.0 * 32767, 1)
        assertEquals(early, buckets.snapshot()[0])
        assertEquals(early, buckets.snapshot(final = true)[0])
        assertTrue(early > 0f)
    }

    @Test
    fun `unfinished bucket appears only after its PCM samples are complete`() {
        val buckets = WaveformAmplitudeBuckets(3)
        buckets.add(0, 100.0 * 100, 1)
        assertEquals(listOf(0f, 0f, 0f), buckets.snapshot())
        buckets.add(0, 1000.0 * 1000, 1)
        buckets.add(1, 100.0 * 100, 1)
        val completeFirst = buckets.snapshot()[0]
        buckets.add(1, 32767.0 * 32767, 1)
        assertEquals(completeFirst, buckets.snapshot()[0])
        assertEquals(0f, buckets.snapshot()[1])
        assertTrue(buckets.snapshot(final = true)[1] > 0f)
    }

    @Test
    fun `silence and full scale stay finite and bounded`() {
        val buckets = WaveformAmplitudeBuckets(2)
        buckets.add(0, 0.0, 10)
        buckets.add(1, 32768.0 * 32768 * 10, 10)
        assertEquals(listOf(0f, 1f), buckets.snapshot(final = true))
    }
}
