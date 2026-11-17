package app.logdate.client.sync.metadata

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [SyncBackoff].
 *
 * Verifies the exponential backoff strategy used for sync retries, ensuring
 * that the delay ladder matches the documented schedule and that jitter is
 * correctly applied to prevent thundering herd problems.
 */
class SyncBackoffTest {
    @Test
    fun `base ladder matches the documented schedule`() {
        val backoff = SyncBackoff(jitterFraction = 0.0)
        assertEquals(1_000L, backoff.baseDelayMs(1))
        assertEquals(2_000L, backoff.baseDelayMs(2))
        assertEquals(4_000L, backoff.baseDelayMs(3))
        assertEquals(8_000L, backoff.baseDelayMs(4))
        assertEquals(16_000L, backoff.baseDelayMs(5))
        assertEquals(30_000L, backoff.baseDelayMs(6))
        assertEquals(5 * 60_000L, backoff.baseDelayMs(7))
        assertEquals(30 * 60_000L, backoff.baseDelayMs(8))
        assertEquals(60 * 60_000L, backoff.baseDelayMs(9))
        assertEquals(60 * 60_000L, backoff.baseDelayMs(99), "attempts past 9 stay capped at 1h")
    }

    @Test
    fun `retry count is clamped to at least 1`() {
        val backoff = SyncBackoff(jitterFraction = 0.0)
        assertEquals(1_000L, backoff.baseDelayMs(0))
        assertEquals(1_000L, backoff.baseDelayMs(-5))
    }

    @Test
    fun `zero jitter returns the base delay exactly`() {
        val backoff = SyncBackoff(jitterFraction = 0.0)
        assertEquals(8_000L, backoff.nextDelayMs(4))
    }

    @Test
    fun `jittered delay stays within the expected +- band`() {
        val base = 10_000L // matches no exact ladder entry; only used to read the band.
        val backoff = SyncBackoff(jitterFraction = 0.2, random = Random(seed = 42))
        val samples =
            (1..100).map {
                backoff.nextDelayMs(3) // base 4_000
            }
        // With ±20% jitter on a 4s base, samples must fall within [3200, 4800].
        samples.forEach { sample ->
            assertTrue(sample in 3_200L..4_800L, "sample $sample outside ±20% of 4000")
        }
    }

    @Test
    fun `jitter is deterministic when the same random is reused`() {
        val a = SyncBackoff(random = Random(seed = 7)).nextDelayMs(5)
        val b = SyncBackoff(random = Random(seed = 7)).nextDelayMs(5)
        assertEquals(a, b)
    }
}
