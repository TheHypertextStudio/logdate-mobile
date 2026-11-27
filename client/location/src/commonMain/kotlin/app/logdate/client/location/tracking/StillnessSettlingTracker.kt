package app.logdate.client.location.tracking

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

data class SettlingDecision(
    val enterStill: Boolean = false,
    val registerExitGeofenceAt: Pair<Double, Double>? = null,
    val wakeToProfileName: String? = null,
)

/**
 * Pure-Kotlin stillness and departure detector backing [ActivityAwareLocationService].
 *
 * When Play Services Activity Recognition suppresses or delays transitions, three consecutive fixes
 * within [settlingRadiusMeters] and under [stillSpeedLimitMps] step the stream down to `STILL`.
 * After [geofenceDelay] within that zone, it emits an accuracy-weighted anchor for a 100 m exit
 * geofence, and wakes immediately on fast speeds or displacement beyond [wakeDistanceMeters].
 */
class StillnessSettlingTracker(
    private val settlingRadiusMeters: Double = 25.0,
    private val minSamplesForStill: Int = 3,
    private val geofenceDelay: Duration = 5.minutes,
    private val wakeDistanceMeters: Double = 100.0,
    private val wakeAccuracyLimitMeters: Float = 50f,
    private val stillSpeedLimitMps: Float = 0.5f,
    private val wakeSpeedMps: Float = 1.8f,
    private val vehicleSpeedMps: Float = 8.0f,
) {
    private var windowStart: Instant? = null
    private var seedLat: Double? = null
    private var seedLon: Double? = null
    private var sampleCount: Int = 0
    private var latWeightedSum: Double = 0.0
    private var lonWeightedSum: Double = 0.0
    private var totalWeight: Double = 0.0
    private var stillActive: Boolean = false
    private var geofenceEmitted: Boolean = false

    fun onFix(
        latitude: Double,
        longitude: Double,
        accuracyMeters: Float?,
        speedMetersPerSecond: Float?,
        timestamp: Instant,
    ): SettlingDecision {
        val anchor = currentAnchor()
        if (stillActive) {
            val fastWake = speedMetersPerSecond != null && speedMetersPerSecond >= wakeSpeedMps
            val displacedWake =
                anchor != null &&
                    (accuracyMeters == null || accuracyMeters <= wakeAccuracyLimitMeters) &&
                    haversineMeters(anchor.first, anchor.second, latitude, longitude) > wakeDistanceMeters
            if (fastWake || displacedWake) {
                reset()
                val profileName =
                    if (speedMetersPerSecond != null && speedMetersPerSecond >= vehicleSpeedMps) {
                        "IN_VEHICLE"
                    } else {
                        "ON_FOOT"
                    }
                return SettlingDecision(wakeToProfileName = profileName)
            }
            addSample(latitude, longitude, accuracyMeters, timestamp)
            val start = windowStart ?: timestamp
            if (!geofenceEmitted && timestamp - start >= geofenceDelay) {
                geofenceEmitted = true
                return SettlingDecision(registerExitGeofenceAt = currentAnchor())
            }
            return SettlingDecision()
        }

        if (speedMetersPerSecond != null && speedMetersPerSecond >= stillSpeedLimitMps) {
            reset()
            return SettlingDecision()
        }

        val currentSeedLat = seedLat
        val currentSeedLon = seedLon
        if (currentSeedLat == null || currentSeedLon == null) {
            startWindow(latitude, longitude, accuracyMeters, timestamp)
            return SettlingDecision()
        }

        if (haversineMeters(currentSeedLat, currentSeedLon, latitude, longitude) > settlingRadiusMeters) {
            startWindow(latitude, longitude, accuracyMeters, timestamp)
            return SettlingDecision()
        }

        addSample(latitude, longitude, accuracyMeters, timestamp)
        if (sampleCount >= minSamplesForStill) {
            stillActive = true
            val start = windowStart ?: timestamp
            val geofenceAnchor =
                if (timestamp - start >= geofenceDelay) {
                    geofenceEmitted = true
                    currentAnchor()
                } else {
                    null
                }
            return SettlingDecision(enterStill = true, registerExitGeofenceAt = geofenceAnchor)
        }
        return SettlingDecision()
    }

    fun reset() {
        windowStart = null
        seedLat = null
        seedLon = null
        sampleCount = 0
        latWeightedSum = 0.0
        lonWeightedSum = 0.0
        totalWeight = 0.0
        stillActive = false
        geofenceEmitted = false
    }

    private fun startWindow(
        latitude: Double,
        longitude: Double,
        accuracyMeters: Float?,
        timestamp: Instant,
    ) {
        reset()
        windowStart = timestamp
        seedLat = latitude
        seedLon = longitude
        addSample(latitude, longitude, accuracyMeters, timestamp)
    }

    private fun addSample(
        latitude: Double,
        longitude: Double,
        accuracyMeters: Float?,
        timestamp: Instant,
    ) {
        if (windowStart == null) windowStart = timestamp
        val sigma = max(accuracyMeters ?: 50f, 5f).toDouble()
        val weight = 1.0 / (sigma * sigma)
        latWeightedSum += latitude * weight
        lonWeightedSum += longitude * weight
        totalWeight += weight
        sampleCount++
    }

    private fun currentAnchor(): Pair<Double, Double>? {
        if (totalWeight <= 0.0) return null
        return (latWeightedSum / totalWeight) to (lonWeightedSum / totalWeight)
    }

    private fun haversineMeters(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double,
    ): Double {
        val dLat = (lat2 - lat1) * PI / 180.0
        val dLon = (lon2 - lon1) * PI / 180.0
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(lat1 * PI / 180.0) * cos(lat2 * PI / 180.0) * sin(dLon / 2) * sin(dLon / 2)
        return 6_371_000.0 * 2.0 * atan2(sqrt(a.coerceIn(0.0, 1.0)), sqrt((1.0 - a).coerceIn(0.0, 1.0)))
    }
}
