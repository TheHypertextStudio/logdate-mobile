package app.logdate.feature.core.export

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.github.aakira.napier.Napier
import java.util.concurrent.TimeUnit

/** Schedules authenticated cloud backups without replacing an active export. */
class CloudBackupScheduler(
    context: Context,
) {
    private val workManager = WorkManager.getInstance(context)
    private val requests =
        CloudBackupRequests { name, periodic, keepExisting ->
            check(keepExisting) { "Cloud backup must retain active work" }
            if (periodic) {
                val request =
                    PeriodicWorkRequestBuilder<CloudBackupWorker>(24, TimeUnit.HOURS)
                        .setConstraints(CloudBackupWorker.NETWORK_CONSTRAINTS)
                        .addTag("${CloudBackupWorker.REQUEST_TIME_TAG}${System.currentTimeMillis()}")
                        .build()
                workManager.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.KEEP, request)
            } else {
                val request =
                    OneTimeWorkRequestBuilder<CloudBackupWorker>()
                        .setConstraints(CloudBackupWorker.NETWORK_CONSTRAINTS)
                        .addTag("${CloudBackupWorker.REQUEST_TIME_TAG}${System.currentTimeMillis()}")
                        .setInputData(Data.Builder().putBoolean(CloudBackupWorker.MANUAL_REQUEST, true).build())
                        .build()
                workManager.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request)
            }
        }

    fun schedulePeriodicBackup() {
        requests.periodic()
        Napier.d("Scheduled periodic LogDate Cloud backup")
    }

    fun enqueueImmediateBackup() {
        requests.immediate()
        Napier.d("Enqueued immediate LogDate Cloud backup")
    }
}
