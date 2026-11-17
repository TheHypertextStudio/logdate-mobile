package app.logdate.client.location.tracking

import app.logdate.client.location.settings.LocationCaptureMode
import app.logdate.client.location.settings.LocationTrackingSettings
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

internal data class LocationTrackingExecutionDecision(
    val shouldStartScheduledTracking: Boolean,
    val shouldStopScheduledTracking: Boolean,
    val shouldStartOptimizedBackgroundTracking: Boolean,
    val shouldStopOptimizedBackgroundTracking: Boolean,
    val shouldStartActivityAwareTracking: Boolean,
    val shouldStopActivityAwareTracking: Boolean,
)

internal fun computeLocationTrackingExecutionDecision(settings: LocationTrackingSettings): LocationTrackingExecutionDecision {
    if (!settings.backgroundTrackingEnabled) {
        return LocationTrackingExecutionDecision(
            shouldStartScheduledTracking = false,
            shouldStopScheduledTracking = true,
            shouldStartOptimizedBackgroundTracking = false,
            shouldStopOptimizedBackgroundTracking = true,
            shouldStartActivityAwareTracking = false,
            shouldStopActivityAwareTracking = true,
        )
    }

    val activeMode = settings.captureMode == LocationCaptureMode.ACTIVE
    return LocationTrackingExecutionDecision(
        shouldStartScheduledTracking = true,
        shouldStopScheduledTracking = false,
        shouldStartOptimizedBackgroundTracking = activeMode,
        shouldStopOptimizedBackgroundTracking = !activeMode,
        shouldStartActivityAwareTracking = activeMode,
        shouldStopActivityAwareTracking = !activeMode,
    )
}

/**
 * Whether the periodic background capture should record a sample. While the activity-aware stream
 * is delivering fixes it already covers the period with better ones, and an extra balanced-power fix
 * in between would only add a coarser position to the same moment. A stream that is subscribed but
 * has delivered nothing within the capture interval covers nothing, so the sample is recorded.
 */
internal fun shouldRecordPeriodicSample(
    settings: LocationTrackingSettings,
    lastStreamFixAt: Instant?,
    now: Instant,
): Boolean =
    settings.captureMode != LocationCaptureMode.ACTIVE ||
        lastStreamFixAt == null ||
        now - lastStreamFixAt > settings.minimumPersistIntervalMinutes.minutes
