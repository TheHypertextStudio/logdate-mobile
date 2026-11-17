package app.logdate.client.location.tracking

import app.logdate.client.location.settings.LocationCaptureMode
import app.logdate.client.location.settings.LocationTrackingSettings
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Unit tests for computing location tracking execution decisions based on user settings.
 *
 * These tests verify that different [LocationCaptureMode] settings correctly trigger the
 * appropriate start and stop actions for various tracking channels (e.g., scheduled,
 * optimized background, and activity-aware tracking).
 */
class LocationTrackingExecutionDecisionTest {
    @Test
    fun `disabled background tracking stops everything`() {
        val decision =
            computeLocationTrackingExecutionDecision(
                settings = LocationTrackingSettings(backgroundTrackingEnabled = false),
            )

        assertTrue(decision.shouldStopScheduledTracking)
        assertTrue(decision.shouldStopOptimizedBackgroundTracking)
        assertTrue(decision.shouldStopActivityAwareTracking)
        assertFalse(decision.shouldStartScheduledTracking)
        assertFalse(decision.shouldStartOptimizedBackgroundTracking)
        assertFalse(decision.shouldStartActivityAwareTracking)
    }

    @Test
    fun `active mode starts all three tracking channels`() {
        val decision =
            computeLocationTrackingExecutionDecision(
                settings =
                    LocationTrackingSettings(
                        backgroundTrackingEnabled = true,
                        captureMode = LocationCaptureMode.ACTIVE,
                    ),
            )

        assertTrue(decision.shouldStartScheduledTracking)
        assertTrue(decision.shouldStartOptimizedBackgroundTracking)
        assertTrue(decision.shouldStartActivityAwareTracking)
        assertFalse(decision.shouldStopScheduledTracking)
        assertFalse(decision.shouldStopOptimizedBackgroundTracking)
        assertFalse(decision.shouldStopActivityAwareTracking)
    }

    @Test
    fun `passive mode stops activity-aware and optimized tracking`() {
        val decision =
            computeLocationTrackingExecutionDecision(
                settings =
                    LocationTrackingSettings(
                        backgroundTrackingEnabled = true,
                        captureMode = LocationCaptureMode.PASSIVE,
                    ),
            )

        assertTrue(decision.shouldStartScheduledTracking)
        assertTrue(decision.shouldStopOptimizedBackgroundTracking)
        assertTrue(decision.shouldStopActivityAwareTracking)
        assertFalse(decision.shouldStartOptimizedBackgroundTracking)
        assertFalse(decision.shouldStartActivityAwareTracking)
    }

    @Test
    fun `periodic samples pause only while the active stream is delivering fixes`() {
        val active =
            LocationTrackingSettings(
                backgroundTrackingEnabled = true,
                captureMode = LocationCaptureMode.ACTIVE,
                minimumPersistIntervalMinutes = 30,
            )
        val passive = active.copy(captureMode = LocationCaptureMode.PASSIVE)
        val now = Instant.parse("2026-10-02T12:00:00Z")

        assertFalse(shouldRecordPeriodicSample(active, now - 4.minutes, now))
        assertTrue(shouldRecordPeriodicSample(active, now - 45.minutes, now))
        assertTrue(shouldRecordPeriodicSample(active, null, now))
        assertTrue(shouldRecordPeriodicSample(passive, now - 4.minutes, now))
    }
}
