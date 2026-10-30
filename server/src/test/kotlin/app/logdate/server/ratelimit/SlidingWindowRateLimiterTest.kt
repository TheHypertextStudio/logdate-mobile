package app.logdate.server.ratelimit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [SlidingWindowRateLimiter], ensuring that request quotas are
 * correctly enforced over a rolling time window.
 *
 * This suite verifies basic allowance logic, key-based isolation, window sliding
 * behavior, and the accurate calculation of retry-after wait times.
 */
class SlidingWindowRateLimiterTest {
    private val policy = RateLimitPolicy(maxRequests = 3, windowSeconds = 60)

    @Test
    fun `allows requests up to the ceiling then denies the rest`() {
        val limiter = SlidingWindowRateLimiter()
        assertTrue(limiter.allow("alice", policy, nowEpochMillis = 0))
        assertTrue(limiter.allow("alice", policy, nowEpochMillis = 10))
        assertTrue(limiter.allow("alice", policy, nowEpochMillis = 20))
        assertFalse(limiter.allow("alice", policy, nowEpochMillis = 30))
    }

    @Test
    fun `independent keys do not share quota`() {
        val limiter = SlidingWindowRateLimiter()
        repeat(3) { assertTrue(limiter.allow("alice", policy, nowEpochMillis = it.toLong())) }
        // Alice exhausted — bob still gets her whole window.
        assertTrue(limiter.allow("bob", policy, nowEpochMillis = 100))
    }

    @Test
    fun `window slides forward to release old requests`() {
        val limiter = SlidingWindowRateLimiter()
        (1..3).forEach { i -> assertTrue(limiter.allow("alice", policy, nowEpochMillis = i.toLong())) }
        assertFalse(limiter.allow("alice", policy, nowEpochMillis = 4))

        // Move time past the window: first entry at t=1 falls out at t=61_001.
        assertTrue(limiter.allow("alice", policy, nowEpochMillis = 61_001))
    }

    @Test
    fun `retryAfterSeconds is zero when no bucket exists yet`() {
        val limiter = SlidingWindowRateLimiter()
        assertEquals(0L, limiter.retryAfterSeconds("fresh", policy))
    }

    @Test
    fun `retryAfterSeconds returns the rounded-up wait for the oldest entry`() {
        val limiter = SlidingWindowRateLimiter()
        limiter.allow("alice", policy, nowEpochMillis = 0)
        limiter.allow("alice", policy, nowEpochMillis = 10)
        limiter.allow("alice", policy, nowEpochMillis = 20)
        // The oldest request is at t=0; window is 60s; at t=30_000 we have 30s to wait.
        assertEquals(30L, limiter.retryAfterSeconds("alice", policy, nowEpochMillis = 30_000))
    }

    @Test
    fun `idle keys are swept so they don't stay in memory`() {
        val limiter = SlidingWindowRateLimiter()
        repeat(1_023) { assertTrue(limiter.allow("visitor-$it", policy, nowEpochMillis = 0)) }
        assertEquals(1_023, limiter.trackedKeys())

        // The 1,024th call sweeps; every earlier window has emptied by then.
        assertTrue(limiter.allow("latest", policy, nowEpochMillis = 61_000))

        assertEquals(1, limiter.trackedKeys())
    }

    @Test
    fun `concurrent requests never pass the ceiling`() =
        runBlocking {
            val limiter = SlidingWindowRateLimiter()
            val allowed =
                (1..200)
                    .map { async(Dispatchers.Default) { limiter.allow("shared", policy, nowEpochMillis = 5) } }
                    .awaitAll()
                    .count { it }
            assertEquals(policy.maxRequests, allowed)
        }
}
