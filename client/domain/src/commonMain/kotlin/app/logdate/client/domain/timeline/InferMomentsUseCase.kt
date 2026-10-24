package app.logdate.client.domain.timeline

import app.logdate.client.intelligence.AIResult
import app.logdate.client.intelligence.MAX_AI_INPUT_CHARACTERS
import app.logdate.client.intelligence.entity.moments.ExtractedMoment
import app.logdate.client.intelligence.entity.moments.MomentExtractor
import app.logdate.client.repository.audio.AudioTag
import app.logdate.client.repository.audio.AudioTagRepository
import app.logdate.client.repository.journals.JournalNote
import io.github.aakira.napier.Napier
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Infers semantically coherent moments from a day's journal entries.
 *
 * Uses AI when available, falling back to time-of-day + place heuristics.
 */
class InferMomentsUseCase(
    private val momentExtractor: MomentExtractor,
    private val audioTagRepository: AudioTagRepository,
) {
    suspend operator fun invoke(
        date: LocalDate,
        entries: List<JournalNote>,
        places: List<TimelinePlaceVisit>,
    ): List<Moment> {
        if (entries.isEmpty()) return emptyList()

        val inputCharacters =
            entries.sumOf { note ->
                val contentLength =
                    when (note) {
                        is JournalNote.Text -> note.content.length
                        is JournalNote.Image -> note.caption.length
                        is JournalNote.Video -> note.caption.length
                        is JournalNote.Audio, is JournalNote.Handwriting -> 0
                    }
                256L + contentLength + (note.location?.displayName?.length ?: 0)
            }
        if (inputCharacters > MAX_AI_INPUT_CHARACTERS) return inferMomentsHeuristically(date, entries, places)

        val ambientSoundsByNote = collectAmbientSounds(entries)
        val serialized = serializeEntries(entries, ambientSoundsByNote)
        val documentId = "moments_${date.toEpochDays()}"

        return when (val result = momentExtractor.extractMoments(documentId, serialized)) {
            is AIResult.Success -> {
                val moments = result.value.toMoments(date, entries, places)
                if (moments.isNotEmpty()) {
                    moments
                } else {
                    Napier.w(tag = TAG, message = "AI returned empty moments for $date, falling back to heuristic")
                    inferMomentsHeuristically(date, entries, places)
                }
            }

            is AIResult.Unavailable -> {
                Napier.d(tag = TAG, message = "AI unavailable for $date, using heuristic fallback")
                inferMomentsHeuristically(date, entries, places)
            }

            is AIResult.Error -> {
                Napier.e(tag = TAG, message = "AI error for $date, using heuristic fallback")
                inferMomentsHeuristically(date, entries, places)
            }
        }
    }

    /**
     * Pulls the ambient sound detections (birds, rain, traffic, …) for every
     * audio note in [entries] from the on-device tagger's persisted output, so
     * the moment extractor can weave them into the narrative copy. Notes with
     * no detections — older recordings, or ones captured before the tagger
     * model was downloaded — simply contribute an empty list.
     */
    private suspend fun collectAmbientSounds(entries: List<JournalNote>): Map<Uuid, List<AudioTag>> {
        val audioNotes = entries.filterIsInstance<JournalNote.Audio>()
        if (audioNotes.isEmpty()) return emptyMap()
        return audioNotes.associate { audio ->
            audio.uid to runCatching { audioTagRepository.getTagsForNote(audio.uid) }.getOrDefault(emptyList())
        }
    }
}

/**
 * Groups notes into moments using time-of-day buckets and place proximity.
 * Used when AI is unavailable.
 */
