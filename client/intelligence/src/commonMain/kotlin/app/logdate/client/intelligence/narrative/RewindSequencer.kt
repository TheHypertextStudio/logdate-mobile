package app.logdate.client.intelligence.narrative

import app.logdate.client.intelligence.curation.CurationResult
import app.logdate.client.intelligence.curation.MediaCandidate
import app.logdate.client.intelligence.rewind.strategy.AudioEntryWithTranscript
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.media.IndexedMedia
import app.logdate.client.text.UiText
import app.logdate.client.text.resolve
import app.logdate.client.text.toUiText
import app.logdate.shared.model.ActivityType
import app.logdate.shared.model.MapPoint
import app.logdate.shared.model.NarrativeOrigin
import app.logdate.shared.model.Person
import app.logdate.shared.model.RewindContent
import app.logdate.shared.model.StoryBeat
import app.logdate.shared.model.TopListItem
import app.logdate.shared.model.TopListKind
import app.logdate.shared.model.WeatherContext
import app.logdate.shared.model.WeekNarrative
import app.logdate.shared.model.WeekStatsSnapshot
import io.github.aakira.napier.Napier
import logdate.client.intelligence.generated.resources.Res
import logdate.client.intelligence.generated.resources.rewind_resolution_tone
import logdate.client.intelligence.generated.resources.rewind_transition_and_then
import logdate.client.intelligence.generated.resources.rewind_transition_breakthrough
import logdate.client.intelligence.generated.resources.rewind_transition_evenings
import logdate.client.intelligence.generated.resources.rewind_transition_meanwhile
import logdate.client.intelligence.generated.resources.rewind_transition_things_shifted
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Sequences a Rewind into a narrative-driven story.
 *
 * The sequencer's job is composition only — selection is done by
 * [app.logdate.client.intelligence.curation.RewindMediaCurator] upstream. Every emitted
 * panel is one of three things: an LLM-cited piece of evidence, a top-up significance
 * pick, or a structural panel (opening / transition / map / weather / personality / top
 * lists / resolution).
 *
 * Opening / resolution behavior is keyed off [WeekNarrative.origin]: LLM origins get an
 * AI-written narrative opening / resolution, [NarrativeOrigin.LOCAL_HEURISTIC] gets a
 * structural [RewindContent.PersonalityCard] opening and skips the resolution sentence
 * that would feel hollow without a real LLM behind it.
 *
 * Transition and closing sentences are saved with the Rewind, so they are written once in the
 * device language. [resolveText] turns them into words; tests pass a stand-in because they cannot
 * read Compose resources.
 */
