package app.logdate.client.domain.timeline

import app.logdate.client.repository.journals.JournalNote

/** Keeps derived context and its complete source provenance separate from provisional displayed entries. */
fun retainAvailableTimelineSummaries(
    previous: Timeline,
    current: Timeline,
): Timeline {
    val previousDays = previous.days.associateBy(TimelineDay::date)
    return current.copy(
        days =
            current.days.map { day ->
                val retained = previousDays[day.date] ?: return@map day
                val pending = day.isSummaryPending
                val provisional = pending && day.isRecentWindow
                val summarySources = retained.retainedSummarySources ?: retained.entries
                val matchingSummary = matchingSources(day.entries.summaryInputs(), summarySources.summaryInputs(), provisional)
                val keepSummary = matchingSummary && day.tldr.isBlank() && retained.tldr.isNotBlank()
                val keepNotice = matchingSummary && pending && retained.summaryUnavailableReason != null
                val keepPeople = matchingSummary && (pending || keepSummary) && retained.people.isNotEmpty()
                val momentSources = retained.retainedMomentSources ?: retained.entries
                val matchingMoments = matchingSources(day.entries.momentInputs(), momentSources.momentInputs(), provisional)
                val richerMoments =
                    day.moments.none { it.inferenceSource == MomentInferenceSource.AI_INFERRED } &&
                        retained.moments.any { it.inferenceSource == MomentInferenceSource.AI_INFERRED }
                val keepMoments = matchingMoments && retained.moments.isNotEmpty() && (pending || richerMoments)
                day.copy(
                    tldr = if (keepSummary) retained.tldr else day.tldr,
                    summaryUnavailableReason =
                        when {
                            keepSummary -> null
                            keepNotice -> retained.summaryUnavailableReason
                            else -> day.summaryUnavailableReason
                        },
                    people = if (keepPeople) retained.people else day.people,
                    moments = if (keepMoments) retained.moments else day.moments,
                    retainedSummarySources = if (keepSummary || keepNotice || keepPeople) summarySources else day.retainedSummarySources,
                    retainedMomentSources = if (keepMoments) momentSources else day.retainedMomentSources,
                )
            },
    )
}

private fun <K, V> matchingSources(
    current: Map<K, V>,
    previous: Map<K, V>,
    provisional: Boolean,
): Boolean =
    current.isNotEmpty() &&
        (current == previous || (provisional && current.all { (id, revision) -> previous[id] == revision }))

private fun List<JournalNote>.summaryInputs() =
    associate { note ->
        note.uid to Triple(note.type, note.creationTimestamp, (note as? JournalNote.Text)?.content)
    }

private fun List<JournalNote>.momentInputs() =
    associate { note ->
        val input =
            when (note) {
                is JournalNote.Text -> note.copy(lastUpdated = note.creationTimestamp, syncVersion = 0)
                is JournalNote.Handwriting -> note.copy(lastUpdated = note.creationTimestamp, syncVersion = 0)
                is JournalNote.Image -> note.copy(lastUpdated = note.creationTimestamp, syncVersion = 0)
                is JournalNote.Video -> note.copy(lastUpdated = note.creationTimestamp, syncVersion = 0)
                is JournalNote.Audio -> note.copy(lastUpdated = note.creationTimestamp, syncVersion = 0, transcript = null)
            }
        note.uid to input
    }
