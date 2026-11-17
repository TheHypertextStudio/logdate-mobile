package app.logdate.client.intelligence.narrative

import app.logdate.client.text.UiText
import app.logdate.client.text.resolve
import app.logdate.client.text.toUiText
import app.logdate.shared.model.NarrativeOrigin
import app.logdate.shared.model.Rewind
import app.logdate.shared.model.YearChapter
import app.logdate.shared.model.YearNarrative
import logdate.client.intelligence.generated.resources.Res
import logdate.client.intelligence.generated.resources.year_narrative_chapter_early_year
import logdate.client.intelligence.generated.resources.year_narrative_chapter_late_year
import logdate.client.intelligence.generated.resources.year_narrative_chapter_spring
import logdate.client.intelligence.generated.resources.year_narrative_chapter_summary_quiet
import logdate.client.intelligence.generated.resources.year_narrative_chapter_summary_theme
import logdate.client.intelligence.generated.resources.year_narrative_chapter_summer
import logdate.client.intelligence.generated.resources.year_narrative_emotional_arc
import logdate.client.intelligence.generated.resources.year_narrative_months_first_quarter
import logdate.client.intelligence.generated.resources.year_narrative_months_fourth_quarter
import logdate.client.intelligence.generated.resources.year_narrative_months_second_quarter
import logdate.client.intelligence.generated.resources.year_narrative_months_third_quarter
import logdate.client.intelligence.generated.resources.year_narrative_overall
import logdate.client.intelligence.generated.resources.year_narrative_overall_with_theme
import org.jetbrains.compose.resources.StringResource

/**
 * Builds a [YearNarrative] from a year's weekly rewinds without calling an LLM.
 *
 * Used by [app.logdate.client.domain.rewind.GenerateAnnualRewindUseCase] when AI
 * synthesis is unavailable — so the user still gets a Year in Review (organized
 * into quarter-chapters) instead of an error.
 *
 * Tone is intentionally factual: we know which themes / activities / people
 * appeared most, and that's what we tell back. No invented prompts.
 *
 * The narrative is saved with the Rewind, so its sentences are written once in the device
 * language. [resolveText] turns them into words; tests pass a stand-in because they cannot read
 * Compose resources.
 */
suspend fun buildLocalYearNarrative(
    weeklyRewinds: List<Rewind>,
    resolveText: suspend (UiText) -> String = { text -> text.resolve() },
): YearNarrative {
    val themes = aggregateTopThemes(weeklyRewinds, max = MAX_YEAR_THEMES)
    val chapters = buildQuarterChapters(weeklyRewinds, resolveText)
    val topTheme = themes.firstOrNull()
    val weekCount = weeklyRewinds.size
    val overall =
        if (topTheme != null) {
            Res.plurals.year_narrative_overall_with_theme.toUiText(weekCount, topTheme, weekCount)
        } else {
            Res.plurals.year_narrative_overall.toUiText(weekCount, weekCount)
        }

    return YearNarrative(
        chapters = chapters,
        overallNarrative = resolveText(overall),
        yearThemes = themes,
        emotionalArc = resolveText(Res.string.year_narrative_emotional_arc.toUiText()),
        reflectionPrompts = emptyList(),
        origin = NarrativeOrigin.LOCAL_HEURISTIC,
    )
}

private fun aggregateTopThemes(
    weeklyRewinds: List<Rewind>,
    max: Int,
): List<String> =
    weeklyRewinds
        .flatMap { it.metadata?.detectedActivities ?: emptyList() }
        .map { it.name.lowercase() }
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedByDescending { it.value }
        .take(max)
        .map { it.key }

private suspend fun buildQuarterChapters(
    weeklyRewinds: List<Rewind>,
    resolveText: suspend (UiText) -> String,
): List<YearChapter> {
    if (weeklyRewinds.isEmpty()) return emptyList()
    return (0 until QUARTERS).map { q ->
        val startIdx = (weeklyRewinds.size * q) / QUARTERS
        val endIdx = (weeklyRewinds.size * (q + 1)) / QUARTERS
        val weeks = weeklyRewinds.subList(startIdx, endIdx)
        val chapterThemes =
            weeks
                .flatMap { it.metadata?.detectedActivities ?: emptyList() }
                .map { it.name.lowercase() }
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .take(MAX_CHAPTER_THEMES)
                .map { it.key }
        val summary =
            if (chapterThemes.isNotEmpty()) {
                Res.string.year_narrative_chapter_summary_theme.toUiText(chapterThemes.first())
            } else {
                Res.string.year_narrative_chapter_summary_quiet.toUiText()
            }
        YearChapter(
            name = resolveText(QUARTER_NAMES[q].toUiText()),
            summary = resolveText(summary),
            monthRange = resolveText(QUARTER_MONTH_RANGES[q].toUiText()),
            emotionalTone = "varied",
            keyWeekIndices = (startIdx until endIdx).toList(),
            themes = chapterThemes,
        )
    }
}

private const val MAX_YEAR_THEMES: Int = 5
private const val MAX_CHAPTER_THEMES: Int = 3
private const val QUARTERS: Int = 4
private val QUARTER_NAMES: List<StringResource> =
    listOf(
        Res.string.year_narrative_chapter_early_year,
        Res.string.year_narrative_chapter_spring,
        Res.string.year_narrative_chapter_summer,
        Res.string.year_narrative_chapter_late_year,
    )
private val QUARTER_MONTH_RANGES: List<StringResource> =
    listOf(
        Res.string.year_narrative_months_first_quarter,
        Res.string.year_narrative_months_second_quarter,
        Res.string.year_narrative_months_third_quarter,
        Res.string.year_narrative_months_fourth_quarter,
    )
