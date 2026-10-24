package app.logdate.client.calendar

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.logdate.client.datastore.LogdatePreferencesDataSource
import app.logdate.client.domain.events.CalendarImportFailure
import app.logdate.client.domain.events.ImportDeviceCalendarEventsUseCase
import app.logdate.client.domain.events.ImportResult
import io.github.aakira.napier.Napier
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.time.Clock

/**
 * Background worker that mirrors the user's selected device calendars into LogDate's own
 * event store via [ImportDeviceCalendarEventsUseCase].
 *
 * Gated on the master "device calendar sync" toggle. When the user has selected zero
 * calendars or revoked the runtime permission the worker still runs but short-circuits
 * back to `Result.success()` so it can be scheduled idempotently. Stats land in
 * [LogdatePreferencesDataSource.recordDeviceCalendarSyncRun] so the settings overview
 * can show the most recent run without standing up a separate repository.
 */
class CalendarImportWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params),
    KoinComponent {
    private val importEvents: ImportDeviceCalendarEventsUseCase by inject()
    private val preferences: LogdatePreferencesDataSource by inject()

    override suspend fun doWork(): Result {
        Napier.d(tag = TAG, message = "Starting device calendar import pass")

        if (!preferences.isEventsEnabled() || !preferences.isDeviceCalendarSyncEnabled()) {
            Napier.d(tag = TAG, message = "Device calendar sync disabled, skipping run")
            return Result.success()
        }

        val selectedIds = preferences.getDeviceCalendarEnabledIds()
        return when (val outcome = importEvents(selectedCalendarIds = selectedIds)) {
            is ImportResult.Success -> {
                val summary = outcome.summary
                Napier.d(
                    tag = TAG,
                    message = "Calendar import: ${summary.created} created, ${summary.updated} updated, ${summary.skipped} skipped",
                )
                preferences.recordDeviceCalendarSyncRun(
                    runAt = Clock.System.now(),
                    created = summary.created,
                    updated = summary.updated,
                    errorKind = null,
                )
                Result.success()
            }

            ImportResult.PermissionDenied -> {
                Napier.d(tag = TAG, message = "Calendar permission missing, recording stats and skipping")
                preferences.recordDeviceCalendarSyncRun(
                    runAt = Clock.System.now(),
                    created = 0,
                    updated = 0,
                    errorKind = CalendarImportFailure.PermissionDenied.name,
                )
                // Permission denial is a steady state, not a transient failure — retrying
                // won't change anything until the user grants the permission, at which
                // point the next periodic tick (or a "Run now" tap) will pick it up.
                Result.success()
            }

            ImportResult.Failure -> {
                Napier.w(tag = TAG, message = "Calendar import pass failed")
                preferences.recordDeviceCalendarSyncRun(
                    runAt = Clock.System.now(),
                    created = 0,
                    updated = 0,
                    errorKind = CalendarImportFailure.Unknown.name,
                )
                Result.retry()
            }
        }
    }

    companion object {
        const val WORK_NAME = "logdate:calendar:import"
        private const val TAG = "CalendarImportWorker"
    }
}
