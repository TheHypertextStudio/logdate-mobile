package app.logdate.client.sync

import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import app.logdate.client.sync.metadata.UploadScope

internal fun immediateSyncRequest(
    syncType: String,
    consent: UploadScope?,
    expedited: Boolean = false,
): OneTimeWorkRequest {
    val constraints =
        Constraints
            .Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

    val inputData =
        Data
            .Builder()
            .putAll(mobileDataSyncInput(consent))
            .putString(AndroidLogDateSyncWorker.KEY_SYNC_TYPE, syncType)
            .putBoolean(AndroidLogDateSyncWorker.KEY_USER_INITIATED, expedited)
            .build()

    val request =
        OneTimeWorkRequestBuilder<AndroidLogDateSyncWorker>()
            .setConstraints(constraints)
            .setInputData(inputData)
            .apply { if (consent != null) addTag(MOBILE_DATA_SYNC_TAG) }
            .apply {
                // Only an explicit backup is expedited. On older Android an expedited request
                // can require a notification before the worker can discover that nothing changed.
                if (expedited) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }.build()

    return request
}
