package app.logdate.feature.core.settings.ui.watch.v2

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.domain.recommendation.GetMemoryRecallUseCase
import app.logdate.client.domain.recommendation.MemoryRecallData
import app.logdate.client.domain.recommendation.WidgetContentType
import app.logdate.client.domain.today.TodaySummary
import app.logdate.client.domain.today.TodaySummaryCalculator
import app.logdate.client.repository.journals.JournalNotesRepository
import io.github.aakira.napier.Napier
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/** Live phone-known summary, calculated identically to the watch's Today tile. */
class WatchTodayPreviewViewModel(
    notes: JournalNotesRepository,
    recall: GetMemoryRecallUseCase,
) : ViewModel() {
    private val calendar =
        flow {
            while (true) {
                val zone = TimeZone.currentSystemDefault()
                emit(
                    Clock.System
                        .now()
                        .toLocalDateTime(zone)
                        .date to zone,
                )
                delay(30_000)
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    val memory =
        combine(notes.observeEntryTimestamps().distinctUntilChanged(), calendar) { _, current -> current.first }
            .distinctUntilChanged()
            .mapLatest<kotlinx.datetime.LocalDate, WatchMemoryPreview?> { today ->
                recall(contentTypes = setOf(WidgetContentType.TEXT, WidgetContentType.AUDIO)).first()?.let {
                    WatchMemoryPreview(it, (today.year - it.date.year).coerceAtLeast(1))
                } ?: WatchMemoryPreview(null, 0)
            }.catch { failure ->
                Napier.e("Could not preview the watch memory tile", failure)
                emit(null)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val summary =
        calendar
            .distinctUntilChanged()
            .flatMapLatest { (today, zone) ->
                notes
                    .observeNotesInRange(today.atStartOfDayIn(zone), today.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone))
                    .map { notes ->
                        TodaySummaryCalculator.calculate(notes.filter { it.creationTimestamp.toLocalDateTime(zone).date == today })
                            as TodaySummary?
                    }
            }.catch { failure ->
                Napier.e("Could not preview today's watch tile", failure)
                emit(null)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

data class WatchMemoryPreview(
    val memory: MemoryRecallData?,
    val yearsAgo: Int,
)
