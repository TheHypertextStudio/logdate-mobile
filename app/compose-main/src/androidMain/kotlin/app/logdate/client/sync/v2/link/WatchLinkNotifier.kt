package app.logdate.client.sync.v2.link

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import app.logdate.client.notifications.LogDateNotificationChannelKey
import app.logdate.client.sync.datalayer.v2.LinkMode
import io.github.aakira.napier.Napier
import app.logdate.client.notifications.R as NotificationResources

/** What the person sees about linking: one confirmation once it works, and the failures only they can fix. */
interface WatchLinkNotifier {
    fun linked(
        target: LinkTarget,
        mode: LinkMode,
    )

    fun needsAttention(
        target: LinkTarget,
        failure: PhoneLinkFailure,
    )
}

/** Posts on the Watch sync channel, local only, one notification per watch; tapping it opens LogDate. */
class AndroidWatchLinkNotifier(
    private val context: Context,
) : WatchLinkNotifier {
    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    override fun linked(
        target: LinkTarget,
        mode: LinkMode,
    ) {
        val body =
            when (mode) {
                LinkMode.PAIR -> NotificationResources.string.notification_watch_paired_body
                LinkMode.CLOUD -> NotificationResources.string.notification_watch_linked_body
            }
        post(
            target,
            context.getString(NotificationResources.string.notification_watch_paired_title, target.displayName),
            context.getString(body),
        )
    }

    override fun needsAttention(
        target: LinkTarget,
        failure: PhoneLinkFailure,
    ) {
        val copy = attentionCopy(failure)
        if (copy == null) {
            Napier.e("No notification describes $failure; it shows on the Watch page only")
            return
        }
        post(target, context.getString(copy.first, target.displayName), context.getString(copy.second))
    }

    private fun attentionCopy(failure: PhoneLinkFailure): Pair<Int, Int>? =
        when (failure) {
            PhoneLinkFailure.WATCH_BOUND_TO_ANOTHER_ACCOUNT -> {
                NotificationResources.string.notification_watch_other_account_title to
                    NotificationResources.string.notification_watch_other_account_body
            }

            PhoneLinkFailure.WATCH_KEY_CONFLICT -> {
                NotificationResources.string.notification_watch_key_conflict_title to
                    NotificationResources.string.notification_watch_key_conflict_body
            }

            PhoneLinkFailure.DEVICE_LIMIT_REACHED -> {
                NotificationResources.string.notification_watch_device_limit_title to
                    NotificationResources.string.notification_watch_device_limit_body
            }

            else -> {
                null
            }
        }

    private fun post(
        target: LinkTarget,
        title: String,
        body: String,
    ) {
        val open =
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
                PendingIntent.getActivity(
                    context,
                    notificationId(target),
                    launch,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
        val notification =
            NotificationCompat
                .Builder(context, LogDateNotificationChannelKey.WATCH_SYNC.id)
                .setSmallIcon(NotificationResources.drawable.ic_notification_watch)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setLocalOnly(true)
                .apply { if (open != null) setContentIntent(open) }
                .build()
        notificationManager.notify(target.nodeId, notificationId(target), notification)
    }

    companion object {
        private const val LINK_NOTIFICATION_BASE = 4_100_000

        /** One notification per watch, separate from the watch note notifications. */
        fun notificationId(target: LinkTarget): Int = LINK_NOTIFICATION_BASE + (target.nodeId.hashCode() and 0xFFFF)
    }
}
