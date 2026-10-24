package app.logdate.client.intelligence.rewind.strategy

import app.logdate.client.intelligence.AIResult
import app.logdate.client.intelligence.availability.RewindAITier
import app.logdate.client.intelligence.curation.CurationConfigProvider
import app.logdate.client.intelligence.curation.RewindMediaCurator
import app.logdate.client.intelligence.narrative.RewindSequencer
import app.logdate.client.intelligence.narrative.WeekNarrativeSynthesizer
import app.logdate.client.intelligence.weather.WeatherFetchLocation
import app.logdate.client.repository.location.LocationHistoryItem
import app.logdate.shared.model.ActivityType
import app.logdate.shared.model.LocationSummary
import app.logdate.shared.model.MapPoint
import app.logdate.shared.model.RewindMetadata
import io.github.aakira.napier.Napier

/**
 * Full AI-narrative Rewind generator.
 *
 * Calls [WeekNarrativeSynthesizer] for a structured story understanding, then runs the
 * curator + sequencer to compose the panel list. When the LLM is unavailable or returns
 * an error mid-flight, internally falls back to [LocalRewindStrategy] so the caller
 * doesn't have to retry — the produced output records the fall-through in
 * [GenerationProvenance.fellBackFrom].
 */
class FullLLMRewindStrategy(
    private val narrativeSynthesizer: WeekNarrativeSynthesizer,
    private val curator: RewindMediaCurator,
    private val sequencer: RewindSequencer,
    private val localFallback: LocalRewindStrategy,
    private val configProvider: CurationConfigProvider = CurationConfigProvider.Default,
) : RewindGenerationStrategy {
    override val name: String = STRATEGY_NAME

    override suspend fun produce(input: RewindInput): RewindStrategyOutput {
        val primaryLocation = computePrimaryLocation(input)
        val narrativeResult =
            narrativeSynthesizer.synthesize(
                weekId = input.weekId,
                textEntries = input.textEntries,
                media = input.media,
                audio = input.audioEntries,
                people = input.people,
                primaryLocation = primaryLocation,
                periodStart = input.periodStart,
                periodEnd = input.periodEnd,
                useCached = true,
            )

        val narrative =
            when (narrativeResult) {
                is AIResult.Success -> {
                    narrativeResult.value
                }

                is AIResult.Unavailable -> {
                    Napier.w("FullLLMRewindStrategy: narrative unavailable (${narrativeResult.reason}); falling back to local")
                    return fallback(input, narrativeResult.reason)
                }

                is AIResult.Error -> {
                    Napier.w("FullLLMRewindStrategy: narrative errored; falling back to local")
                    return fallback(input, aiUnavailableReason = null)
                }
            }

        val curation =
            curator.curate(
                allMedia = input.media,
                narrative = narrative,
                textEntries = input.textEntries,
                people = input.people,
                locationHistory = input.locationHistory,
                periodStart = input.periodStart,
                periodEnd = input.periodEnd,
                config = configProvider.get(),
            )

        val activities = deriveActivities(narrative.themes)
        val mapPoints = downsampleLocationPath(input.locationHistory)
        val stats =
            app.logdate.shared.model.WeekStatsSnapshot(
                photoCount = curation.perBeat.values.sumOf { it.size } + curation.freeAgents.size,
                textNoteCount = input.textEntries.size,
                distinctLocations =
                    input.locationHistory
                        .map { "${it.location.latitude.toInt()},${it.location.longitude.toInt()}" }
                        .distinct()
                        .size,
                distinctPeople = input.people.size,
                audioNoteCount = input.audioEntries.size,
            )

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
                milestones =
                    narrative.storyBeats
                        .filter { it.emotionalWeight.lowercase() in MILESTONE_TONES }
                        .map { it.moment },
                peopleHighlighted = input.people.map { it.name },
                reflectionPrompts = narrative.reflectionPrompts,
                highlightedQuotes = narrative.highlightedQuotes,
                weatherContext = narrative.weatherContext,
                locationPath = mapPoints,
            )

        return RewindStrategyOutput(
            narrative = narrative,
            curation = curation,
            content = content,
            metadata = metadata,
            provenance =
                GenerationProvenance(
                    strategy = STRATEGY_NAME,
                    tier = RewindAITier.FULL,
                    llmCalls = 1,
                ),
            weatherContext = narrative.weatherContext,
        )
    }

    private suspend fun fallback(
        input: RewindInput,
        aiUnavailableReason: app.logdate.client.intelligence.AIUnavailableReason?,
    ): RewindStrategyOutput {
        val local = localFallback.produce(input)
        return local.copy(
            provenance =
                local.provenance.copy(
                    aiUnavailableReason = aiUnavailableReason,
                    fellBackFrom = STRATEGY_NAME,
                ),
        )
    }

    private fun computePrimaryLocation(input: RewindInput): WeatherFetchLocation? {
        if (input.locationHistory.isEmpty()) return null
        val avgLat = input.locationHistory.sumOf { it.location.latitude } / input.locationHistory.size
        val avgLon = input.locationHistory.sumOf { it.location.longitude } / input.locationHistory.size
        return WeatherFetchLocation(latitude = avgLat, longitude = avgLon)
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

    private fun deriveActivities(themes: List<String>): List<ActivityType> {
        val activities = mutableSetOf<ActivityType>()
        themes.map { it.lowercase() }.forEach { theme ->
            when {
                listOf("travel", "trip", "vacation", "flight", "road").any { theme.contains(it) } -> {
                    activities.add(ActivityType.TRAVEL)
                }

                listOf("social", "friend", "party", "gathering", "dinner").any { theme.contains(it) } -> {
                    activities.add(ActivityType.SOCIAL)
                }

                listOf("work", "project", "deadline", "focus", "productive").any { theme.contains(it) } -> {
                    activities.add(ActivityType.FOCUSED_WORK)
                }

                listOf("quiet", "rest", "relax", "solitude").any { theme.contains(it) } -> {
                    activities.add(ActivityType.QUIET)
                }

                listOf("milestone", "achievement", "graduation", "birthday", "anniversary").any { theme.contains(it) } -> {
                    activities.add(ActivityType.MILESTONE)
                }
            }
        }
        return activities.toList().ifEmpty { listOf(ActivityType.MIXED) }
    }

    private companion object {
        const val STRATEGY_NAME = "full-llm"
        private const val MAP_POINT_LIMIT = 50
        private val MILESTONE_TONES = setOf("triumphant", "milestone", "significant", "proud")
    }
}
