package app.logdate.client.intelligence.rewind.strategy

import app.logdate.client.intelligence.availability.RewindAITier
import app.logdate.client.intelligence.curation.CurationConfig
import app.logdate.client.intelligence.curation.CurationConfigProvider
import app.logdate.client.intelligence.curation.CurationResult
import app.logdate.client.intelligence.curation.RewindMediaCurator
import app.logdate.client.intelligence.narrative.RewindSequencer
import app.logdate.client.intelligence.rewind.local.LocalQuoteSelector
import app.logdate.client.intelligence.rewind.local.LocalStoryBeatDetector
import app.logdate.client.intelligence.rewind.local.LocalThemeExtractor
import app.logdate.client.intelligence.rewind.local.deriveActivitiesFromThemes
import app.logdate.client.repository.location.LocationHistoryItem
import app.logdate.shared.model.LocationSummary
import app.logdate.shared.model.MapPoint
import app.logdate.shared.model.NarrativeOrigin
import app.logdate.shared.model.RewindMetadata
import app.logdate.shared.model.StoryBeat
import app.logdate.shared.model.WeekNarrative
import app.logdate.shared.model.WeekStatsSnapshot
import io.github.aakira.napier.Napier

/**
 * Pure-local Rewind generator. Runs without any network call — every piece comes from
 * the user's own data.
 *
 * Today's local narrative is intentionally minimal: themes are empty, story beats are
 * a single synthetic catch-all so the curator can still bucket media into a structured
 * Rewind, reflection prompts are empty (per the [WeekNarrative.reflectionPrompts]
 * contract — no templated wellness questions), and the opening panel is a
 * [app.logdate.shared.model.RewindContent.PersonalityCard]. A later step adds proper
 * local theme / beat / quote detection on top of this same shape.
 */
