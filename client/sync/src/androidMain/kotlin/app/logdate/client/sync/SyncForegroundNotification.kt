package app.logdate.client.sync

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import app.logdate.client.notifications.LogDateNotificationChannelKey
import io.github.aakira.napier.Napier

/**
 * The notification that keeps a backup alive while the app is not on screen.
 *
 * A sync of several hundred entries takes minutes. Run as ordinary background work it was
 * cancelled as soon as the app left the foreground, part way through, with nothing to show for
 * it; a foreground service survives that. The notification is the price Android charges for it,
 * so it may as well say something true - how far along the backup is.
 */
internal object SyncForegroundNotification {
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
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(context.getString(R.string.sync_notification_title))
                .setContentText(text)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(Notification.VISIBILITY_PRIVATE)

        if (total != null && total > 0 && completed != null) {
            builder.setProgress(total, completed, false)
        } else {
            builder.setProgress(0, 0, true)
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
     * Updates the active foreground notification directly without calling WorkManager's
     * `setForeground()`, avoiding `ACTION_NOTIFY` races against `SystemForegroundService` shutdown.
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
            Napier.w("Could not update backup progress notification", e)
            false
        }
}
