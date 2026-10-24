package app.logdate.client.intelligence.curation

import app.logdate.client.repository.journals.JournalNote
import app.logdate.shared.model.StoryBeat
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Assigns scored candidates to story-beat time windows.
 *
 * Each beat's window is derived from the timestamps of the cited text entries and media
 * (±2h padding). Beats whose `evidenceIds` resolve to nothing in the current input get
 * an evenly-split slice of the period as a fallback so they still produce a window.
 * Candidates outside every window become free agents for structural panels.
 */
class BeatBucketer {
    /**
     * Returns each candidate with [MediaCandidate.assignedBeatIndex] populated (or `null`
     * when no beat window contained it).
     */
    fun bucket(
        candidates: List<MediaCandidate>,
        beats: List<StoryBeat>,
        textEntries: List<JournalNote.Text>,
        periodStart: Instant,
        periodEnd: Instant,
    ): List<MediaCandidate> {
        if (beats.isEmpty() || candidates.isEmpty()) return candidates
        val windows = deriveWindows(beats, candidates, textEntries, periodStart, periodEnd)

        return candidates.map { candidate ->
            val ts = candidate.media.timestamp.toEpochMilliseconds()
            // When a candidate falls inside multiple beat windows, prefer the beat that
            // explicitly cited it; otherwise pick the one whose window center is closest.
            val matchingBeats = windows.withIndex().filter { (_, w) -> ts in w.startMs..w.endMs }
            val chosen: Int? =
                when {
                    matchingBeats.isEmpty() -> {
                        null
                    }

                    matchingBeats.size == 1 -> {
                        matchingBeats.first().index
                    }

                    else -> {
                        val citedBeatIdx =
                            matchingBeats
                                .firstOrNull { (i, _) -> candidate.media.uid.toString() in beats[i].evidenceIds }
                                ?.index
                        citedBeatIdx
                            ?: matchingBeats.minBy { (_, w) -> kotlin.math.abs(((w.startMs + w.endMs) / 2L) - ts) }.index
                    }
                }
            candidate.copy(assignedBeatIndex = chosen)
        }
    }

    private fun deriveWindows(
        beats: List<StoryBeat>,
        candidates: List<MediaCandidate>,
        textEntries: List<JournalNote.Text>,
        periodStart: Instant,
        periodEnd: Instant,
    ): List<BeatWindow> {
        val candidateTsByUid: Map<String, Long> =
            candidates.associate {
                it.media.uid.toString() to it.media.timestamp.toEpochMilliseconds()
            }
        val entryTsByUid: Map<String, Long> =
            textEntries.associate {
                it.uid.toString() to it.creationTimestamp.toEpochMilliseconds()
            }

        val padMs = WINDOW_PAD.inWholeMilliseconds
        val anchoredWindows = mutableMapOf<Int, BeatWindow>()
        val unanchored = mutableListOf<Int>()

        beats.forEachIndexed { idx, beat ->
            val evidenceTimes =
                beat.evidenceIds.mapNotNull { id -> candidateTsByUid[id] ?: entryTsByUid[id] }
            if (evidenceTimes.isEmpty()) {
                unanchored.add(idx)
            } else {
                anchoredWindows[idx] = BeatWindow(evidenceTimes.min() - padMs, evidenceTimes.max() + padMs)
            }
        }

        // Unanchored beats get an even slice of the period — bookended by the period's
        // actual bounds and squeezed in chronological-index order between anchored
        // windows when possible, so they keep narrative ordering.
        if (unanchored.isNotEmpty()) {
            val periodSpan = periodEnd.toEpochMilliseconds() - periodStart.toEpochMilliseconds()
            val sliceMs = periodSpan / beats.size.coerceAtLeast(1)
            unanchored.forEach { idx ->
                val start = periodStart.toEpochMilliseconds() + idx * sliceMs
                val end = if (idx == beats.lastIndex) periodEnd.toEpochMilliseconds() else start + sliceMs
                anchoredWindows[idx] = BeatWindow(start, end)
            }
        }

        return (0 until beats.size).map { anchoredWindows.getValue(it) }
    }

    internal data class BeatWindow(
        val startMs: Long,
        val endMs: Long,
    )

    private companion object {
        private val WINDOW_PAD = 2.hours
    }
}
