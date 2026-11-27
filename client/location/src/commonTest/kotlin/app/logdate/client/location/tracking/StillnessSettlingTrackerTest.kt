package app.logdate.client.location.tracking

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class StillnessSettlingTrackerTest {
    private val base = Instant.parse("2026-10-12T16:00:00Z")

    @Test
    fun `three stationary fixes step down to STILL and five minutes emit geofence anchor`() {
        val tracker = StillnessSettlingTracker()

        val first = tracker.onFix(37.7749, -122.4194, 40f, 0.1f, base)
        assertFalse(first.enterStill)
        assertNull(first.registerExitGeofenceAt)

        val second = tracker.onFix(37.77492, -122.4194, 40f, 0.2f, base + 20.seconds)
        assertFalse(second.enterStill)

        // Accurate 3rd fix weighs the anchor heavily toward its coordinate.
        val third = tracker.onFix(37.7750, -122.4194, 5f, 0.0f, base + 40.seconds)
        assertTrue(third.enterStill)
        assertNull(third.registerExitGeofenceAt)

        val fiveMinutesLater = tracker.onFix(37.7750, -122.4194, 6f, 0.0f, base + 5.minutes + 10.seconds)
        val anchor = assertNotNull(fiveMinutesLater.registerExitGeofenceAt)
        assertEquals(37.7750, anchor.first, 0.0001)
        assertEquals(-122.4194, anchor.second, 0.0001)
    }

    @Test
    fun `fast speed or displacement beyond 100m wakes to moving profile`() {
        val tracker = StillnessSettlingTracker()
        tracker.onFix(37.7749, -122.4194, 10f, 0.0f, base)
        tracker.onFix(37.7749, -122.4194, 10f, 0.0f, base + 20.seconds)
        assertTrue(tracker.onFix(37.7749, -122.4194, 10f, 0.0f, base + 40.seconds).enterStill)

        // Coarse >50m accuracy jump does not false-wake.
        val coarseDrift = tracker.onFix(37.7770, -122.4194, 120f, 0.2f, base + 60.seconds)
        assertNull(coarseDrift.wakeToProfileName)

        // Precise displacement >100m wakes to ON_FOOT.
        val walkWake = tracker.onFix(37.7765, -122.4194, 15f, 1.4f, base + 90.seconds)
        assertEquals("ON_FOOT", walkWake.wakeToProfileName)

        // Re-settle and verify highway speed wakes directly to IN_VEHICLE.
        tracker.onFix(37.7765, -122.4194, 10f, 0.0f, base + 2.minutes)
        tracker.onFix(37.7765, -122.4194, 10f, 0.0f, base + 2.minutes + 20.seconds)
        assertTrue(tracker.onFix(37.7765, -122.4194, 10f, 0.0f, base + 2.minutes + 40.seconds).enterStill)
        val vehicleWake = tracker.onFix(37.7766, -122.4194, 10f, 12.0f, base + 3.minutes)
        assertEquals("IN_VEHICLE", vehicleWake.wakeToProfileName)
    }
}
