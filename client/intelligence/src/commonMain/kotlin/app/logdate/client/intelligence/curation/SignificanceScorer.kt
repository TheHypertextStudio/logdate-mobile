package app.logdate.client.intelligence.curation

import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.location.LocationHistoryItem
import app.logdate.client.repository.media.IndexedMedia
import app.logdate.shared.model.Person
import app.logdate.shared.model.WeekNarrative
import kotlin.math.abs
import kotlin.math.floor

/**
 * Composes a per-candidate [ScoreBreakdown].
 *
 * Sub-scores combine the LLM's "this matters" signal (story-beat evidenceIds, +40 flat),
 * cross-platform behavioral signals (people / journal / time clustering), and
 * platform-extracted intrinsic signals (resolution, duration). Null signals contribute
 * zero — never negative — so a desktop pass with all-null MediaSignals still differentiates
 * candidates by the cross-platform signals alone.
 */
class SignificanceScorer {
    /**
     * Scores every candidate against the same context (narrative, journal entries, etc.).
     * Returns a new list with [MediaCandidate.derivedScores] populated. [MediaCandidate.isLLMCited]
     * is also set whenever the candidate matched a story-beat `evidenceId`.
     */
    fun score(
        candidates: List<MediaCandidate>,
        narrative: WeekNarrative?,
        textEntries: List<JournalNote.Text>,
        people: List<Person>,
        locationHistory: List<LocationHistoryItem>,
    ): List<MediaCandidate> {
        if (candidates.isEmpty()) return candidates

        val evidenceUids: Set<String> =
            narrative?.storyBeats?.flatMapTo(mutableSetOf()) { it.evidenceIds } ?: emptySet()
        val personNames: List<String> = people.map { it.name }
        val locationCellCounts: Map<LocationCell, Int> =
            locationHistory.groupingBy { LocationCell.from(it.location.latitude, it.location.longitude) }.eachCount()
        val sortedMediaTimestamps: LongArray =
            candidates.map { it.media.timestamp.toEpochMilliseconds() }.sorted().toLongArray()

        return candidates.map { candidate ->
            val mediaTs = candidate.media.timestamp.toEpochMilliseconds()
            val isCited = candidate.media.uid.toString() in evidenceUids

            val narrativeEvidence = if (isCited) 40f else 0f
            val peopleProximity = scorePeopleProximity(mediaTs, textEntries, personNames)
            val locationNovelty = scoreLocationNovelty(candidate, locationCellCounts)
            val timeClusterDensity = scoreTimeClusterDensity(mediaTs, sortedMediaTimestamps)
            val journalProximity = scoreJournalProximity(mediaTs, textEntries)
            val intrinsic = scoreIntrinsic(candidate.media, candidate.signals)
            val penalty = 0f // Reserved for downstream tie-breakers (burst-survivor penalty etc.).

            val totalRaw =
                narrativeEvidence + peopleProximity + locationNovelty +
                    timeClusterDensity + journalProximity + intrinsic + penalty
            val total = totalRaw.coerceIn(0f, 100f)

            candidate.copy(
                isLLMCited = isCited,
                derivedScores =
                    ScoreBreakdown(
                        total = total,
                        narrativeEvidence = narrativeEvidence,
                        peopleProximity = peopleProximity,
                        locationNovelty = locationNovelty,
                        timeClusterDensity = timeClusterDensity,
                        journalProximity = journalProximity,
                        mediaIntrinsic = intrinsic,
                        penalty = penalty,
                    ),
            )
        }
    }

    private fun scorePeopleProximity(
        mediaTs: Long,
        textEntries: List<JournalNote.Text>,
        personNames: List<String>,
    ): Float {
        if (textEntries.isEmpty() || personNames.isEmpty()) return 0f
        val window = PEOPLE_PROXIMITY_WINDOW_MS
        val mentions =
            textEntries
                .asSequence()
                .filter { abs(it.creationTimestamp.toEpochMilliseconds() - mediaTs) <= window }
                .flatMap { entry -> personNames.asSequence().filter { entry.content.contains(it, ignoreCase = true) } }
                .toSet() // distinct person names mentioned near this photo
        return (mentions.size.coerceAtMost(5) * 3f) // 3 points per name, capped at 15
    }

