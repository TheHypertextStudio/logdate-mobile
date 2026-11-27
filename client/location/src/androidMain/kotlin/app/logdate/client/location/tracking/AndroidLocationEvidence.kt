package app.logdate.client.location.tracking

import android.location.Location
import android.os.Build
import android.os.SystemClock
import androidx.core.location.LocationCompat
import app.logdate.client.repository.location.LocationCapturePipeline
import app.logdate.client.repository.location.LocationCaptureSource
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

private const val MAX_CLOCK_SKEW_FUTURE_MS = -5_000L
private const val COARSE_NULL_ISLAND_ACCURACY_THRESHOLD_METERS = 1_000f

internal fun Location.observedAt(fallback: Instant): Instant = if (time > 0) Instant.fromEpochMilliseconds(time) else fallback

internal fun Location.isValidForCapture(): Boolean =
    isValidCaptureSample(
        latitude = latitude,
        longitude = longitude,
        hasAccuracy = hasAccuracy(),
        accuracyMeters = if (hasAccuracy()) accuracy else null,
    )

internal fun isValidCaptureSample(
    latitude: Double,
    longitude: Double,
    hasAccuracy: Boolean,
    accuracyMeters: Float?,
): Boolean {
    if (!latitude.isFinite() || !longitude.isFinite()) return false
    if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return false
    if (hasAccuracy && (accuracyMeters == null || accuracyMeters < 0f)) return false
    if (latitude == 0.0 &&
        longitude == 0.0 &&
        (!hasAccuracy || (accuracyMeters ?: Float.MAX_VALUE) > COARSE_NULL_ISLAND_ACCURACY_THRESHOLD_METERS)
    ) {
        return false
    }
    return true
}

internal fun Location.isFreshForStream(
    maxAgeMs: Long,
    elapsedNowNanos: Long = SystemClock.elapsedRealtimeNanos(),
): Boolean = isFreshElapsedNanos(elapsedRealtimeNanos, elapsedNowNanos, maxAgeMs)

internal fun isFreshElapsedNanos(
    fixElapsedNanos: Long,
    elapsedNowNanos: Long,
    maxAgeMs: Long,
): Boolean {
    if (fixElapsedNanos <= 0L || elapsedNowNanos <= 0L) return true
    val ageMs = (elapsedNowNanos - fixElapsedNanos) / 1_000_000L
    return ageMs in MAX_CLOCK_SKEW_FUTURE_MS..maxAgeMs
}

internal fun Location.captureMetadata(
    recordedAt: Instant,
    pipeline: LocationCapturePipeline,
    source: LocationCaptureSource,
    activityType: String? = null,
): Map<String, Any> =
    buildMap {
        put("loggedAt", recordedAt)
        put("capturePipeline", pipeline)
        put("captureSource", source)
        put("timeZoneId", TimeZone.currentSystemDefault().id)
        if (hasAccuracy()) put("accuracyMeters", accuracy)
        if (hasSpeed()) put("speedMetersPerSecond", speed)
        if (hasBearing()) put("bearingDegrees", bearing)
        put("isMock", LocationCompat.isMock(this@captureMetadata))
        provider?.takeIf { it.isNotBlank() }?.let { put("provider", it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (hasSpeedAccuracy()) put("speedAccuracyMetersPerSecond", speedAccuracyMetersPerSecond)
            if (hasVerticalAccuracy()) put("verticalAccuracyMeters", verticalAccuracyMeters)
        }
        elapsedRealtimeNanos.takeIf { it > 0L }?.let { put("elapsedRealtimeNanos", it) }
        activityType?.let { put("activityType", it) }
    }
