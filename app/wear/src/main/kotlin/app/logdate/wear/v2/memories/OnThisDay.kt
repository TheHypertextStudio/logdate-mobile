package app.logdate.wear.v2.memories

import app.logdate.client.domain.recommendation.MemoryRecallData
import app.logdate.client.domain.recommendation.WidgetContentType
import app.logdate.client.domain.today.MoodNoteTag
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.wear.v2.library.LibraryWindow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.datetime.LocalDate

/** What On this day on the watch recalls: words and voice, since photos stay on the phone. */
val WATCH_RECALL_TYPES: Set<WidgetContentType> = setOf(WidgetContentType.TEXT, WidgetContentType.AUDIO)

/**
 * A day from a past year to revisit. [leadMoment] opens it: the day's first voice note, or its
 * first text when it has none. [summary] is empty when the recall has no words to show.
 */
data class OnThisDay(
    val date: LocalDate,
    val yearsAgo: Int,
    val leadMoment: JournalNote,
    val momentCount: Int,
    val summary: String,
)

/** Finds On this day among the notes on this watch, on the day [recall] picks. */
class OnThisDayFinder(
    private val recall: suspend () -> MemoryRecallData?,
    private val notesRepository: JournalNotesRepository,
) {
    /**
     * Picks the day again when an entry arrives or leaves, and follows edits to that day's moments
     * without reading every note on the watch.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(today: LocalDate): Flow<OnThisDay?> =
        notesRepository
            .observeEntryTimestamps()
            .distinctUntilChanged()
            .mapLatest { recall() }
            .distinctUntilChanged { old, new -> old?.date == new?.date }
            .flatMapLatest { memory ->
                if (memory == null) {
                    flowOf(null)
                } else {
                    notesRepository.observeNotesForDay(memory.date).mapLatest { notes ->
                        build(today, recall()?.takeIf { it.date == memory.date } ?: memory, notes)
                    }
                }
            }

    suspend fun find(today: LocalDate): OnThisDay? {
        val memory = recall() ?: return null
        return build(today, memory, notesRepository.getNotesForDay(memory.date))
    }

    private fun build(
        today: LocalDate,
        memory: MemoryRecallData,
        notes: List<JournalNote>,
    ): OnThisDay? {
        val moments =
            notes
                .filter(LibraryWindow::isLibraryMoment)
                .sortedBy(JournalNote::creationTimestamp)
        val lead = moments.firstOrNull { it is JournalNote.Audio } ?: moments.firstOrNull() ?: return null
        return OnThisDay(
            date = memory.date,
            yearsAgo = (today.year - memory.date.year).coerceAtLeast(1),
            leadMoment = lead,
            momentCount = moments.size,
            summary = memory.summary.takeIf { MoodNoteTag.tagOf(it) == null }.orEmpty(),
        )
    }
}
