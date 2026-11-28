package app.logdate.client.domain.timeline

import app.logdate.client.domain.location.history.LocationHistoryService
import app.logdate.client.domain.location.history.LocationHistorySnapshot
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The location history the timeline shows for each day, reconstructed at most once per [freshFor].
 *
 * Rebuilding a day's history reads every location sample around it and reruns stay detection, and
 * the timeline rebuilds its days whenever a note, caption or transcript in its window changes. Without
 * this, each of those rebuilds reconstructed every visible day again and kept the CPU busy for as
 * long as anything kept changing. The places a day card lists may trail new location samples by up
 * to [freshFor].
 */
class TimelineDayHistory(
    private val readDay: (suspend (LocalDate) -> LocationHistorySnapshot)?,
    private val freshFor: Duration = 5.minutes,
    private val now: () -> Instant = { Clock.System.now() },
) {
    constructor(locationHistoryService: LocationHistoryService?) : this(locationHistoryService?.let(::firstSnapshotOfDay))

    private val mutex = Mutex()
    private val snapshots = LinkedHashMap<LocalDate, Pair<Instant, LocationHistorySnapshot?>>()

    /** The day's history snapshot, or null when there is no history service or it can't be read. */
    suspend fun snapshotFor(date: LocalDate): LocationHistorySnapshot? {
        val read = readDay ?: return null
        // Held across the reconstruction so concurrent builds of the same timeline share one result.
        return mutex.withLock {
            snapshots[date]?.takeIf { (readAt, _) -> now() - readAt < freshFor }?.let { (_, snapshot) -> return@withLock snapshot }
            val snapshot =
                try {
                    read(date)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Napier.w("Couldn't read location history for $date", failure)
                    null
                }
            val readAt = now()
            snapshots.remove(date)
            snapshots.entries.removeAll { (_, entry) -> readAt - entry.first >= freshFor }
            if (snapshots.size >= MAX_DAYS) snapshots.remove(snapshots.keys.first())
            snapshots[date] = readAt to snapshot
            snapshot
        }
    }

    private companion object {
        /** More days than one screen of timeline shows, so scrolling back doesn't evict what's on screen. */
        const val MAX_DAYS = 64
    }
}

private fun firstSnapshotOfDay(service: LocationHistoryService): suspend (LocalDate) -> LocationHistorySnapshot =
    { date -> service.observeDay(date).first() }
