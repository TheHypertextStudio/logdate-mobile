package app.logdate.client.intelligence.rewind.local

import app.logdate.client.intelligence.rewind.strategy.AudioEntryWithTranscript
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.location.LocationHistoryItem
import app.logdate.client.repository.media.IndexedMedia
import app.logdate.shared.model.StoryBeat
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Splits a period's content into 1–6 [StoryBeat]s by clustering on calendar day. The
 * local Rewind path lacks an LLM to understand narrative arc; day-level clustering
 * is the simplest signal that still yields a story-shaped Rewind instead of one
 * catch-all beat covering everything.
 *
 * Each beat covers one day (or one "phase" when there are too many days to fit in
 * six beats), with:
 *  - a [StoryBeat.moment] headline picked from the longest entry-style sentence
 *    in the day's writing, or a day-of-week placeholder when nothing fits.
 *  - an [StoryBeat.emotionalWeight] tag inferred by counting feeling-laden words
 *    (joyful / heavy / mixed / quiet).
 *  - [StoryBeat.evidenceIds] containing the uid strings of every entry and media
 *    item from that day, so the sequencer can attach them to this beat.
 */
class LocalStoryBeatDetector(
    private val maxBeats: Int = DEFAULT_MAX_BEATS,
) {
    /**
     * @param textEntries text entries from the period.
     * @param media media items from the period (used only for evidence-id grouping).
     * @param audio audio entries from the period, paired with whatever transcript is
     *   available right now. Included with the same no-judgment inclusion as text and
     *   media — an audio-only day still produces a beat, transcript or not.
     * @param locationHistory recorded GPS points from the period. When a single day
     *   shows visits to >=2 places more than [LOCATION_SPLIT_DISTANCE_METERS] apart,
     *   the detector subdivides that day into multiple beats — so trip days don't
     *   flatten into one undifferentiated entry.
     * @return 0–[maxBeats] story beats. Empty when no text, media, or audio exists.
     */
    fun detect(
        textEntries: List<JournalNote.Text>,
        media: List<IndexedMedia> = emptyList(),
        audio: List<AudioEntryWithTranscript> = emptyList(),
        locationHistory: List<LocationHistoryItem> = emptyList(),
    ): List<StoryBeat> {
        if (textEntries.isEmpty() && media.isEmpty() && audio.isEmpty()) return emptyList()
        val tz = TimeZone.currentSystemDefault()

        // Bucket entries + media + audio by calendar date.
        val textByDay = textEntries.groupBy { it.creationTimestamp.toLocalDateTime(tz).date }
        val mediaByDay = media.groupBy { it.timestamp.toLocalDateTime(tz).date }
        val audioByDay =
            audio.groupBy {
                it.audio.creationTimestamp
                    .toLocalDateTime(tz)
                    .date
            }
        val locationsByDay =
            locationHistory.groupBy { it.timestamp.toLocalDateTime(tz).date }

        // Set + Set deduplicates (Set semantics), .sorted() yields a List in
        // natural date order; both available cross-platform unlike toSortedSet.
        val daysWithContent =
            (textByDay.keys + mediaByDay.keys + audioByDay.keys).sorted()

        // Step 1: per-day, subdivide into segments by location cluster when the
        // user travelled meaningfully. Each segment becomes a candidate beat.
        val candidateSegments =
            daysWithContent.flatMap { day ->
                buildDaySegments(
                    day = day,
                    dayEntries = textByDay[day].orEmpty(),
                    dayMedia = mediaByDay[day].orEmpty(),
                    dayAudio = audioByDay[day].orEmpty(),
                    dayLocations = locationsByDay[day].orEmpty(),
                )
            }

        // Step 2: enforce the ≤[maxBeats] cap. Prefer keeping the most recent
        // segments intact (the "what happened at the end of the week" story);
        // collapse the earliest into a single opening "phase" beat when needed.
        val beatedSegments =
            if (candidateSegments.size <= maxBeats) {
                candidateSegments.map { listOf(it) }
            } else {
                val collapseCount = candidateSegments.size - maxBeats + 1
                listOf(candidateSegments.take(collapseCount)) +
                    candidateSegments.drop(collapseCount).map { listOf(it) }
            }

        return beatedSegments.map { group ->
            val days = group.flatMap { it.days }.distinct()
            val entries = group.flatMap { it.entries }
            val media = group.flatMap { it.media }
            val audioForBeat = group.flatMap { it.audio }
            buildBeat(days, entries, media, audioForBeat)
        }
    }

    /**
     * Breaks one day into 1+ segments by location cluster. Returns segments in
     * chronological order; a day with no location data or a single cluster
     * returns a single segment with all that day's content.
     */
    private fun buildDaySegments(
        day: LocalDate,
        dayEntries: List<JournalNote.Text>,
        dayMedia: List<IndexedMedia>,
        dayAudio: List<AudioEntryWithTranscript>,
        dayLocations: List<LocationHistoryItem>,
    ): List<DaySegment> {
        if (dayLocations.size < 2) {
            return listOf(DaySegment(listOf(day), dayEntries, dayMedia, dayAudio))
        }

        val clusters = clusterByDistance(dayLocations.sortedBy { it.timestamp })
        if (clusters.size < 2) {
            return listOf(DaySegment(listOf(day), dayEntries, dayMedia, dayAudio))
        }

        // GPS captures are sparse. Assign each item to the nearest capture instead
        // of requiring its timestamp to fall inside a capture window; the latter
        // silently loses moments before, between, and after recorded points.
        fun nearestCluster(at: Instant): Int =
            clusters.indices.minBy { index ->
                clusters[index].minOf { point ->
                    val distance = point.timestamp - at
                    if (distance.isNegative()) -distance else distance
                }
            }

        val perCluster =
            clusters.indices.map { index ->
                val matchingEntries = dayEntries.filter { nearestCluster(it.creationTimestamp) == index }
                val matchingMedia = dayMedia.filter { nearestCluster(it.timestamp) == index }
                val matchingAudio = dayAudio.filter { nearestCluster(it.audio.creationTimestamp) == index }
                DaySegment(
                    days = listOf(day),
                    entries = matchingEntries,
                    media = matchingMedia,
                    audio = matchingAudio,
                )
            }
        val nonEmpty = perCluster.filter { it.entries.isNotEmpty() || it.media.isNotEmpty() || it.audio.isNotEmpty() }
        // If filtering wiped everything, keep the whole-day segment.
        return nonEmpty.ifEmpty { listOf(DaySegment(listOf(day), dayEntries, dayMedia, dayAudio)) }
    }

    /**
     * Greedy temporal clustering — walk locations in order and start a new
     * cluster whenever the next point is more than [LOCATION_SPLIT_DISTANCE_METERS]
     * from the current cluster's last point. Captures "morning at home, afternoon
     * at the coast" without trying to be a real geo-clusterer.
     */
    private fun clusterByDistance(sorted: List<LocationHistoryItem>): List<List<LocationHistoryItem>> {
        if (sorted.isEmpty()) return emptyList()
        val clusters = mutableListOf<MutableList<LocationHistoryItem>>()
        var current = mutableListOf(sorted.first())
        clusters.add(current)
        for (point in sorted.drop(1)) {
            val last = current.last()
            val distance = point.location.distanceTo(last.location)
            if (distance >= LOCATION_SPLIT_DISTANCE_METERS) {
                current = mutableListOf(point)
                clusters.add(current)
            } else {
                current.add(point)
            }
        }
        return clusters
    }

    private data class DaySegment(
        val days: List<LocalDate>,
        val entries: List<JournalNote.Text>,
        val media: List<IndexedMedia>,
        val audio: List<AudioEntryWithTranscript> = emptyList(),
    )

    private fun buildBeat(
        dayBucket: List<LocalDate>,
        entries: List<JournalNote.Text>,
        media: List<IndexedMedia>,
        audio: List<AudioEntryWithTranscript>,
    ): StoryBeat {
        val evidenceIds =
            entries.map { it.uid.toString() } +
                media.map { it.uid.toString() } +
                audio.map { it.audio.uid.toString() }
        val moment = pickMoment(entries, audio, dayBucket)
        val emotionalWeight = classifyEmotion(entries, audio)
        return StoryBeat(
            moment = moment,
            context = "",
            emotionalWeight = emotionalWeight,
            evidenceIds = evidenceIds,
        )
    }

    private fun pickMoment(
        entries: List<JournalNote.Text>,
        audio: List<AudioEntryWithTranscript>,
        dayBucket: List<LocalDate>,
    ): String {
        // Concatenate entry text and available transcripts, split sentences, take the
        // first that fits the headline window — a spoken moment is as headline-worthy
        // as a written one.
        val combined =
            (entries.map { it.content } + audio.mapNotNull { it.transcriptionText })
                .joinToString(separator = " ") { it.trim() }
        val sentence =
            splitSentences(combined)
                .map { it.trim() }
                .firstOrNull { it.length in HEADLINE_MIN..HEADLINE_MAX }
        if (sentence != null) return sentence

        // Fallback: day label or phase label.
        return if (dayBucket.size == 1) {
            dayBucket.first().toString()
        } else {
            "${dayBucket.first()} → ${dayBucket.last()}"
        }
    }

    private fun splitSentences(text: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            current.append(c)
            val terminal = c == '.' || c == '!' || c == '?'
            val followedByWs = (i + 1 >= text.length) || text[i + 1].isWhitespace()
            if (terminal && followedByWs) {
                out.add(current.toString())
                current.clear()
                while (i + 1 < text.length && text[i + 1].isWhitespace()) i++
            }
            i++
        }
        if (current.isNotBlank()) out.add(current.toString())
        return out
    }

    private fun classifyEmotion(
        entries: List<JournalNote.Text>,
        audio: List<AudioEntryWithTranscript>,
    ): String {
        val transcripts = audio.mapNotNull { it.transcriptionText }
        if (entries.isEmpty() && transcripts.isEmpty()) return "quiet"
        val joined = (entries.map { it.content } + transcripts).joinToString(separator = " ").lowercase()
        var positive = 0
        var negative = 0
        for (word in POSITIVE_WORDS) {
            if (joined.contains(word)) positive++
        }
        for (word in NEGATIVE_WORDS) {
            if (joined.contains(word)) negative++
        }
        val total = positive + negative
        if (total == 0) return "quiet"
        // Ratio-based: a strong lean (≥70% on one side) classifies as that side,
        // otherwise "mixed". The previous binary scheme tipped to "mixed" the
        // moment a single contrasting word appeared in an otherwise positive day.
        val positiveRatio = positive.toDouble() / total
        return when {
            positiveRatio >= MAJORITY_THRESHOLD -> "joyful"
            (1.0 - positiveRatio) >= MAJORITY_THRESHOLD -> "heavy"
            else -> "mixed"
        }
    }

    private companion object {
        const val DEFAULT_MAX_BEATS: Int = 6
        const val HEADLINE_MIN: Int = 20
        const val HEADLINE_MAX: Int = 120
        private const val MAJORITY_THRESHOLD: Double = 0.70

        // Split a day into multiple beats when GPS points stretch this far apart.
        // 2km is "drove or took transit" — not "walked across the block."
        const val LOCATION_SPLIT_DISTANCE_METERS: Double = 2000.0

        // Journal-vocabulary expanded; "energized", "calm", "alive", and common
        // multi-word phrases like "feel good" are real journal language.
        val POSITIVE_WORDS: Set<String> =
            setOf(
                "happy",
                "joy",
                "joyful",
                "love",
                "excited",
                "grateful",
                "wonderful",
                "amazing",
                "thrilled",
                "proud",
                "delighted",
                "peaceful",
                "blissful",
                "relieved",
                "hopeful",
                "great",
                "energized",
                "calm",
                "alive",
                "fulfilled",
                "content",
                "inspired",
                "playful",
                "tender",
                "warm",
                "feel good",
                "loved",
                "lucky",
                "blessed",
                "centered",
                "gentle",
                "sweet",
                "refreshed",
            )

        val NEGATIVE_WORDS: Set<String> =
            setOf(
                "sad",
                "angry",
                "frustrated",
                "anxious",
                "overwhelmed",
                "exhausted",
                "lonely",
                "miserable",
                "afraid",
                "disappointed",
                "heartbroken",
                "stressed",
                "worried",
                "regret",
                "hurt",
                "ashamed",
                "wrong",
                "burnt out",
                "drained",
                "restless",
                "tense",
                "numb",
                "empty",
                "tired",
                "fed up",
                "grief",
                "lost",
                "stuck",
                "scared",
                "rejected",
                "ignored",
                "irritable",
                "panicked",
                "ashamed",
                "hopeless",
                "discouraged",
            )
    }
}
