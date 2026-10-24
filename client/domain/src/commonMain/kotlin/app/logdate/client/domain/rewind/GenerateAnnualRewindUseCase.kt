package app.logdate.client.domain.rewind

import app.logdate.client.intelligence.AIResult
import app.logdate.client.intelligence.narrative.AnnualRewindSequencer
import app.logdate.client.intelligence.narrative.WeekSummaryInput
import app.logdate.client.intelligence.narrative.YearNarrativeSynthesizer
import app.logdate.client.intelligence.narrative.buildLocalYearNarrative
import app.logdate.client.repository.rewind.RewindGenerationManager
import app.logdate.client.repository.rewind.RewindRepository
import app.logdate.client.text.UiText
import app.logdate.client.text.resolve
import app.logdate.client.text.toUiText
import app.logdate.shared.model.ActivityType
import app.logdate.shared.model.LocationSummary
import app.logdate.shared.model.Rewind
import app.logdate.shared.model.RewindContent
import app.logdate.shared.model.RewindGenerationRequest
import app.logdate.shared.model.RewindMetadata
import app.logdate.shared.model.YearNarrative
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import logdate.client.domain.generated.resources.Res
import logdate.client.domain.generated.resources.rewind_title_annual
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

/**
 * Generates an annual "Year in Review" rewind by reading back existing weekly
 * rewinds and feeding their summaries to a year-level LLM synthesis.
 *
 * This use case does NOT re-process raw journal entries. It consumes the output
 * of the weekly pipeline — each weekly rewind's `overallNarrative`, themes,
 * people, and milestones — and compresses them into 5-8 "chapters" that trace
 * the arc of the year. The LLM input is ~3000-5000 tokens for 52 weeks, well
 * within a single call.
 *
 * The title and any locally written narrative are saved with the Rewind, so they are written
 * once in the device language at generation time. [resolveText] turns that text into words;
 * tests pass a stand-in because they cannot read Compose resources.
 */