internal fun inferMomentsHeuristically(
    date: LocalDate,
    entries: List<JournalNote>,
    places: List<TimelinePlaceVisit>,
): List<Moment> {
    val timezone = TimeZone.currentSystemDefault()

    // Group by (time bucket, place) pairs
    data class GroupKey(
        val bucket: HeuristicTimeBucket,
        val placeId: String?,
    )

    val grouped =
        entries
            .sortedBy { it.creationTimestamp }
            .groupBy { note ->
                val bucket = note.creationTimestamp.toHeuristicBucket(timezone)
                val placeId =
                    note.location?.let { loc ->
                        val lat = loc.effectiveLatitude ?: return@let null
                        val lon = loc.effectiveLongitude ?: return@let null
                        places
                            .find { place ->
                                val pLat = place.latitude ?: return@find false
                                val pLon = place.longitude ?: return@find false
                                isNearby(lat, lon, pLat, pLon)
                            }?.id
                    }
                GroupKey(bucket, placeId)
            }

    return grouped.entries
        .sortedWith(compareBy({ it.key.bucket.ordinal }, { it.key.placeId }))
        .map { (key, notes) ->
            val place = key.placeId?.let { id -> places.find { it.id == id } }
            // Only place-based labels are shown. Bare time-of-day labels
            // are always suppressed — they don't add semantic value.
            buildMomentFromNotes(
                labelPlace = place,
                notes = notes,
                places = if (place != null) listOf(place) else emptyList(),
                inferenceSource = MomentInferenceSource.TIME_OF_DAY_FALLBACK,
            )
        }
}

private fun List<ExtractedMoment>.toMoments(
    date: LocalDate,
    allEntries: List<JournalNote>,
    allPlaces: List<TimelinePlaceVisit>,
): List<Moment> {
    val entriesById = allEntries.associateBy { it.uid.toString() }
    val timezone = TimeZone.currentSystemDefault()

    return mapIndexedNotNull { index, extracted ->
        val sourceNotes = extracted.sourceNoteIds.mapNotNull { entriesById[it] }
        if (sourceNotes.isEmpty()) return@mapIndexedNotNull null

        val startInstant =
            LocalDateTime(
                date.year,
                date.month,
                date.day,
                extracted.estimatedStartHour.coerceIn(0, 23),
                extracted.estimatedStartMinute.coerceIn(0, 59),
            ).toInstant(timezone)

        val endInstant =
            LocalDateTime(
                date.year,
                date.month,
                date.day,
                extracted.estimatedEndHour.coerceIn(0, 23),
                extracted.estimatedEndMinute.coerceIn(0, 59),
            ).toInstant(timezone)

        val momentPlaces =
            sourceNotes
                .mapNotNull { note -> note.location }
                .mapNotNull { loc ->
                    val lat = loc.effectiveLatitude ?: return@mapNotNull null
                    val lon = loc.effectiveLongitude ?: return@mapNotNull null
                    allPlaces.find { place ->
                        val pLat = place.latitude ?: return@find false
                        val pLon = place.longitude ?: return@find false
                        isNearby(lat, lon, pLat, pLon)
                    }
                }.distinctBy { it.id }

        Moment(
            id = sourceNotes.first().uid.toMomentId(startInstant, index),
            label = extracted.label,
            estimatedStart = startInstant,
            estimatedEnd = if (endInstant > startInstant) endInstant else startInstant,
            sourceNotes = sourceNotes,
            textFragments =
                extracted.textFragments.map { fragment ->
                    MomentTextFragment(
                        text = fragment.text,
                        sourceNoteId =
                            runCatching { Uuid.parse(fragment.sourceNoteId) }
                                .getOrElse { sourceNotes.first().uid },
                    )
                },
            media = sourceNotes.flatMap { note -> note.toMomentMedia() },
            audio = sourceNotes.flatMap { note -> note.toMomentAudio() },
            places = momentPlaces,
            people = extracted.people,
            inferenceSource = MomentInferenceSource.AI_INFERRED,
        )
    }
}

private fun Uuid.toMomentId(
    start: Instant,
    index: Int,
): Uuid =
    toLongs { high, low ->
        Uuid.fromLongs(high xor start.toEpochMilliseconds(), low xor index.toLong())
    }

private fun buildMomentFromNotes(
    labelPlace: TimelinePlaceVisit?,
    notes: List<JournalNote>,
    places: List<TimelinePlaceVisit>,
    inferenceSource: MomentInferenceSource,
): Moment {
    val textFragments =
        notes.filterIsInstance<JournalNote.Text>().map { note ->
            MomentTextFragment(text = note.content.take(MAX_MOMENT_PREVIEW_CHARACTERS), sourceNoteId = note.uid)
        }
    val media = notes.flatMap { it.toMomentMedia() }
    val audio = notes.flatMap { it.toMomentAudio() }

    return Moment(
        id = notes.minBy { it.creationTimestamp }.uid,
        label = "",
        estimatedStart = notes.minOf { it.creationTimestamp },
        estimatedEnd = notes.maxOf { it.creationTimestamp },
        sourceNotes = notes,
        textFragments = textFragments,
        media = media,
        audio = audio,
        places = places,
        people = emptyList(), // Heuristic doesn't extract people
        inferenceSource = inferenceSource,
        labelPlace = labelPlace,
    )
}

