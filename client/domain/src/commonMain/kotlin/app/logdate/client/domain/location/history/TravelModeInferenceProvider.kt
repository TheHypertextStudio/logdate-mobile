package app.logdate.client.domain.location.history

import app.logdate.shared.model.location.LocationObservation
import app.logdate.shared.model.location.TravelMode

data class TravelModeCandidate(
    val mode: TravelMode,
    val confidence: Float,
    val provenance: String,
)

fun interface TravelModeInferenceProvider {
    fun infer(evidence: List<LocationObservation>): List<TravelModeCandidate>
}

class RecordedTravelModeInferenceProvider : TravelModeInferenceProvider {
    override fun infer(evidence: List<LocationObservation>): List<TravelModeCandidate> {
        val modes = evidence.map { it.activity }.filterNot { it == TravelMode.STILL || it == TravelMode.UNKNOWN }
        val mode =
            modes
                .groupingBy { it }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key ?: return emptyList()
        return listOf(TravelModeCandidate(mode, modes.count { it == mode }.toFloat() / evidence.size, "recorded_activity"))
    }
}

/**
 * Combines projected activity-recognition intervals with kinematic speed distributions so missing
 * or revoked Activity Recognition permissions still yield honest movement modes. Transit modes
 * ([TravelMode.BUS], [TravelMode.TRAIN]) are never guessed from kinematics alone.
 */
class HybridTravelModeInferenceProvider : TravelModeInferenceProvider {
    override fun infer(evidence: List<LocationObservation>): List<TravelModeCandidate> {
        if (evidence.isEmpty()) return emptyList()
        inferFromActivityIntervals(evidence)?.let { return listOf(it) }
        val kinematics = collectKinematicSpeeds(evidence)
        if (kinematics.validPairs < 3 || kinematics.speeds.isEmpty()) {
            val recorded = RecordedTravelModeInferenceProvider().infer(evidence)
            return recorded.ifEmpty { listOf(TravelModeCandidate(TravelMode.UNKNOWN, 0f, "insufficient_kinematics")) }
        }
        return listOf(classifyKinematicSpeeds(kinematics))
    }

    private fun inferFromActivityIntervals(evidence: List<LocationObservation>): TravelModeCandidate? {
        val activeModes = evidence.map { it.activity }.filterNot { it == TravelMode.STILL || it == TravelMode.UNKNOWN }
        if (activeModes.size * 2 < evidence.size) return null
        val dominant =
            activeModes
                .groupingBy { it }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key ?: return null
        val confidence = (activeModes.count { it == dominant }.toFloat() / evidence.size).coerceIn(0f, 1f)
        return TravelModeCandidate(dominant, confidence, "activity_interval")
    }

    private fun collectKinematicSpeeds(evidence: List<LocationObservation>): KinematicSummary {
        val speeds = mutableListOf<Double>()
        var totalDistance = 0.0
        var validPairs = 0
        for (i in 0 until evidence.lastIndex) {
            val a = evidence[i]
            val b = evidence[i + 1]
            val dt = (b.timestamp - a.timestamp).inWholeMilliseconds / 1000.0
            if (dt >= 2.0 && (a.accuracyMeters ?: 0f) <= 100f && (b.accuracyMeters ?: 0f) <= 100f) {
                val d = distanceMeters(a, b)
                totalDistance += d
                speeds += (d / dt)
                validPairs++
            }
        }
        evidence.forEach { point ->
            val reported = point.speedMetersPerSecond
            if (reported != null && reported > 0f && (point.accuracyMeters ?: 0f) <= 100f) {
                speeds += reported.toDouble()
            }
        }
        return KinematicSummary(speeds.sorted(), totalDistance, validPairs)
    }

    private fun classifyKinematicSpeeds(summary: KinematicSummary): TravelModeCandidate {
        val sorted = summary.speeds
        val p50 = sorted[(sorted.size * 0.50).toInt().coerceIn(sorted.indices)]
        val p85 = sorted[(sorted.size * 0.85).toInt().coerceIn(sorted.indices)]
        val stopRatio = sorted.count { it < 0.4 }.toDouble() / sorted.size
        val totalDistance = summary.totalDistanceMeters
        return when {
            p85 >= 8.5 || (p50 >= 6.0 && totalDistance >= 500.0) -> {
                TravelModeCandidate(TravelMode.VEHICLE, if (p85 >= 12.0) 0.90f else 0.78f, "kinematic_speed")
            }

            p50 in 3.5..<8.5 && p85 < 10.5 && totalDistance >= 300.0 -> {
                TravelModeCandidate(TravelMode.CYCLING, 0.65f, "kinematic_speed")
            }

            p50 in 2.3..<3.8 && stopRatio < 0.2 && totalDistance >= 250.0 -> {
                TravelModeCandidate(TravelMode.RUNNING, 0.62f, "kinematic_speed")
            }

            p50 in 0.5..<2.3 && p85 < 3.5 -> {
                TravelModeCandidate(TravelMode.WALKING, 0.72f, "kinematic_speed")
            }

            else -> {
                TravelModeCandidate(TravelMode.UNKNOWN, 0f, "insufficient_kinematics")
            }
        }
    }

    private data class KinematicSummary(
        val speeds: List<Double>,
        val totalDistanceMeters: Double,
        val validPairs: Int,
    )
}
