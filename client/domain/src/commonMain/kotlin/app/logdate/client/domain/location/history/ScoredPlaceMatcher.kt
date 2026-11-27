package app.logdate.client.domain.location.history

import app.logdate.shared.model.location.PlaceVisit
import app.logdate.shared.model.location.SemanticPlace
import kotlin.math.exp
import kotlin.math.ln

data class PlaceMatchResult(
    val assigned: SemanticPlace?,
    val candidatePlaceIds: List<String>,
    val needsDisambiguation: Boolean,
)

/**
 * Scores non-deleted, alias-resolved [SemanticPlace] candidates against a [PlaceVisit] using
 * distance-decay Gaussian weighting, user confirmation, visit familiarity, and explicit priors.
 */
class ScoredPlaceMatcher {
    fun match(
        visit: PlaceVisit,
        places: List<SemanticPlace>,
        visitCountsByPlaceId: Map<String, Int> = emptyMap(),
        explicitCorrectionPlaceId: String? = null,
    ): PlaceMatchResult {
        if (places.isEmpty()) return PlaceMatchResult(null, emptyList(), false)

        val candidates =
            places
                .mapNotNull { place ->
                    val distance = geographicDistance(visit.latitude, visit.longitude, place.latitude, place.longitude)
                    val maxGate = maxOf(place.radiusMeters, visit.uncertaintyRadiusMeters.toDouble()) + CANDIDATE_GATE_MARGIN_METERS
                    if (distance > maxGate) {
                        null
                    } else {
                        val effectiveRadius =
                            maxOf(
                                place.radiusMeters,
                                visit.uncertaintyRadiusMeters.toDouble(),
                                MINIMUM_EFFECTIVE_RADIUS_METERS,
                            )
                        val distanceFactor = exp(-((distance / effectiveRadius) * (distance / effectiveRadius)))
                        val confirmedWeight = if (place.userConfirmed) CONFIRMED_WEIGHT else UNCONFIRMED_WEIGHT
                        val visitCount = (visitCountsByPlaceId[place.id] ?: 0).coerceAtLeast(0)
                        val familiarityWeight = 1.0 + FAMILIARITY_LOG_WEIGHT * ln(1.0 + visitCount.toDouble())
                        val explicitWeight = if (place.id == explicitCorrectionPlaceId) EXPLICIT_CORRECTION_WEIGHT else 1.0
                        val score = distanceFactor * confirmedWeight * familiarityWeight * explicitWeight
                        ScoredCandidate(place, score)
                    }
                }.sortedByDescending { it.score }

        val top = candidates.firstOrNull() ?: return PlaceMatchResult(null, emptyList(), false)
        if (top.score < ASSIGNMENT_SCORE_THRESHOLD) {
            return PlaceMatchResult(null, candidates.take(MAX_CANDIDATE_IDS).map { it.place.id }, false)
        }
        val runnerUp = candidates.getOrNull(1)
        val disambiguate = runnerUp != null && (top.score - runnerUp.score) <= DISAMBIGUATION_MARGIN
        val candidateIds =
            if (disambiguate) {
                candidates.take(MAX_CANDIDATE_IDS).map { it.place.id }
            } else {
                emptyList()
            }
        return PlaceMatchResult(
            assigned = top.place,
            candidatePlaceIds = candidateIds,
            needsDisambiguation = disambiguate,
        )
    }

    private data class ScoredCandidate(
        val place: SemanticPlace,
        val score: Double,
    )

    private companion object {
        const val CANDIDATE_GATE_MARGIN_METERS = 25.0
        const val MINIMUM_EFFECTIVE_RADIUS_METERS = 45.0
        const val CONFIRMED_WEIGHT = 1.5
        const val UNCONFIRMED_WEIGHT = 0.85
        const val FAMILIARITY_LOG_WEIGHT = 0.15
        const val EXPLICIT_CORRECTION_WEIGHT = 1.25
        const val ASSIGNMENT_SCORE_THRESHOLD = 0.45
        const val DISAMBIGUATION_MARGIN = 0.15
        const val MAX_CANDIDATE_IDS = 3
    }
}
