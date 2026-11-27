package app.logdate.client.domain.location.history

import app.logdate.client.repository.journals.JournalNote
import app.logdate.shared.model.location.PlaceVisit
import app.logdate.shared.model.location.VisitMemoryLink
import kotlin.math.abs
import kotlin.time.Duration.Companion.minutes

class AssociateVisitMemories {
    operator fun invoke(
        visits: List<PlaceVisit>,
        notes: List<JournalNote>,
        links: List<VisitMemoryLink>,
    ): List<PlaceVisit> {
        val assignments = mutableMapOf<String, MutableList<String>>()
        notes.forEach { note ->
            val explicit = links.filter { it.noteId == note.uid.toString() }
            val chosen =
                if (explicit.isNotEmpty()) {
                    visits.filter { visit -> explicit.any { it.targetEvidenceId in visit.evidenceIds } }.singleOrNull()
                } else {
                    scoreBestVisit(visits, note)
                }
            chosen?.let { visit -> assignments.getOrPut(visit.id) { mutableListOf() }.add(note.uid.toString()) }
        }
        return visits.map { it.copy(memoryIds = assignments[it.id].orEmpty()) }
    }

    private fun scoreBestVisit(
        visits: List<PlaceVisit>,
        note: JournalNote,
    ): PlaceVisit? {
        val location = note.location ?: return null
        val lat = location.effectiveLatitude ?: return null
        val lon = location.effectiveLongitude ?: return null
        val edgeGraceSeconds = TEMPORAL_GRACE.inWholeSeconds.toDouble()

        val scored =
            visits.mapNotNull { visit ->
                val spatialTolerance = maxOf(BASE_SPATIAL_TOLERANCE_METERS, visit.uncertaintyRadiusMeters.toDouble() + 35.0)
                val dist = geographicDistance(visit.latitude, visit.longitude, lat, lon)
                if (dist > spatialTolerance) return@mapNotNull null

                val insideStrict = note.creationTimestamp >= visit.start && note.creationTimestamp <= visit.end
                val temporalScore =
                    when {
                        insideStrict -> 1.0
                        note.creationTimestamp >= visit.start - TEMPORAL_GRACE &&
                            note.creationTimestamp <= visit.end + TEMPORAL_GRACE -> {
                            val edgeDelta =
                                if (note.creationTimestamp < visit.start) {
                                    (visit.start - note.creationTimestamp).inWholeSeconds.toDouble()
                                } else {
                                    (note.creationTimestamp - visit.end).inWholeSeconds.toDouble()
                                }
                            1.0 - (edgeDelta / edgeGraceSeconds).coerceIn(0.0, 1.0)
                        }
                        else -> return@mapNotNull null
                    }
                val spatialScore = 1.0 / (1.0 + dist / 50.0)
                ScoredVisit(visit, temporalScore + spatialScore, insideStrict)
            }
        if (scored.isEmpty()) return null
        val maxScore = scored.maxOf { it.score }
        val tiedAtTop = scored.filter { abs(it.score - maxScore) <= SCORE_TIE_EPSILON }
        if (tiedAtTop.size == 1) return tiedAtTop.single().visit
        val strictlyInside = tiedAtTop.filter { it.insideStrict }
        return if (strictlyInside.size == 1) strictlyInside.single().visit else null
    }

    private data class ScoredVisit(
        val visit: PlaceVisit,
        val score: Double,
        val insideStrict: Boolean,
    )

    private companion object {
        const val BASE_SPATIAL_TOLERANCE_METERS = 100.0
        const val SCORE_TIE_EPSILON = 1e-6
        val TEMPORAL_GRACE = 5.minutes
    }
}
