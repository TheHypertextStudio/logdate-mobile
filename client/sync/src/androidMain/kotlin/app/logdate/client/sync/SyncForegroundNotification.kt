package app.logdate.client.sync

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import app.logdate.client.notifications.LogDateNotificationChannelKey
import app.logdate.client.notifications.applyTransferLiveUpdate
import app.logdate.client.notifications.currentLiveUpdatesSwitch
import app.logdate.client.notifications.transferLiveUpdate
import io.github.aakira.napier.Napier

/**
 * The notification that keeps a backup alive while the app is not on screen.
 *
 * A sync of several hundred entries takes minutes. Run as ordinary background work it was
 * cancelled as soon as the app left the foreground, part way through, with nothing to show for
 * it; a foreground service survives that. The notification is the price Android charges for it,
 * so it may as well say something true - how far along the backup is.
 */
object SyncForegroundNotification {
    private val channel = LogDateNotificationChannelKey.CLOUD_SYNC
    val notificationId: Int = channel.notificationId ?: 1004

    fun buildNotification(
        context: Context,
        text: String,
        completed: Int? = null,
        total: Int? = null,
    ): Notification {
        val builder =
            NotificationCompat
                .Builder(context, channel.id)
                .setSmallIcon(
                    if (syncNotificationPhase(total) ==
                        SyncNotificationPhase.CHECKING
                    ) {
                        android.R.drawable.stat_notify_sync
                    } else {
                        android.R.drawable.stat_sys_upload
                    },
                ).setContentTitle(
                    context.getString(
                        if (syncNotificationPhase(total) ==
                            SyncNotificationPhase.CHECKING
                        ) {
                            R.string.sync_notification_starting
                        } else {
                            R.string.sync_notification_title
                        },
                    ),
                ).setContentText(text)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(Notification.VISIBILITY_PRIVATE)

        val percent =
            if (total != null && total > 0 && completed != null) {
                builder.setProgress(total, completed, false)
                (completed * 100 / total).coerceIn(0, 100)
            } else {
                builder.setProgress(0, 0, true)
                null
            }
        if (syncNotificationPhase(total) == SyncNotificationPhase.BACKING_UP) {
            builder.applyTransferLiveUpdate(transferLiveUpdate(currentLiveUpdatesSwitch().enabled, percent), percent)
        }

        return builder.build()
    }

    fun info(
        context: Context,
        text: String,
        completed: Int? = null,
        total: Int? = null,
    ): ForegroundInfo =
        ForegroundInfo(
            notificationId,
            buildNotification(context, text, completed, total),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

    /**
     * Updates an active foreground notification directly via [NotificationManager] rather than
     * calling `setForeground()`, preventing `ACTION_NOTIFY` from racing past `SystemForegroundService` shutdown.
     */
    fun updateProgress(
        context: Context,
        text: String,
        completed: Int? = null,
        total: Int? = null,
    ): Boolean =
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (manager == null) {
                false
            } else {
                manager.notify(notificationId, buildNotification(context, text, completed, total))
                true
            }
        } catch (e: SecurityException) {
            Napier.w("Missing permission to update backup progress notification", e)
            false
        } catch (e: Exception) {
            Napier.w("Could not update backup progress notification: ${e::class.simpleName}")
            false
        }
}