class GenerateAnnualRewindUseCase(
    private val rewindRepository: RewindRepository,
    private val generationManager: RewindGenerationManager,
    private val yearNarrativeSynthesizer: YearNarrativeSynthesizer,
    private val annualRewindSequencer: AnnualRewindSequencer,
    private val resolveText: suspend (UiText) -> String = { text -> text.resolve() },
) {
    /**
     * Generates an annual rewind for [year].
     *
     * @param year Calendar year (e.g., 2025).
     * @return The generation result. On success, the rewind is already saved to the
     *   repository and will appear in the overview list's pastRewinds stream.
     */
    suspend operator fun invoke(year: Int): GenerateBasicRewindResult {
        val timezone = TimeZone.currentSystemDefault()
        val startTime = LocalDate(year, 1, 1).atStartOfDayIn(timezone)
        val endTime = LocalDate(year + 1, 1, 1).atStartOfDayIn(timezone)

        Napier.d("Generating annual rewind for $year ($startTime to $endTime)")

        // Dedup: skip if an annual rewind already exists for this year.
        val existing = rewindRepository.getRewindBetween(startTime, endTime).firstOrNull()
        if (existing != null) {
            Napier.d("Annual rewind already exists for $year")
            return GenerateBasicRewindResult.Success(existing)
        }

        if (generationManager.isGenerationInProgress(startTime, endTime)) {
            return GenerateBasicRewindResult.AlreadyInProgress
        }

        val request =
            try {
                generationManager.requestGeneration(startTime, endTime)
            } catch (e: Exception) {
                Napier.e("Failed to create annual generation request", e)
                return GenerateBasicRewindResult.Error("Failed to start annual rewind generation", e)
            }

        try {
            // Collect all weekly/milestone rewinds for the year.
            val allRewinds = rewindRepository.getRewindsInRange(startTime, endTime).firstOrNull() ?: emptyList()
            // Filter out any existing annual rewinds (short-circuit: span > 30 days).
            val weeklyRewinds =
                allRewinds.filter { rewind ->
                    (rewind.endDate - rewind.startDate) < 30.days
                }

            if (weeklyRewinds.size < MIN_WEEKLY_REWINDS) {
                updateStatus(request.id, RewindGenerationRequest.Status.FAILED, "Not enough weekly rewinds")
                return GenerateBasicRewindResult.NoContent
            }

            Napier.d("Found ${weeklyRewinds.size} weekly rewinds for $year")

            // Build rich LLM input from each weekly rewind — not just the 2-sentence
            // narrative, but story beats, verbatim quotes, and reflection observations
            // so the year synthesizer has enough texture to find cross-week arcs.
            val summaries =
                weeklyRewinds.mapIndexed { index, rewind ->
                    val metadata = rewind.metadata
                    WeekSummaryInput(
                        weekIndex = index,
                        startDate =
                            rewind.startDate
                                .toLocalDateTime(timezone)
                                .date
                                .toString(),
                        endDate =
                            rewind.endDate
                                .toLocalDateTime(timezone)
                                .date
                                .toString(),
                        overallNarrative = rewind.title,
                        themes = metadata?.detectedActivities?.map { it.name } ?: emptyList(),
                        emotionalTone = metadata?.detectedActivities?.firstOrNull()?.name ?: "mixed",
                        peopleHighlighted = metadata?.peopleHighlighted ?: emptyList(),
                        milestones = metadata?.milestones ?: emptyList(),
                        storyBeatMoments =
                            rewind.content
                                .filterIsInstance<RewindContent.NarrativeContext>()
                                .map { it.contextText },
                        highlightedQuotes = metadata?.highlightedQuotes?.map { it.text } ?: emptyList(),
                        reflectionObservations = metadata?.reflectionPrompts?.map { it.observation } ?: emptyList(),
                    )
                }

            // Single LLM call with all weekly summaries.
            val narrativeResult = yearNarrativeSynthesizer.synthesize(yearId = "$year", weeklySummaries = summaries)

            val narrative =
                when (narrativeResult) {
                    is AIResult.Success -> {
                        narrativeResult.value
                    }

                    is AIResult.Unavailable -> {
                        Napier.w("AI unavailable for annual rewind — falling back to local narrative")
                        buildLocalYearNarrative(year = year, weeklyRewinds = weeklyRewinds, resolveText = resolveText)
                    }

                    is AIResult.Error -> {
                        Napier.w("AI error for annual rewind — falling back to local narrative", narrativeResult.throwable)
                        buildLocalYearNarrative(year = year, weeklyRewinds = weeklyRewinds, resolveText = resolveText)
                    }
                }

            // Build panels from the narrative + weekly rewinds.
            val content = annualRewindSequencer.sequence(narrative, weeklyRewinds, year)

            // Aggregate metadata from all weekly rewinds.
            val metadata = aggregateMetadata(weeklyRewinds, narrative)

            val rewind =
                Rewind(
                    uid = Uuid.random(),
                    startDate = startTime,
                    endDate = endTime,
                    generationDate = Clock.System.now(),
                    label = "$year",
                    title = resolveText(Res.string.rewind_title_annual.toUiText(year)),
                    content = content,
                    metadata = metadata,
                )

            rewindRepository.saveRewind(rewind)
            updateStatus(request.id, RewindGenerationRequest.Status.COMPLETED)
            Napier.i("Generated annual rewind for $year with ${content.size} panels")
            return GenerateBasicRewindResult.Success(rewind)
        } catch (e: Exception) {
            Napier.e("Failed to generate annual rewind for $year", e)
            updateStatus(request.id, RewindGenerationRequest.Status.FAILED, e.message)
            return GenerateBasicRewindResult.Error("Annual rewind generation failed", e)
        }
    }

    private fun aggregateMetadata(
        weeklyRewinds: List<Rewind>,
        narrative: YearNarrative,
    ): RewindMetadata {
        val allActivities =
            weeklyRewinds
                .flatMap { it.metadata?.detectedActivities ?: emptyList() }
                .groupBy { it }
                .entries
                .sortedByDescending { it.value.size }
                .map { it.key }
                .take(5)
        val allPeople =
            weeklyRewinds
                .flatMap { it.metadata?.peopleHighlighted ?: emptyList() }
                .groupBy { it }
                .entries
                .sortedByDescending { it.value.size }
                .map { it.key }
                .take(10)
        val allMilestones =
            weeklyRewinds
                .flatMap { it.metadata?.milestones ?: emptyList() }
                .distinct()
        val totalLocations =
            weeklyRewinds.sumOf { it.metadata?.locationSummary?.distinctLocations ?: 0 }

        return RewindMetadata(
            detectedActivities = allActivities.ifEmpty { listOf(ActivityType.MIXED) },
            locationSummary =
                if (totalLocations > 0) {
                    LocationSummary(distinctLocations = totalLocations, newPlaces = 0, primaryLocation = null)
                } else {
                    null
                },
            milestones = allMilestones,
            peopleHighlighted = allPeople,
            reflectionPrompts = narrative.reflectionPrompts,
        )
    }

    private suspend fun updateStatus(
        requestId: Uuid,
        status: RewindGenerationRequest.Status,
        details: String? = null,
    ) {
        try {
            if (status == RewindGenerationRequest.Status.CANCELLED) {
                generationManager.cancelGeneration(requestId)
            } else {
                generationManager.updateRequestStatus(requestId, status, details)
            }
        } catch (e: Exception) {
            Napier.w("Failed to update annual generation status", e)
        }
    }

    private companion object {
        const val MIN_WEEKLY_REWINDS = 4
    }
}
