package app.logdate.server.atproto.firehose

import app.logdate.server.logging.RepoEventNotices
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import studio.hypertext.atproto.repo.RepoErrorMessage
import studio.hypertext.atproto.repo.RepoEventFrames
import studio.hypertext.atproto.repo.RepoInfoMessage
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * What `com.atproto.sync.subscribeRepos` sends a follower: the frames, in order, starting where the
 * follower asked.
 *
 * A follower names the last event number it saw as its cursor. Everything after it is replayed from
 * the log, then new events follow as they are stored. A follower with no cursor starts at the present.
 * A cursor older than the oldest event the log still keeps gets an `OutdatedCursor` notice first and
 * then everything the log has, because some of what it missed is gone. A cursor beyond the newest
 * event is an error, since the server cannot have sent it.
 *
 * Servers share one log, so a follower is served the same way whichever instance it reaches. One
 * reader per instance looks for new events, every [pollInterval] for those another instance stored and
 * at once through [wakeups] for those this instance stored, and keeps the newest of them in memory.
 * Followers that have caught up take their events from it, so the log is read once for all of them.
 * A follower further behind reads the log itself until it catches up.
 */
class RepoEventStream(
    private val store: RepoEventStore,
    private val wakeups: SharedFlow<Long>,
    private val pollInterval: Duration,
    private val maxFollowers: Int,
    private val retention: Duration,
    private val clock: Clock = Clock.System,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val followers = AtomicInteger(0)

    /** The newest events, read while at least one follower is waiting for them. */
    private val newest: StateFlow<RecentEvents?> =
        flow<RecentEvents?> { readNewEvents() }
            .stateIn(scope, SharingStarted.WhileSubscribed(READER_LINGER_MILLIS, replayExpirationMillis = 0), null)

    /**
     * Reserves a place for one follower, or returns `null` when [maxFollowers] are already connected.
     * The caller must close the returned reservation when the follower disconnects.
     */
    fun reserve(): FollowerReservation? {
        if (followers.incrementAndGet() > maxFollowers) {
            followers.decrementAndGet()
            return null
        }
        return FollowerReservation { followers.decrementAndGet() }
    }

    /**
     * How many followers are connected now.
     *
     * Read by RepoEventStreamTest and RepoEventStreamRoutesTest to assert follower slots and wait for a socket to connect or leave.
     */
    @Suppress("unused")
    val followerCount: Int get() = followers.get()

    /**
     * The frames for a follower that sent [cursor]. The flow ends after an error frame and otherwise
     * runs until the collector stops.
     */
    fun frames(cursor: Long?): Flow<ByteArray> =
        flow {
            val latest = store.latestSeq()
            var position = latest
            if (cursor != null) {
                if (cursor > latest) {
                    emit(RepoEventFrames.encode(RepoErrorMessage("FutureCursor", "Cursor $cursor is ahead of the newest event $latest")))
                    return@flow
                }
                val earliest = store.earliestSeq()
                // With every event pruned there is no earliest one, yet a cursor behind the newest number missed some.
                val lastUnavailable = if (earliest == null) latest else earliest - 1
                if (cursor < lastUnavailable) {
                    emit(
                        RepoEventFrames.encode(
                            RepoInfoMessage("OutdatedCursor", "Events up to $lastUnavailable are no longer available"),
                        ),
                    )
                }
                position = cursor
            }
            while (currentCoroutineContext().isActive) {
                val recent = newest.filterNotNull().first()
                val shared = recent.after(position)
                if (shared == null) {
                    val batch = store.read(position, BATCH_SIZE)
                    batch.forEach { event ->
                        emit(event.frame)
                        position = event.seq
                    }
                    // Every event up to the reader's starting point existed before it started, so a short page has them all.
                    if (batch.size < BATCH_SIZE) position = maxOf(position, recent.floor)
                    continue
                }
                shared.forEach { event ->
                    emit(event.frame)
                    position = event.seq
                }
                if (shared.isEmpty()) newest.first { it == null || it.last > position }
            }
        }

    /**
     * Deletes events older than the retention period and returns how many went. A failure is logged
     * and reported as zero, so the next sweep retries it.
     */
    suspend fun pruneExpired(): Int =
        try {
            store.deleteBefore(clock.now() - retention)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Napier.e(RepoEventNotices.PRUNE_FAILED, failure)
            0
        }

    /** Runs [pruneExpired] every [interval] until the coroutine is cancelled. */
    suspend fun pruneForever(interval: Duration) {
        while (currentCoroutineContext().isActive) {
            pruneExpired()
            delay(interval)
        }
    }

    /**
     * Reads the log from the present onwards and publishes what it finds. A failed read, the first one
     * included, is logged and tried again after [pollInterval], while the followers keep waiting.
     */
    private suspend fun FlowCollector<RecentEvents?>.readNewEvents() {
        var recent = RecentEvents(floor = retrying { store.latestSeq() }, events = emptyList())
        emit(recent)
        while (currentCoroutineContext().isActive) {
            val batch = retrying { store.read(recent.last, BATCH_SIZE) }
            if (batch.isNotEmpty()) {
                recent = recent.plus(batch, KEPT_EVENTS)
                emit(recent)
            }
            if (batch.size == BATCH_SIZE) continue
            withTimeoutOrNull(pollInterval) { wakeups.first() }
        }
    }

    /** Runs [read] until it succeeds, logging each failure and waiting [pollInterval] before trying again. */
    private suspend fun <T> retrying(read: suspend () -> T): T {
        while (true) {
            try {
                return read()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Napier.e(RepoEventNotices.READ_FAILED, failure)
                delay(pollInterval)
            }
        }
    }

    /** A place held for one connected follower. */
    fun interface FollowerReservation : AutoCloseable

    private companion object {
        const val BATCH_SIZE = 50
        const val KEPT_EVENTS = 500
        const val READER_LINGER_MILLIS = 5_000L
    }
}

/**
 * Every event in the log after [floor], up to the newest the reader found, oldest first. [floor] rises as
 * the oldest are dropped to keep at most a fixed number.
 */
private class RecentEvents(
    val floor: Long,
    val events: List<StoredRepoEvent>,
) {
    val last: Long get() = events.lastOrNull()?.seq ?: floor

    /** The events after [position], or `null` when some of them are older than these. */
    fun after(position: Long): List<StoredRepoEvent>? {
        if (position < floor) return null
        val start = events.indexOfFirst { it.seq > position }
        return if (start < 0) emptyList() else events.subList(start, events.size)
    }

    fun plus(
        batch: List<StoredRepoEvent>,
        keep: Int,
    ): RecentEvents {
        val all = events + batch
        if (all.size <= keep) return RecentEvents(floor, all)
        val dropped = all.size - keep
        return RecentEvents(all[dropped - 1].seq, all.subList(dropped, all.size))
    }
}
