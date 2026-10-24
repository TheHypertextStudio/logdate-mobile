package app.logdate.client.domain.watch

import app.logdate.shared.model.Rewind
import app.logdate.shared.model.RewindContent
import app.logdate.shared.model.user.UserData
import app.logdate.util.getLocaleFirstDayOfWeek
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.todayIn
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Supplies persisted weekly Rewind sources and favorites without generating a Rewind in the background. */
class WatchWindowExtrasSource(
    private val rewinds: Flow<List<Rewind>>,
    private val user: Flow<UserData>,
    private val firstDayOfWeek: Flow<DayOfWeek?>,
    private val clock: Clock = Clock.System,
    private val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
) {
    fun observe(): Flow<WindowExtraIds> =
        combine(rewinds, user, firstDayOfWeek) { saved, data, preferred ->
            val timezone = zone()
            val today = clock.todayIn(timezone)
            val first = preferred ?: getLocaleFirstDayOfWeek()
            val end = today.minus((today.dayOfWeek.ordinal - first.ordinal + 7) % 7, DateTimeUnit.DAY)
            val start = end.minus(7, DateTimeUnit.DAY)
            val weekly =
                saved
                    .filter {
                        it.startDate == start.atStartOfDayIn(timezone) && it.endDate == end.atStartOfDayIn(timezone)
                    }.maxByOrNull { it.generationDate }
            WindowExtraIds(
                rewind =
                    weekly
                        ?.content
                        .orEmpty()
                        .mapNotNull { content ->
                            when (content) {
                                is RewindContent.TextNote, is RewindContent.AudioNote,
                                is RewindContent.Image, is RewindContent.Video,
                                -> content.sourceId

                                else -> null
                            }
                        }.toSet(),
                starred = data.favoriteNotes.mapNotNull { Uuid.parseOrNull(it) }.toSet(),
            )
        }.distinctUntilChanged()
}
