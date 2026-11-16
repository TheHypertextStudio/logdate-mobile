package app.logdate.wear.v2.memories

import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.rewind.RewindRepository
import app.logdate.shared.model.Rewind
import app.logdate.shared.model.RewindContent
import app.logdate.wear.v2.library.LibraryWindow
import app.logdate.wear.v2.today.TodayWindow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlin.uuid.Uuid

/** This week's Rewind as the watch shows it: its title, and the moment that opens it. */
data class WeeklyRewind(
    val title: String,
    val leadMoment: JournalNote,
)

/**
 * Verified phone-window Rewind notes form the card without copying the phone's Rewind database.
 * Legacy callers may use the newest local Rewind that ended in the last [REWIND_AGE_DAYS] days.
 */
class WeeklyRewindSource(
    private val rewinds: RewindRepository,
    private val notesRepository: JournalNotesRepository,
    private val windowRewind: suspend () -> Set<Uuid>? = { null },
    private val windowChanges: Flow<Unit> = flowOf(Unit),
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(today: TodayWindow): Flow<WeeklyRewind?> {
        val since = today.date.minus(REWIND_AGE_DAYS, DateTimeUnit.DAY).atStartOfDayIn(today.timeZone)
        return windowChanges
            .onStart { emit(Unit) }
            .mapLatest { windowRewind() }
            .flatMapLatest { selected ->
                if (selected != null) {
                    observeNewest(selected).map { lead -> lead?.let { WeeklyRewind("This week's Rewind", it) } }
                } else {
                    rewinds
                        .getAllRewinds()
                        .map { all -> all.filter { it.endDate >= since }.maxByOrNull(Rewind::endDate) }
                        .distinctUntilChanged()
                        .flatMapLatest { rewind -> rewind?.let(::observeLead) ?: flowOf(null) }
                }
            }.distinctUntilChanged()
    }

    /** The newest library moment among [ids], following edits and deletes of each one. */
    private fun observeNewest(ids: Set<Uuid>): Flow<JournalNote?> =
        observeEach(ids.toList()).map { notes ->
            notes.filter(LibraryWindow::isLibraryMoment).maxByOrNull(JournalNote::creationTimestamp)
        }

    /** The first voice or text moment of [rewind] still on the watch, in the Rewind's own order. */
    private fun observeLead(rewind: Rewind): Flow<WeeklyRewind?> {
        val ids =
            rewind.content
                .filter { it is RewindContent.AudioNote || it is RewindContent.TextNote }
                .map { it.sourceId }
        return observeEach(ids).map { notes ->
            notes.firstOrNull(LibraryWindow::isLibraryMoment)?.let { lead -> WeeklyRewind(title = rewind.title, leadMoment = lead) }
        }
    }

    private fun observeEach(ids: List<Uuid>): Flow<List<JournalNote>> =
        if (ids.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(ids.map(notesRepository::observeNoteById)) { notes -> notes.filterNotNull() }
        }

    companion object {
        const val REWIND_AGE_DAYS = 7
    }
}