private fun JournalNote.toMomentMedia(): List<MomentMedia> =
    when (this) {
        is JournalNote.Image -> listOf(MomentMedia(uri = mediaRef, isVideo = false, sourceNoteId = uid))
        is JournalNote.Video -> listOf(MomentMedia(uri = mediaRef, isVideo = true, sourceNoteId = uid))
        is JournalNote.Text, is JournalNote.Handwriting, is JournalNote.Audio -> emptyList()
    }

private fun JournalNote.toMomentAudio(): List<MomentAudio> =
    when (this) {
        is JournalNote.Audio -> listOf(MomentAudio(uri = mediaRef, durationMs = durationMs, sourceNoteId = uid))
        is JournalNote.Text, is JournalNote.Handwriting, is JournalNote.Image, is JournalNote.Video -> emptyList()
    }

private fun serializeEntries(
    entries: List<JournalNote>,
    ambientSoundsByNote: Map<Uuid, List<AudioTag>>,
): String =
    buildString {
        appendLine("Journal entries for the day:")
        appendLine()
        entries.sortedBy { it.creationTimestamp }.forEach { note ->
            appendLine("--- Entry [${note.uid}] at ${note.creationTimestamp} ---")
            when (note) {
                is JournalNote.Handwriting -> {
                    appendLine("Type: original handwriting")
                }

                is JournalNote.Text -> {
                    appendLine("Type: text")
                    note.location?.displayName?.let { appendLine("Location: $it") }
                    appendLine("Content: ${note.content}")
                }

                is JournalNote.Image -> {
                    appendLine("Type: photo")
                    note.location?.displayName?.let { appendLine("Location: $it") }
                    if (note.caption.isNotBlank()) appendLine("Caption: ${note.caption}")
                }

                is JournalNote.Video -> {
                    appendLine("Type: video")
                    note.location?.displayName?.let { appendLine("Location: $it") }
                    if (note.caption.isNotBlank()) appendLine("Caption: ${note.caption}")
                }

                is JournalNote.Audio -> {
                    appendLine("Type: audio recording")
                    appendLine("Duration: ${note.durationMs}ms")
                    note.location?.displayName?.let { appendLine("Location: $it") }
                    val sounds = ambientSoundsByNote[note.uid].orEmpty()
                    if (sounds.isNotEmpty()) {
                        // Top few high-confidence labels in plain language so the
                        // moment extractor can weave them into the narrative
                        // (e.g. "morning walk with birdsong").
                        val phrase =
                            sounds
                                .filter { it.confidence >= AMBIENT_NARRATIVE_CONFIDENCE }
                                .take(MAX_AMBIENT_LABELS)
                                .joinToString(", ") { it.soundName.lowercase() }
                        if (phrase.isNotEmpty()) {
                            appendLine("Ambient sounds: $phrase")
                        }
                    }
                }
            }
            appendLine()
        }
    }

private const val AMBIENT_NARRATIVE_CONFIDENCE = 0.4f
private const val MAX_AMBIENT_LABELS = 4

private enum class HeuristicTimeBucket {
    MORNING,
    AFTERNOON,
    EVENING,
}

private fun Instant.toHeuristicBucket(timezone: TimeZone): HeuristicTimeBucket {
    val hour = toLocalDateTime(timezone).hour
    return when (hour) {
        in 0..11 -> HeuristicTimeBucket.MORNING
        in 12..17 -> HeuristicTimeBucket.AFTERNOON
        else -> HeuristicTimeBucket.EVENING
    }
}

/**
 * Checks if two coordinates are "nearby" (within ~500m).
 */
private fun isNearby(
    lat1: Double,
    lon1: Double,
    lat2: Double,
    lon2: Double,
): Boolean {
    val dlat = lat1 - lat2
    val dlon = lon1 - lon2
    // Rough approximation: 0.005 degrees ~ 500m at mid-latitudes
    return dlat * dlat + dlon * dlon < 0.005 * 0.005
}

private const val TAG = "InferMomentsUseCase"
private const val MAX_MOMENT_PREVIEW_CHARACTERS = 2_000
