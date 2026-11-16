package app.logdate.client.ambient

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import app.logdate.client.capture.internalCaptureIntent
import app.logdate.client.domain.notes.AddNoteUseCase
import app.logdate.client.notifications.LogDateNotificationChannelKey
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import studio.hypertext.logdate.R
import kotlin.time.Clock
import app.logdate.client.notifications.R as NotificationResources

/** The RemoteInput key a capture reminder's Write action stores the reply under. */
const val QUICK_REPLY_RESULT_KEY = "app.logdate.extra.QUICK_REPLY_TEXT"

/** The broadcast a capture reminder's Write action sends. */
const val ACTION_QUICK_REPLY = "app.logdate.action.QUICK_REPLY"

/** The reply text, carried by the Retry action after a failed save. */
const val EXTRA_QUICK_REPLY_TEXT = "app.logdate.extra.QUICK_REPLY_RETRY_TEXT"

/** The notification the reply came from, so the confirmation replaces it. */
const val EXTRA_QUICK_REPLY_NOTIFICATION_ID = "app.logdate.extra.QUICK_REPLY_NOTIFICATION_ID"

/** How long the saved confirmation stays before Android removes it. */
private const val CONFIRMATION_TIMEOUT_MS = 6_000L

/**
 * Saves the text typed into a capture reminder's reply field as a new entry, then replaces the
 * reminder with a confirmation. The confirmation never repeats the entry, because it can show on the
 * lock screen. If saving fails, the notification keeps the reply, with Retry and a way into LogDate,
 * so the words are never lost; its lock-screen version names only the failure.
 */
class QuickReplyReceiver :
    BroadcastReceiver(),
    KoinComponent {
    private val addNote: AddNoteUseCase by inject()
    private val scope: CoroutineScope by inject()

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION_QUICK_REPLY) return
        val notificationId = intent.getIntExtra(EXTRA_QUICK_REPLY_NOTIFICATION_ID, 0)
        val reply =
            RemoteInput.getResultsFromIntent(intent)?.getCharSequence(QUICK_REPLY_RESULT_KEY)
                ?: intent.getStringExtra(EXTRA_QUICK_REPLY_TEXT)
        val note =
            quickReplyNote(
                reply,
                Clock.System.now(),
                java.util.TimeZone
                    .getDefault()
                    .id,
            )
        val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (note == null) {
            notifications.cancel(notificationId)
            return
        }

        val pending = goAsync()
        scope.launch {
            try {
                runCatching { addNote(notes = listOf(note)) }
                    .onSuccess {
                        notifications.notify(notificationId, confirmation(context))
                    }.onFailure { error ->
                        Napier.e("Quick reply could not be saved", error)
                        notifications.notify(notificationId, failure(context, notificationId, note.content))
                    }
            } finally {
                pending.finish()
            }
        }
    }

    private fun confirmation(context: Context) =
        NotificationCompat
            .Builder(context, LogDateNotificationChannelKey.CAPTURE_NUDGES.id)
            .setSmallIcon(NotificationResources.drawable.ic_notification_capture)
            .setContentTitle(context.getString(R.string.quick_reply_saved_title))
            .setContentText(context.getString(R.string.quick_reply_saved_body))
            .setSilent(true)
            .setAutoCancel(true)
            .setTimeoutAfter(CONFIRMATION_TIMEOUT_MS)
            .build()

    private fun failure(
        context: Context,
        notificationId: Int,
        content: String,
    ) = NotificationCompat
        .Builder(context, LogDateNotificationChannelKey.CAPTURE_NUDGES.id)
        .setSmallIcon(NotificationResources.drawable.ic_notification_capture)
        .setContentTitle(context.getString(R.string.quick_reply_failed_title))
        .setPublicVersion(
            NotificationCompat
                .Builder(context, LogDateNotificationChannelKey.CAPTURE_NUDGES.id)
                .setSmallIcon(NotificationResources.drawable.ic_notification_capture)
                .setContentTitle(context.getString(R.string.quick_reply_failed_title))
                .build(),
        ).addAction(
            NotificationResources.drawable.ic_notification_capture,
            context.getString(R.string.quick_reply_retry),
            PendingIntent.getBroadcast(
                context,
                notificationId,
                Intent(ACTION_QUICK_REPLY)
                    .setClass(context, QuickReplyReceiver::class.java)
                    .putExtra(EXTRA_QUICK_REPLY_NOTIFICATION_ID, notificationId)
                    .putExtra(EXTRA_QUICK_REPLY_TEXT, content),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        ).setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.quick_reply_failed_body, content)))
        .setContentText(context.getString(R.string.quick_reply_failed_body, quickReplyPreview(content)))
        .setContentIntent(
            // Opening the notification starts a new entry with the reply's text, since the
            // notification is the only other copy and opening it dismisses it.
            PendingIntent.getActivity(
                context,
                notificationId,
                context
                    .internalCaptureIntent(content)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        ).setAutoCancel(true)
        .build()
}
