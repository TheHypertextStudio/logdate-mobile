package app.logdate.client.location.tracking

import com.google.android.gms.location.Priority
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocationProfileAndEvidenceTest {
    @Test
    fun `all four location profiles disable waitForAccurateLocation and enforce expected cadences`() {
        val profiles =
            listOf(
                LocationProfile.STILL,
                LocationProfile.ON_FOOT,
                LocationProfile.CYCLING,
                LocationProfile.IN_VEHICLE,
            )
        profiles.forEach { profile ->
            val request = profile.toLocationRequest()
            assertFalse(request.isWaitForAccurateLocation, "Profile $profile must not wait indefinitely indoors")
            assertEquals(profile.priority, request.priority)
            assertEquals(profile.intervalMillis, request.intervalMillis)
            assertEquals(profile.minIntervalMillis, request.minUpdateIntervalMillis)
            assertEquals(profile.displacementMeters, request.minUpdateDistanceMeters)
            assertEquals(profile.maxDelayMillis, request.maxUpdateDelayMillis)
        }
        assertEquals(Priority.PRIORITY_HIGH_ACCURACY, LocationProfile.CYCLING.priority)
        assertEquals(25f, LocationProfile.CYCLING.displacementMeters)
    }

    @Test
    fun `capture validity and elapsed realtime freshness reject stale and invalid fixes`() {
        assertTrue(isValidCaptureSample(37.7749, -122.4194, hasAccuracy = true, accuracyMeters = 15f))
        assertFalse(isValidCaptureSample(Double.NaN, -122.4194, hasAccuracy = true, accuracyMeters = 15f))
        assertFalse(isValidCaptureSample(95.0, 0.0, hasAccuracy = true, accuracyMeters = 15f))
        assertFalse(isValidCaptureSample(37.7749, -122.4194, hasAccuracy = true, accuracyMeters = -1f))
        assertFalse(isValidCaptureSample(0.0, 0.0, hasAccuracy = false, accuracyMeters = null))
        assertFalse(isValidCaptureSample(0.0, 0.0, hasAccuracy = true, accuracyMeters = 1500f))
        assertTrue(isValidCaptureSample(0.0, 0.0, hasAccuracy = true, accuracyMeters = 15f))

        val nowNanos = 1_000_000_000_000L
        assertTrue(isFreshElapsedNanos(nowNanos - 30_000_000_000L, nowNanos, maxAgeMs = 60_000L))
        assertFalse(isFreshElapsedNanos(nowNanos - 90_000_000_000L, nowNanos, maxAgeMs = 60_000L))
        assertTrue(isFreshElapsedNanos(nowNanos - 90_000_000_000L, nowNanos, maxAgeMs = 300_000L))
        assertFalse(isFreshElapsedNanos(nowNanos - 400_000_000_000L, nowNanos, maxAgeMs = 300_000L))
        assertFalse(isFreshElapsedNanos(nowNanos + 10_000_000_000L, nowNanos, maxAgeMs = 60_000L))
    }
}
