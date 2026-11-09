package app.logdate.client.shortcuts

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.github.aakira.napier.Napier
import java.util.concurrent.TimeUnit

/**
 * Schedules periodic and on-demand refreshes of LogDate's dynamic launcher shortcuts.
 *
 * The periodic job runs every 24 hours so day-rollover ("Today" shortcut) and
 * newly generated weekly rewinds get reflected in the launcher without needing
 * to wait for the user to relaunch the app. The immediate one-shot is enqueued
 * from `LogDateApplication.onCreate` so the launcher catches up as soon as the
 * process starts.
 *
 * Mirrors [app.logdate.client.rewind.RewindGenerationScheduler] in shape so the
 * two stay obviously similar.
 */
class DynamicShortcutScheduler(
    private val context: Context,
) {
    private val workManager = WorkManager.getInstance(context)

    fun schedulePeriodicRefresh() {
        val periodicRequest =
            PeriodicWorkRequestBuilder<DynamicShortcutRefreshWorker>(
                24,
                TimeUnit.HOURS,
            ).build()

        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodicRequest,
        )
        Napier.d("Scheduled periodic dynamic shortcut refresh")
    }

    fun enqueueImmediateRefresh() {
        val request = OneTimeWorkRequestBuilder<DynamicShortcutRefreshWorker>().build()
        workManager.enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
        Napier.d("Enqueued immediate dynamic shortcut refresh")
    }

    private companion object {
        const val PERIODIC_WORK_NAME = "logdate:shortcuts:dynamic_refresh:periodic"
        const val IMMEDIATE_WORK_NAME = "logdate:shortcuts:dynamic_refresh:immediate"
    }
}