class LocalRewindStrategy(
    private val curator: RewindMediaCurator,
    private val sequencer: RewindSequencer,
    private val themeExtractor: LocalThemeExtractor = LocalThemeExtractor(),
    private val quoteSelector: LocalQuoteSelector = LocalQuoteSelector(),
    private val storyBeatDetector: LocalStoryBeatDetector = LocalStoryBeatDetector(),
    private val configProvider: CurationConfigProvider = CurationConfigProvider.Default,
) : RewindGenerationStrategy {
    override val name: String = STRATEGY_NAME

    override suspend fun produce(input: RewindInput): RewindStrategyOutput {
        val narrative = buildLocalNarrative(input)
        // Bump the per-beat cap for local Rewinds — without real beat detection we have
        // only one synthetic beat, and shipping 4 photos for a whole week would feel
        // anemic. The global cap stays. The user's screenshot-inclusion choice still
        // applies, however, because the hard filter runs the same way.
        val userConfig = configProvider.get()
        val curation =
            curator.curate(
                allMedia = input.media,
                narrative = narrative,
                textEntries = input.textEntries,
                people = input.people,
                locationHistory = input.locationHistory,
                periodStart = input.periodStart,
                periodEnd = input.periodEnd,
                config = LOCAL_CURATION_CONFIG.copy(excludeScreenshots = userConfig.excludeScreenshots),
            )

        val mapPoints = downsampleLocationPath(input.locationHistory)
        val stats = buildLocalStats(input, curation)
        val activities = deriveActivitiesFromThemes(narrative.themes)

        val content =
            sequencer.sequence(
                narrative = narrative,
                curation = curation,
                textEntries = input.textEntries,
                audioEntries = input.audioEntries,
                people = input.people,
                weather = narrative.weatherContext,
                locationPath = mapPoints,
                stats = stats,
                activities = activities,
            )

        val metadata =
            RewindMetadata(
                detectedActivities = activities,
                locationSummary = buildLocationSummary(input.locationHistory),
                milestones = emptyList(),
                peopleHighlighted = input.people.map { it.name },
                reflectionPrompts = emptyList(), // local never templates prompts
                highlightedQuotes = narrative.highlightedQuotes,
                weatherContext = narrative.weatherContext,
                locationPath = mapPoints,
            )

        Napier.d("LocalRewindStrategy produced ${content.size} panels")
        return RewindStrategyOutput(
            narrative = narrative,
            curation = curation,
            content = content,
            metadata = metadata,
            provenance =
                GenerationProvenance(
                    strategy = STRATEGY_NAME,
                    tier = RewindAITier.NONE,
                ),
            weatherContext = narrative.weatherContext,
        )
    }

    /**
     * Builds a synthetic [WeekNarrative] with a single catch-all story beat that holds
     * every media UID from the period. This gives the curator and sequencer the same
     * shape they expect from an LLM-produced narrative, so neither has to special-case
     * the local path.
     */
    private fun buildLocalNarrative(input: RewindInput): WeekNarrative {
        val themes = themeExtractor.extract(input.textEntries.map { it.content })
        val quotes = quoteSelector.select(input.textEntries)
        val detectedBeats =
            storyBeatDetector.detect(
                textEntries = input.textEntries,
                media = input.media,
                audio = input.audioEntries,
                locationHistory = input.locationHistory,
            )
        // Fallback to a single catch-all beat when the period has neither text, media,
        // nor audio — keeps the sequencer happy without special-casing.
        val storyBeats =
            detectedBeats.ifEmpty {
                listOf(
                    StoryBeat(
                        moment = "Your week",
                        context = "A summary of the period",
                        emotionalWeight = "varied",
                        evidenceIds =
                            input.media.map { it.uid.toString() } +
                                input.textEntries.map { it.uid.toString() } +
                                input.audioEntries.map { it.audio.uid.toString() },
                    ),
                )
            }
        return WeekNarrative(
            themes = themes,
            emotionalTone = storyBeats.firstOrNull()?.emotionalWeight ?: "varied",
            storyBeats = storyBeats,
            overallNarrative = "A summary of your week.",
            reflectionPrompts = emptyList(),
            highlightedQuotes = quotes,
            weatherContext = null, // weather fetch belongs to the use case for now
            origin = NarrativeOrigin.LOCAL_HEURISTIC,
        )
    }

    private fun buildLocalStats(
        input: RewindInput,
        curation: CurationResult,
    ): WeekStatsSnapshot {
        val curatedMediaCount = curation.perBeat.values.sumOf { it.size } + curation.freeAgents.size
        val distinctLocations =
            input.locationHistory
                .map { "${it.location.latitude.toInt()},${it.location.longitude.toInt()}" }
                .distinct()
                .size
        return WeekStatsSnapshot(
            photoCount = curatedMediaCount,
            textNoteCount = input.textEntries.size,
            distinctLocations = distinctLocations,
            distinctPeople = input.people.size,
            audioNoteCount = input.audioEntries.size,
        )
    }

    private fun buildLocationSummary(history: List<LocationHistoryItem>): LocationSummary? =
        if (history.isEmpty()) {
            null
        } else {
            LocationSummary(
                distinctLocations =
                    history
                        .map { "${it.location.latitude.toInt()},${it.location.longitude.toInt()}" }
                        .distinct()
                        .size,
                newPlaces = 0,
                primaryLocation = null,
            )
        }

    private fun downsampleLocationPath(history: List<LocationHistoryItem>): List<MapPoint> {
        if (history.isEmpty()) return emptyList()
        val asPoints =
            history.map { item ->
                MapPoint(
                    latitude = item.location.latitude,
                    longitude = item.location.longitude,
                    timestamp = item.timestamp,
                )
            }
        if (asPoints.size <= MAP_POINT_LIMIT) return asPoints
        val step = asPoints.size.toDouble() / MAP_POINT_LIMIT
        val sampled = mutableListOf<MapPoint>()
        var index = 0.0
        while (sampled.size < MAP_POINT_LIMIT - 1 && index.toInt() < asPoints.size) {
            sampled.add(asPoints[index.toInt()])
            index += step
        }
        if (sampled.lastOrNull() != asPoints.last()) sampled.add(asPoints.last())
        return sampled
    }

    private companion object {
        const val STRATEGY_NAME = "local-heuristic"
        private const val MAP_POINT_LIMIT = 50
        val LOCAL_CURATION_CONFIG: CurationConfig =
            CurationConfig(
                maxItemsPerBeat = 16, // one catch-all beat — give it room
                maxTotalMedia = 20,
            )
    }
}