class RewindSequencer(
    private val resolveText: suspend (UiText) -> String = { text -> text.resolve() },
) {
    /**
     * Composes the panel list for a Rewind.
     *
     * @param narrative The (LLM- or locally-generated) understanding of the week.
     * @param curation Pre-curated media. The sequencer consumes [CurationResult.perBeat]
     *   for in-beat panels and [CurationResult.freeAgents] for structural decorations.
     * @param textEntries All text journal entries from the period — used to attach text
     *   quotes cited by story beats' `evidenceIds`.
     * @param audioEntries All audio entries from the period, paired with whatever
     *   transcript is available — used to attach audio moments cited by story beats'
     *   `evidenceIds`, the same way text entries are.
     * @param people People extracted from the period — used to build a [TopListKind.PEOPLE]
     *   card when the count is high enough.
     * @param weather Atmospheric context for the period, or null when unavailable.
     * @param locationPath Downsampled location path; rendered as a [RewindContent.MapPanel]
     *   when there are enough points to read as movement.
     * @param stats Counts that populate the [RewindContent.PersonalityCard].
     * @param activities Activity classification for the period, used to choose the
     *   personality card's dominant-activity headline.
     */
    suspend fun sequence(
        narrative: WeekNarrative,
        curation: CurationResult,
        textEntries: List<JournalNote.Text>,
        audioEntries: List<AudioEntryWithTranscript> = emptyList(),
        people: List<Person>,
        weather: WeatherContext?,
        locationPath: List<MapPoint>,
        stats: WeekStatsSnapshot,
        activities: List<ActivityType>,
    ): List<RewindContent> {
        Napier.d("Sequencing narrative (${narrative.origin}) with ${narrative.storyBeats.size} beats")

        val allTimestamps =
            textEntries.map { it.creationTimestamp } +
                audioEntries.map { it.audio.creationTimestamp } +
                curation.perBeat.values
                    .flatten()
                    .map { it.media.timestamp } +
                curation.freeAgents.map { it.media.timestamp }
        val earliest = allTimestamps.minOrNull() ?: Instant.DISTANT_PAST
        val latest = allTimestamps.maxOrNull() ?: Instant.DISTANT_FUTURE

        val panels = mutableListOf<RewindContent>()

        // 1. Opening — narrative when an LLM was involved, personality card when not.
        panels.add(createOpening(narrative, stats, activities, earliest))

        // 2. Story beats. Transitions between, evidence and curated media inside.
        narrative.storyBeats.forEachIndexed { index, beat ->
            if (index > 0) {
                val transition = createTransition(beat, narrative.storyBeats[index - 1])
                transition?.let { panels.add(it) }
            }
            panels.addAll(createBeatPanels(beat, index, textEntries, audioEntries, curation))
        }

        // 3. Structural decorations from the free-agent pool — map, weather, top lists.
        //    These appear after the last story beat and before the resolution, so the
        //    "Wrapped" insight peak lands near the end where attention is highest.
        if (locationPath.size >= MIN_MAP_POINTS && spansMeaningfulDistance(locationPath)) {
            panels.add(
                RewindContent.MapPanel(
                    timestamp = latest,
                    sourceId = Uuid.random(),
                    locationPath = locationPath,
                ),
            )
        }
        weather?.let {
            panels.add(
                RewindContent.WeatherPanel(
                    timestamp = latest,
                    sourceId = Uuid.random(),
                    weather = it,
                ),
            )
        }
        topListsFor(narrative, people, curation)
            .forEach { panels.add(it.copy(timestamp = latest, sourceId = Uuid.random())) }

        // 4. Closing — LLM-origin only. Local-origin Rewinds end on the top-list peak.
        if (narrative.origin != NarrativeOrigin.LOCAL_HEURISTIC) {
            panels.add(createResolution(narrative, latest))
        }

        Napier.d("Generated ${panels.size} panels from narrative")
        return panels
    }

    /**
     * First panel of the Rewind. Narrative opening for LLM origins, structural
     * personality card for local-heuristic origins.
     */
    private fun createOpening(
        narrative: WeekNarrative,
        stats: WeekStatsSnapshot,
        activities: List<ActivityType>,
        earliest: Instant,
    ): RewindContent =
        when (narrative.origin) {
            NarrativeOrigin.LLM, NarrativeOrigin.QUOTES_ONLY_LLM -> {
                val sentences = narrative.overallNarrative.split(". ")
                val openingText = if (sentences.size > 1) sentences.first() + "." else narrative.overallNarrative
                RewindContent.NarrativeContext(
                    timestamp = earliest,
                    sourceId = Uuid.random(),
                    contextText = openingText,
                    backgroundImage = null,
                )
            }

            NarrativeOrigin.LOCAL_HEURISTIC -> {
                RewindContent.PersonalityCard(
                    timestamp = earliest,
                    sourceId = Uuid.random(),
                    stats = stats,
                    dominantActivity = activities.firstOrNull() ?: ActivityType.MIXED,
                )
            }
        }

    private suspend fun createTransition(
        currentBeat: StoryBeat,
        previousBeat: StoryBeat,
    ): RewindContent.Transition? {
        val transitionText =
            when {
                previousBeat.emotionalWeight.contains("joyful", ignoreCase = true) &&
                    currentBeat.emotionalWeight.contains("melancholy", ignoreCase = true) -> {
                    Res.string.rewind_transition_things_shifted
                }

                previousBeat.emotionalWeight.contains("exhausted", ignoreCase = true) &&
                    currentBeat.emotionalWeight.contains("triumphant", ignoreCase = true) -> {
                    Res.string.rewind_transition_breakthrough
                }

                else -> {
                    val connector =
                        when {
                            currentBeat.context.contains("meanwhile", ignoreCase = true) -> Res.string.rewind_transition_meanwhile
                            currentBeat.context.contains("evening", ignoreCase = true) -> Res.string.rewind_transition_evenings
                            else -> Res.string.rewind_transition_and_then
                        }
                    connector
                }
            }

        return RewindContent.Transition(
            timestamp = Instant.DISTANT_PAST,
            sourceId = Uuid.random(),
            transitionText = resolveText(transitionText.toUiText()),
        )
    }

    /**
     * Builds the panel block for one story beat. Pulls cited text quotes plus the
     * curator's pre-selected media for this beat index, stamps significance scores onto
     * media panels, and sorts chronologically within the block.
     */
    private fun createBeatPanels(
        beat: StoryBeat,
        beatIndex: Int,
        textEntries: List<JournalNote.Text>,
        audioEntries: List<AudioEntryWithTranscript>,
        curation: CurationResult,
    ): List<RewindContent> {
        val panels = mutableListOf<RewindContent>()
        val evidenceIds = beat.evidenceIds.toSet()

        // Text evidence — pull cited entries.
        textEntries
            .filter { it.uid.toString() in evidenceIds }
            .forEach { entry ->
                panels.add(
                    RewindContent.TextNote(
                        timestamp = entry.creationTimestamp,
                        sourceId = entry.uid,
                        content = entry.content,
                    ),
                )
            }

        // Audio evidence — same citation mechanism as text. A transcript is text, so
        // an audio entry's moment is cited the same way a written one is; playback
        // works whether or not a transcript was ready by generation time.
        audioEntries
            .filter { it.audio.uid.toString() in evidenceIds }
            .forEach { entry ->
                panels.add(
                    RewindContent.AudioNote(
                        timestamp = entry.audio.creationTimestamp,
                        sourceId = entry.audio.uid,
                        uri = entry.audio.mediaRef,
                        durationMs = entry.audio.durationMs,
                        transcriptionText = entry.transcriptionText,
                    ),
                )
            }

        // Media panels — drawn from the curator's per-beat selection, with the
        // significance score stamped on from the curation result.
        val beatMedia: List<MediaCandidate> = curation.perBeat[beatIndex].orEmpty()
        beatMedia.forEach { c ->
            val score = curation.sigByMediaUid[c.media.uid]
            when (val m = c.media) {
                is IndexedMedia.Image -> {
                    panels.add(
                        RewindContent.Image(
                            timestamp = m.timestamp,
                            sourceId = m.uid,
                            uri = m.uri,
                            caption = m.caption,
                            significanceScore = score,
                        ),
                    )
                }

                is IndexedMedia.Video -> {
                    panels.add(
                        RewindContent.Video(
                            timestamp = m.timestamp,
                            sourceId = m.uid,
                            uri = m.uri,
                            caption = m.caption,
                            duration = m.duration,
                            significanceScore = score,
                        ),
                    )
                }
            }
        }

        // When the beat has no evidence in the period's content, fall back to a context
        // panel so the beat still shows up in the story.
        if (panels.isEmpty()) {
            Napier.w("No evidence found for beat: ${beat.moment}")
            panels.add(
                RewindContent.NarrativeContext(
                    timestamp = Instant.DISTANT_PAST,
                    sourceId = Uuid.random(),
                    contextText = beat.moment,
                    backgroundImage = null,
                ),
            )
        }

        return panels.sortedBy { it.timestamp }
    }

    /**
     * Builds the optional top-N list panels.
     *
     * - PEOPLE list when there are at least two recurring people.
     * - QUOTES list when the narrative carries at least two highlighted quotes (the
     *   detail screen also shows these inline as Wrapped-style spotlight cards).
     * - PLACES is deferred until the location module surfaces per-place tallies.
     */
    private fun topListsFor(
        narrative: WeekNarrative,
        people: List<Person>,
        @Suppress("UNUSED_PARAMETER") curation: CurationResult,
    ): List<RewindContent.TopList> {
        val out = mutableListOf<RewindContent.TopList>()

        if (people.size >= 2) {
            out.add(
                RewindContent.TopList(
                    timestamp = Instant.DISTANT_PAST,
                    sourceId = Uuid.random(),
                    kind = TopListKind.PEOPLE,
                    items = people.take(5).map { TopListItem(label = it.name) },
                ),
            )
        }

        if (narrative.highlightedQuotes.size >= 2) {
            out.add(
                RewindContent.TopList(
                    timestamp = Instant.DISTANT_PAST,
                    sourceId = Uuid.random(),
                    kind = TopListKind.QUOTES,
                    items =
                        narrative.highlightedQuotes.take(4).map {
                            TopListItem(label = it.text, subtitle = it.whyItHits)
                        },
                ),
            )
        }
        return out
    }

    private suspend fun createResolution(
        narrative: WeekNarrative,
        latestTimestamp: Instant,
    ): RewindContent.NarrativeContext {
        val sentences = narrative.overallNarrative.split(". ")
        val resolutionText =
            if (sentences.size > 1) {
                sentences.drop(1).joinToString(". ")
            } else {
                resolveText(Res.string.rewind_resolution_tone.toUiText(narrative.emotionalTone))
            }
        return RewindContent.NarrativeContext(
            timestamp = latestTimestamp,
            sourceId = Uuid.random(),
            contextText = resolutionText,
            backgroundImage = null,
        )
    }

    /**
     * Map panel threshold check — must span at least ~1km. Uses a coarse diagonal of the
     * lat/lon bounding box to decide; the renderer doesn't need precise distance.
     */
    private fun spansMeaningfulDistance(path: List<MapPoint>): Boolean {
        if (path.size < 2) return false
        val lats = path.map { it.latitude }
        val lons = path.map { it.longitude }
        val latSpan = (lats.max() - lats.min())
        val lonSpan = (lons.max() - lons.min())
        // 0.009 degrees ≈ 1km at the equator. Diagonal good-enough heuristic.
        return latSpan + lonSpan > 0.009
    }

    private companion object {
        private const val MIN_MAP_POINTS = 3
    }
}
