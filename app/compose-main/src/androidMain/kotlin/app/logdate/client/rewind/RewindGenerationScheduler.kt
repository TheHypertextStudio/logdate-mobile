package app.logdate.client.rewind

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.github.aakira.napier.Napier
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Schedules periodic and on-demand rewind generation via WorkManager.
 *
 * Weekly rewind generation runs every 24 hours, checking whether last week's
 * rewind needs to be created. An immediate check is also triggered on app startup
 * to ensure rewinds are ready when the user opens the Rewind screen.
 */
class RewindGenerationScheduler(
    private val context: Context,
) {
    private val workManager = WorkManager.getInstance(context)

    /**
     * Schedules periodic weekly rewind generation checks.
     *
     * The worker runs every 24 hours, which ensures Sunday evening generation
     * regardless of the user's timezone. WorkManager's flexibility window
     * handles battery optimization automatically.
     */
    fun schedulePeriodicGeneration() {
        val periodicRequest =
            PeriodicWorkRequestBuilder<RewindGenerationWorker>(
                24,
                TimeUnit.HOURS,
            ).setInitialDelay(
                calculateInitialDelay(),
                TimeUnit.MILLISECONDS,
            ).build()

        workManager.enqueueUniquePeriodicWork(
            RewindGenerationWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodicRequest,
        )

        Napier.d("Scheduled periodic rewind generation")
    }

    fun disableAnnualGeneration() {
        workManager.cancelUniqueWork(AnnualRewindGenerationWorker.WORK_NAME)
    }

    /**
     * Triggers an immediate check for pending rewind generation.
     *
     * Called at app startup to ensure rewinds are ready when the user
     * navigates to the Rewind screen.
     */
    fun enqueueImmediateCheck() {
        val request =
            OneTimeWorkRequestBuilder<RewindGenerationWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

        workManager.enqueueUniqueWork(
            "${RewindGenerationWorker.WORK_NAME}:immediate",
            ExistingWorkPolicy.KEEP,
            request,
        )

        Napier.d("Enqueued immediate rewind generation check")
    }

    /**
     * Calculates initial delay to target Sunday evening (8 PM local time).
     */
    private fun calculateInitialDelay(): Long {
        val now = Calendar.getInstance()
        val target =
            Calendar.getInstance().apply {
                set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
                set(Calendar.HOUR_OF_DAY, 20)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= now.timeInMillis) {
                    add(Calendar.WEEK_OF_YEAR, 1)
                }
            }
        return target.timeInMillis - now.timeInMillis
    }
}
