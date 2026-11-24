package app.logdate.client.sync

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

/** Quiet polls stay ordinary; eligible transfers retain foreground protection when a run lasts. */
internal suspend fun <T> withSyncForegroundRun(
    statuses: StateFlow<SyncStatus>,
    showStarting: suspend () -> Boolean,
    showProgress: suspend (SyncRunProgress) -> Boolean,
    hasEligibleWork: suspend () -> Boolean = { false },
    run: suspend () -> T,
): T =
    coroutineScope {
        val since = statuses.value
        val notificationMutex = Mutex()
        var shown = false
        var notificationsAllowed = true
        val updates =
            launch(start = CoroutineStart.UNDISPATCHED) {
                statuses
                    .runProgressUpdates(since, 1.seconds)
                    .takeWhile { progress ->
                        notificationMutex.withLock {
                            if (!notificationsAllowed) return@withLock false
                            notificationsAllowed = showProgress(progress)
                            shown = notificationsAllowed
                            notificationsAllowed
                        }
                    }.collect()
            }
        // Remote discovery may download before an upload denominator exists. Do not let a long
        // discovery/transfer run lose background protection, or overwrite determinate upload progress.
        val keepAlive =
            launch {
                // Each check reads every pending row, so a run with nothing eligible checks less and less often.
                var wait = 10.seconds
                while (isActive) {
                    delay(wait)
                    wait = (wait * 2).coerceAtMost(80.seconds)
                    if (notificationMutex.withLock { shown || !notificationsAllowed }) break
                    if (!hasEligibleWork()) continue
                    notificationMutex.withLock {
                        if (!shown && notificationsAllowed) {
                            notificationsAllowed = showStarting()
                            shown = notificationsAllowed
                        }
                    }
                }
            }
        try {
            run()
        } finally {
            withContext(NonCancellable) {
                notificationMutex.withLock {
                    notificationsAllowed = false
                }
                updates.cancelAndJoin()
                keepAlive.cancelAndJoin()
            }
        }
    }
