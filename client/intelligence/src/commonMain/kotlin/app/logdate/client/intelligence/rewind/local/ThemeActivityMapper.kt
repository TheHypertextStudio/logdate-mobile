package app.logdate.client.intelligence.rewind.local

import app.logdate.shared.model.ActivityType

/**
 * Maps narrative themes (lowercase keywords from [LocalThemeExtractor] or LLM output)
 * to structured [ActivityType] values used in Rewind metadata and the PersonalityCard's
 * dominant-activity headline.
 *
 * Keyword lists are intentionally narrow — we'd rather miss a match and fall back to
 * [ActivityType.MIXED] than mislabel a week. New keywords land here when they prove
 * out in real Rewinds.
 */
fun deriveActivitiesFromThemes(themes: List<String>): List<ActivityType> {
    val activities = mutableSetOf<ActivityType>()
    val lowerThemes = themes.map { it.lowercase() }

    for (theme in lowerThemes) {
        when {
            theme.contains("travel") ||
                theme.contains("trip") ||
                theme.contains("vacation") ||
                theme.contains("flight") ||
                theme.contains("road") -> activities.add(ActivityType.TRAVEL)

            theme.contains("social") ||
                theme.contains("friend") ||
                theme.contains("party") ||
                theme.contains("gathering") ||
                theme.contains("dinner") -> activities.add(ActivityType.SOCIAL)

            theme.contains("work") ||
                theme.contains("project") ||
                theme.contains("deadline") ||
                theme.contains("focus") ||
                theme.contains("productive") -> activities.add(ActivityType.FOCUSED_WORK)

            theme.contains("quiet") ||
                theme.contains("rest") ||
                theme.contains("relax") ||
                theme.contains("solitude") -> activities.add(ActivityType.QUIET)

            theme.contains("milestone") ||
                theme.contains("achievement") ||
                theme.contains("graduation") ||
                theme.contains("birthday") ||
                theme.contains("anniversary") -> activities.add(ActivityType.MILESTONE)
        }
    }

    return activities.toList().ifEmpty { listOf(ActivityType.MIXED) }
}