    private fun scoreLocationNovelty(
        candidate: MediaCandidate,
        locationCellCounts: Map<LocationCell, Int>,
    ): Float {
        val lat = candidate.signals.latitude ?: return 0f
        val lon = candidate.signals.longitude ?: return 0f
        val cell = LocationCell.from(lat, lon)
        return when (val visits = locationCellCounts[cell] ?: 0) {
            0 -> {
                15f
            }

            // brand-new cell
            in 1..2 -> {
                8f
            }

            // rare cell
            else -> {
                if (visits >= 30) -2f else 0f // very common cell — slight penalty
            }
        }
    }

    private fun scoreTimeClusterDensity(
        mediaTs: Long,
        sortedTimestamps: LongArray,
    ): Float {
        val window = TIME_CLUSTER_WINDOW_MS
        // Cluster counts are small in weekly Rewinds; a linear scan beats the
        // ceremony of a binary search for the cardinalities we expect.
        var count = 0
        for (t in sortedTimestamps) {
            if (abs(t - mediaTs) <= window) {
                count++
            } else if (t - mediaTs > window) {
                break
            }
        }
        return when (count) {
            1 -> 2f

            // lonely shot — slightly less interesting than a cluster
            2 -> 5f

            in 3..7 -> 10f

            // sweet spot
            in 8..15 -> 4f

            // probably a moment but already lots of evidence
            else -> 1f // huge cluster — likely a burst we didn't catch / repetitive scene
        }
    }

    private fun scoreJournalProximity(
        mediaTs: Long,
        textEntries: List<JournalNote.Text>,
    ): Float {
        if (textEntries.isEmpty()) return 0f
        val nearest = textEntries.minOf { abs(it.creationTimestamp.toEpochMilliseconds() - mediaTs) }
        return when {
            nearest <= MIN_30 -> 20f
            nearest <= HOURS_2 -> 10f
            nearest <= HOURS_6 -> 3f
            else -> 0f
        }
    }

    private fun scoreIntrinsic(
        media: IndexedMedia,
        signals: MediaSignals,
    ): Float {
        var score = 0f
        val longEdge = maxOf(signals.widthPx ?: 0, signals.heightPx ?: 0)
        if (longEdge >= 1920) score += 5f
        if (media is IndexedMedia.Video) {
            val seconds = media.duration.inWholeSeconds
            score +=
                when {
                    seconds in 3..60 -> 5f

                    // panel-friendly clip length
                    seconds > 300 -> -10f

                    // long video is probably a screen recording
                    else -> 0f
                }
        }
        return score
    }

    private companion object {
        private const val MIN_30 = 30L * 60L * 1000L
        private const val HOURS_2 = 2L * 60L * 60L * 1000L
        private const val HOURS_6 = 6L * 60L * 60L * 1000L
        private const val PEOPLE_PROXIMITY_WINDOW_MS = HOURS_2
        private const val TIME_CLUSTER_WINDOW_MS = 15L * 60L * 1000L
    }
}

/**
 * One ~1km lat/lon grid cell, used for location novelty bucketing. Deliberately coarse
 * — the goal is "did we go somewhere new this week" rather than precise localization.
 */
internal data class LocationCell(
    val latIdx: Int,
    val lonIdx: Int,
) {
    companion object {
        private const val CELL_DEGREES = 0.01 // ~1.1km at the equator

        fun from(
            lat: Double,
            lon: Double,
        ): LocationCell =
            LocationCell(
                latIdx = floor(lat / CELL_DEGREES).toInt(),
                lonIdx = floor(lon / CELL_DEGREES).toInt(),
            )
    }
}
