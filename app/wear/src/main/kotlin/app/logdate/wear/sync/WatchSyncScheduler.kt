package app.logdate.wear.sync

import app.logdate.client.sync.SyncStatus
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.SyncDeadLetterStore
import app.logdate.client.sync.metadata.SyncMetadataService
import app.logdate.client.sync.metadata.SyncRetryScheduleStore
import app.logdate.wear.sync.v2.delivery.NoteSchedule
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.launch
import kotlin.time.Clock

/** Runs the watch's sync once, after [delayMs], in place of any run already waiting. */
fun interface WatchSyncWork {
    fun runAfter(delayMs: Long)
}

/**
 * Wakes the watch when an outbox item is next due, so a retry happens on time even when nothing else
 * would start the app.
 *
 * The next run is set for the earliest stored retry time of anything the watch uploads, or for now
 * when an item has never been attempted. It is set again each time the sync status settles outside a
 * run: at app start, when the phone comes into or goes out of reach, when the outbox changes, and
 * after every run. Changes during a run wait for it to finish, because setting a new run replaces the
 * waiting work and would stop a run in progress. Legacy uploads wait for the phone to return;
 * operation-aware notes can wake independently to retry their own cloud backup.
 *
 * After a reboot WorkManager reschedules this work through the boot permission declared for reminders.
 */
class WatchSyncScheduler(
    private val work: WatchSyncWork,
    private val syncStatus: Flow<SyncStatus>,
    private val syncMetadataService: SyncMetadataService,
    private val retryScheduleStore: SyncRetryScheduleStore,
    private val dataLayerClient: WearDataLayerClient,
    private val clock: Clock = Clock.System,
    private val noteSchedule: suspend () -> NoteSchedule = { NoteSchedule.Outbox },
    private val deadLetterStore: SyncDeadLetterStore? = null,
) {
    /** Follows the sync status in [scope] for the life of the app. */
    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        scope.launch {
            syncStatus
                .debounce(STATUS_SETTLE_MS)
                .filterNot { it.isSyncing }
                .catch { e -> Napier.e("Stopped scheduling watch syncs: the sync status could not be read", e) }
                .collect { scheduleNextLogged() }
        }
    }

    /** Sets the next run from note delivery deadlines and reachable legacy collections. */
    suspend fun scheduleNext() {
        val connected = dataLayerClient.isPhoneConnected()
        val dueAt =
            when (val notes = noteSchedule()) {
                NoteSchedule.Outbox -> {
                    if (connected) earliestAttemptAt(UPLOADED_TYPES) else null
                }

                is NoteSchedule.Ledger -> {
                    val collections = if (connected) earliestAttemptAt(UPLOADED_TYPES.filterNot { it == EntityType.NOTE }) else null
                    listOfNotNull(notes.dueAt, collections).minOrNull()
                }
            } ?: return
        work.runAfter((dueAt - clock.now().toEpochMilliseconds()).coerceAtLeast(0L))
    }

    private suspend fun earliestAttemptAt(types: List<EntityType>): Long? {
        val failures = deadLetterStore?.list().orEmpty()
        return types
            .flatMap { type -> syncMetadataService.getPendingUploads(type).map { type to it } }
            .filterNot { (type, pending) ->
                failures.any {
                    it.entityType == type.name &&
                        it.entityId == pending.entityId &&
                        it.operationId == pending.operationId &&
                        it.scope == pending.scope &&
                        it.operation == pending.operation.name &&
                        it.expectedServerVersion == pending.expectedServerVersion
                }
            }.minOfOrNull { (type, pending) -> retryScheduleStore.nextAttemptAt(type, wearRetryKey(pending)) ?: 0L }
    }

    private suspend fun scheduleNextLogged() {
        try {
            scheduleNext()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Napier.e("Could not schedule the next watch sync", e)
        }
    }

    private companion object {
        /** What the watch's sync manager uploads; anything else in the outbox is not the watch's to send. */
        val UPLOADED_TYPES = listOf(EntityType.JOURNAL, EntityType.NOTE, EntityType.ASSOCIATION)

        /** Status changes come in bursts: a note, its link to a journal, and the run that sends both. */
        const val STATUS_SETTLE_MS = 2_000L
    }
}
