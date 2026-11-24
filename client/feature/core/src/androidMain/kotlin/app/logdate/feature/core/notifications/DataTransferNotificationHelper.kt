package app.logdate.feature.core.notifications

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import app.logdate.client.feature.core.R
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

/**
 * Shared base for export and restore notification helpers.
 *
 * Captures the superset behavior: progress bar, immediate foreground
 * service display, silent notifications across all states, and a cancel
 * action during progress.
 */
abstract class DataTransferNotificationHelper(
    protected val context: Context,
    protected val workId: Uuid,
) {
    protected abstract val channelId: String
    protected abstract val notificationId: Int

    @get:StringRes
    protected abstract val progressTitleResId: Int

    @get:StringRes
    protected abstract val completeTitleResId: Int

    @get:StringRes
    protected abstract val failedTitleResId: Int

    protected val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    protected open val terminalNotificationId: Int
        get() = notificationId + TERMINAL_NOTIFICATION_ID_OFFSET

    /**
     * Builds a foreground-service notification with a progress bar and cancel action.
     *
     * @param progress Current progress percentage (0-100). When 0, the bar is indeterminate.
     * @param message  Localized status text shown below the title.
     */
    open fun createForegroundInfo(
        progress: Int,
        message: String,
    ): ForegroundInfo {
        val cancelIntent =
            WorkManager
                .getInstance(context)
                .createCancelPendingIntent(workId.toJavaUuid())

        val notification =
            NotificationCompat
                .Builder(context, channelId)
                .setContentTitle(context.getString(progressTitleResId))
                .setContentText(message)
                .setContentIntent(createContentIntent())
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setProgress(100, progress, progress == 0)
                .setOngoing(true)
                .setSilent(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .addAction(
                    android.R.drawable.ic_delete,
                    context.getString(R.string.notification_action_cancel),
                    cancelIntent,
                ).build()

        return ForegroundInfo(
            notificationId,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    /**
     * Posts a completion notification via [NotificationManager] on a non-foreground notification ID
     * so WorkManager teardown never races with or cancels it.
     */
    open fun showCompletionNotification(contentText: String) {
        val notification =
            NotificationCompat
                .Builder(context, channelId)
                .setContentTitle(context.getString(completeTitleResId))
                .setContentText(contentText)
                .setContentIntent(createContentIntent())
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setAutoCancel(true)
                .setSilent(true)
                .build()
        postTerminalNotification(notification)
    }

    /**
     * Posts an error notification via [NotificationManager] on a non-foreground notification ID.
     */
    fun showErrorNotification(errorMessage: String) {
        val notification =
            NotificationCompat
                .Builder(context, channelId)
                .setContentTitle(context.getString(failedTitleResId))
                .setContentText(context.getString(R.string.error_with_message, errorMessage))
                .setContentIntent(createContentIntent())
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setAutoCancel(true)
                .setSilent(true)
                .build()
        postTerminalNotification(notification)
    }

    private fun postTerminalNotification(notification: android.app.Notification) {
        runCatching {
            notificationManager.notify(terminalNotificationId, notification)
        }
    }

    companion object {
        private const val TERMINAL_NOTIFICATION_ID_OFFSET = 10_000
    }

    protected open fun createContentIntent(): PendingIntent? {
        val launchIntent =
            context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: return null

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        launchIntent.putExtra(EXTRA_NAV_SOURCE, NAV_SOURCE_DATA_TRANSFER)

        return PendingIntent.getActivity(
            context,
            notificationId,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
