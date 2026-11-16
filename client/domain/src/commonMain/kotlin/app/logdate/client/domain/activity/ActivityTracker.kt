package app.logdate.client.domain.activity

import app.logdate.client.datastore.KeyValueStorage
import app.logdate.client.domain.LenientJson
import app.logdate.client.sync.peer.state.ActivitySnapshot
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

/** The activity both devices act on, and the last one either of them saw end. */
data class AgreedActivity(
    val current: Activity? = null,
    val lastEnded: Activity? = null,
)

/** Where a device keeps its [ActivityLog] between the short lives of the processes that feed it. */
interface ActivityLogStore {
    suspend fun read(): ActivityLog?

    suspend fun write(log: ActivityLog)
}

/** [ActivityLogStore] as JSON under one [key]. */
class KeyValueActivityLogStore(
    private val storage: KeyValueStorage,
    private val key: String = "activity.log",
) : ActivityLogStore {
    override suspend fun read(): ActivityLog? {
        val text = storage.getString(key) ?: return null
        return try {
            json.decodeFromString(ActivityLog.serializer(), text)
        } catch (e: IllegalArgumentException) {
            Napier.w("The saved activity could not be read; starting with none", e)
            null
        }
    }

    override suspend fun write(log: ActivityLog) = storage.putString(key, json.encodeToString(ActivityLog.serializer(), log))

    private companion object {
        val json = LenientJson
    }
}

/**
 * One device's activities, and the activity this device and its peer agree on.
 *
 * This device's source calls [accept] with each raw transition. The log is saved after every change,
 * so a process that a broadcast or a Health Services callback starts picks up where the last one
 * stopped. [start] ends an activity on time once its signal has stopped for its kind's end grace.
 * [agreed] combines this device's log with the [peer]'s and stays empty while [enabled] is false.
 *
 * @param isPhone Whether this device is the phone, which decides which side of the agreement its own
 *   activity fills.
 */
class ActivityTracker(
    private val store: ActivityLogStore,
    private val peer: Flow<ActivitySnapshot?>,
    private val watchWorn: Flow<Boolean>,
    private val isPhone: Boolean,
    private val enabled: Flow<Boolean>,
    private val clock: Clock = Clock.System,
) {
    private val log = MutableStateFlow(ActivityLog())
    private val mutex = Mutex()
    private var restored = false

    /** This device's own log, for its state document. */
    val local: StateFlow<ActivityLog> = log.asStateFlow()

    val agreed: Flow<AgreedActivity> =
        flow {
            restore()
            emitAll(
                combine(enabled, log, peer, watchWorn) { on, own, other, worn ->
                    if (on) agree(own.settle(clock.now()), other, worn) else AgreedActivity()
                },
            )
        }.distinctUntilChanged()

    suspend fun accept(transition: ActivityTransition) {
        mutex.withLock {
            restoreLocked()
            save(log.value.accept(transition).settle(clock.now()))
        }
    }

    /** Ends activities on time while [scope] lives. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(scope: CoroutineScope) {
        scope.launch {
            restore()
            log.collectLatest { current ->
                val settlesAt = current.settlesAt() ?: return@collectLatest
                delay(settlesAt - clock.now())
                mutex.withLock { save(log.value.settle(clock.now())) }
            }
        }
    }

    private fun agree(
        own: ActivityLog,
        other: ActivitySnapshot?,
        worn: Boolean,
    ): AgreedActivity {
        val now = clock.now()
        val peerCurrent = other?.current()?.takeIf { now - it.startedAt <= PEER_ACTIVITY_LIMIT }
        val phone = if (isPhone) own.current else peerCurrent
        val watch = if (isPhone) peerCurrent else own.current
        return AgreedActivity(
            current = agreedActivity(phone = phone, watch = watch, watchWorn = worn),
            lastEnded = latestEnded(own.lastEnded, other?.lastEnded()),
        )
    }

    private suspend fun restore() = mutex.withLock { restoreLocked() }

    private suspend fun restoreLocked() {
        if (restored) return
        restored = true
        val saved =
            try {
                store.read()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Napier.e("The saved activity could not be read; starting with none", e)
                null
            }
        log.value = saved?.settle(clock.now()) ?: ActivityLog()
    }

    private suspend fun save(next: ActivityLog) {
        if (next == log.value) return
        log.value = next
        try {
            store.write(next)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Napier.e("The current activity could not be saved; it is kept until this process ends", e)
        }
    }

    private companion object {
        /**
         * How long a peer's activity under way counts. The peer ends its own activities, so this only
         * matters when the peer stopped sending, for example a watch that ran out of battery mid-walk.
         */
        val PEER_ACTIVITY_LIMIT = 12.hours
    }
}
