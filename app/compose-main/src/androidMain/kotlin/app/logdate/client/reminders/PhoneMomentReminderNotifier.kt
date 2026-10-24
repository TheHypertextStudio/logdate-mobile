package app.logdate.client.reminders

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.logdate.client.MainActivity
import app.logdate.client.ambient.AMBIENT_PROMPT_TARGET_DRAFT
import app.logdate.client.ambient.AMBIENT_PROMPT_TARGET_MEMORY_RECALL
import app.logdate.client.ambient.EXTRA_AMBIENT_PROMPT_DRAFT_ID
import app.logdate.client.ambient.EXTRA_AMBIENT_PROMPT_RECALL_DATE
import app.logdate.client.ambient.EXTRA_AMBIENT_PROMPT_TARGET
import app.logdate.client.capture.ACTION_CAPTURE
import app.logdate.client.capture.EXTRA_CAPTURE_MODE
import app.logdate.client.domain.recommendation.AmbientPromptCandidate
import app.logdate.client.domain.recommendation.AmbientPromptPayload
import app.logdate.client.domain.reminders.ReminderTrigger
import app.logdate.client.domain.reminders.reminderTrigger
import app.logdate.client.notifications.LogDateNotificationChannelKey
import app.logdate.client.notifications.LogDateNotificationRegistrar
import io.github.aakira.napier.Napier
import kotlinx.datetime.LocalDate
import studio.hypertext.logdate.R
import app.logdate.client.notifications.R as NotificationResources

/** Posts only; the delivery coordinator owns permission to consume the day's reminder. */
class PhoneMomentReminderNotifier(
    private val context: Context,
) {
    fun post(
        trigger: ReminderTrigger,
        openMarks: Int,
        day: LocalDate,
        supplemental: AmbientPromptCandidate? = null,
    ): Boolean {
        if (openMarks < 0) return false
        if (trigger == ReminderTrigger.DRAFT_RESCUE || trigger == ReminderTrigger.MEMORY_RECALL) {
            if (supplemental?.reminderTrigger() != trigger) return false
        } else if (supplemental != null) {
            return false
        }
        if (!LogDateNotificationRegistrar(context).canPost(CHANNEL)) return false
        val manager = NotificationManagerCompat.from(context)
        val (title, message) = content(trigger)
        val body =
            buildString {
                append(context.getString(message))
                if (openMarks > 0) {
                    append("\n")
                    append(context.resources.getQuantityString(R.plurals.moment_reminder_open_marks, openMarks, openMarks))
                }
            }
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL.id)
                .setSmallIcon(NotificationResources.drawable.ic_notification_capture)
                .setContentTitle(context.getString(title))
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentIntent(supplemental?.let { openSupplemental(it, day) } ?: capture(day, "text"))
                .addAction(
                    NotificationResources.drawable.ic_notification_capture,
                    context.getString(R.string.moment_reminder_record),
                    capture(day, "voice"),
                ).extend(NotificationCompat.WearableExtender().setBridgeTag(TAG).setDismissalId("$TAG:$day"))
                .build()
        return try {
            manager.notify(TAG, NOTIFICATION_ID, notification)
            true
        } catch (e: SecurityException) {
            Napier.w("Notification permission changed before the phone reminder could be posted", e)
            false
        }
    }

    private fun capture(
        day: LocalDate,
        mode: String,
    ): PendingIntent =
        PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(ACTION_CAPTURE)
                .setClass(context, MainActivity::class.java)
                .setData(Uri.parse("logdate://capture/reminder/$day/$mode"))
                .putExtra(EXTRA_CAPTURE_MODE, mode)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun openSupplemental(
        candidate: AmbientPromptCandidate,
        day: LocalDate,
    ): PendingIntent {
        val intent =
            Intent(context, MainActivity::class.java)
                .setData(Uri.parse("logdate://reminder/$day/${candidate.reminderTrigger()}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        when (val payload = candidate.payload) {
            is AmbientPromptPayload.DraftRescue -> {
                intent
                    .putExtra(EXTRA_AMBIENT_PROMPT_TARGET, AMBIENT_PROMPT_TARGET_DRAFT)
                    .putExtra(EXTRA_AMBIENT_PROMPT_DRAFT_ID, payload.draftId.toString())
            }

            is AmbientPromptPayload.MemoryRecall -> {
                intent
                    .putExtra(EXTRA_AMBIENT_PROMPT_TARGET, AMBIENT_PROMPT_TARGET_MEMORY_RECALL)
                    .putExtra(EXTRA_AMBIENT_PROMPT_RECALL_DATE, payload.date.toString())
            }

            else -> {
                error("Unsupported supplemental reminder")
            }
        }
        return PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun content(trigger: ReminderTrigger): Pair<Int, Int> =
        when (trigger) {
            ReminderTrigger.MOVING_ENDED -> {
                R.string.moment_reminder_moving_title to R.string.moment_reminder_moving_body
            }

            ReminderTrigger.TRIP_ENDED -> {
                R.string.moment_reminder_trip_title to R.string.moment_reminder_trip_body
            }

            ReminderTrigger.WORKOUT_ENDED -> {
                R.string.moment_reminder_workout_title to R.string.moment_reminder_workout_body
            }

            ReminderTrigger.ARRIVED_SOMEWHERE_NEW -> {
                R.string.moment_reminder_arrival_title to R.string.moment_reminder_arrival_body
            }

            ReminderTrigger.CAMPFIRE_EMBERS -> {
                R.string.moment_reminder_campfire_title to R.string.moment_reminder_campfire_body
            }

            ReminderTrigger.WIND_DOWN -> {
                R.string.moment_reminder_wind_down_title to R.string.moment_reminder_wind_down_body
            }

            ReminderTrigger.DRAFT_RESCUE -> {
                NotificationResources.string.ambient_prompt_draft_rescue_title to
                    NotificationResources.string.ambient_prompt_draft_rescue_body
            }

            ReminderTrigger.MEMORY_RECALL -> {
                NotificationResources.string.ambient_prompt_memory_recall_title to
                    NotificationResources.string.ambient_prompt_memory_recall_body
            }
        }

    companion object {
        const val TAG = "logdate.reminder"
        val CHANNEL = LogDateNotificationChannelKey.MOMENT_REMINDERS
        val NOTIFICATION_ID: Int = requireNotNull(CHANNEL.notificationId)
    }
}
