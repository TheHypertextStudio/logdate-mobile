package app.logdate.client.ambient

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import app.logdate.client.MainActivity
import app.logdate.client.capture.ACTION_CAPTURE
import app.logdate.client.capture.EXTRA_CAPTURE_MODE
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.domain.recommendation.AmbientCaptureNudgeStyle
import app.logdate.client.domain.recommendation.AmbientPromptCandidate
import app.logdate.client.domain.recommendation.AmbientPromptFamily
import app.logdate.client.domain.recommendation.AmbientPromptPayload
import app.logdate.client.notifications.LogDateNotificationChannelKey
import app.logdate.client.notifications.LogDateNotificationRegistrar
import app.logdate.util.toReadableDateAllDay
import app.logdate.util.toReadableDateTimeShort
import io.github.aakira.napier.Napier
import studio.hypertext.logdate.R
import app.logdate.client.notifications.R as NotificationResources

class AmbientPromptNotificationCoordinator(
    private val context: Context,
    private val quickReplyEnabled: () -> Boolean = { false },
) {
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun post(candidate: AmbientPromptCandidate): Boolean {
        if (candidate.family == AmbientPromptFamily.EVENT_NUDGE && !FeatureFlag.EVENTS.availableForLaunch) return false
        return runCatching {
            val channelKey = candidate.channelKey()
            if (!LogDateNotificationRegistrar(context).canPost(channelKey)) return false
            val notificationId = channelKey.notificationId ?: candidate.dedupeKey.hashCode()
            val content = notificationContent(candidate)
            val builder =
                NotificationCompat
                    .Builder(context, channelKey.id)
                    .setSmallIcon(NotificationResources.drawable.ic_notification_capture)
                    .setContentTitle(content.title)
                    .setContentText(content.body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(content.body))
                    .setAutoCancel(true)
                    .setContentIntent(buildPendingIntent(candidate))
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setCategory(content.category)
            if (candidate.payload is AmbientPromptPayload.CaptureNudge && quickReplyEnabled()) {
                builder.addAction(writeAction(notificationId)).addAction(recordAction())
            }

            notificationManager.notify(notificationId, builder.build())
        }.onFailure { error ->
            Napier.w("Failed to post ambient prompt notification", error)
        }.isSuccess
    }

    /** Write: a reply field in the notification whose text is saved as an entry by [QuickReplyReceiver]. */
    private fun writeAction(notificationId: Int): NotificationCompat.Action {
        val reply =
            PendingIntent.getBroadcast(
                context,
                notificationId,
                Intent(ACTION_QUICK_REPLY)
                    .setClass(context, QuickReplyReceiver::class.java)
                    .putExtra(EXTRA_QUICK_REPLY_NOTIFICATION_ID, notificationId),
                // RemoteInput fills in the reply, so this intent must stay mutable.
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
        val input =
            RemoteInput
                .Builder(QUICK_REPLY_RESULT_KEY)
                .setLabel(context.getString(R.string.quick_reply_hint))
                .build()
        return NotificationCompat.Action
            .Builder(NotificationResources.drawable.ic_notification_capture, context.getString(R.string.quick_reply_write), reply)
            .addRemoteInput(input)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setAllowGeneratedReplies(false)
            .setShowsUserInterface(false)
            .build()
    }

    /** Record: opens the editor through the capture action and starts a voice entry. */
    private fun recordAction(): NotificationCompat.Action {
        val record =
            PendingIntent.getActivity(
                context,
                ACTION_CAPTURE.hashCode(),
                Intent(ACTION_CAPTURE)
                    .setClass(context, MainActivity::class.java)
                    .putExtra(EXTRA_CAPTURE_MODE, "voice")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat.Action
            .Builder(NotificationResources.drawable.ic_notification_capture, context.getString(R.string.quick_reply_record), record)
            .build()
    }

    private fun buildPendingIntent(candidate: AmbientPromptCandidate): PendingIntent {
        val launchIntent =
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                when (val payload = candidate.payload) {
                    is AmbientPromptPayload.CaptureNudge -> {
                        putExtra(EXTRA_AMBIENT_PROMPT_TARGET, AMBIENT_PROMPT_TARGET_NEW_ENTRY)
                    }

                    is AmbientPromptPayload.DraftRescue -> {
                        putExtra(EXTRA_AMBIENT_PROMPT_TARGET, AMBIENT_PROMPT_TARGET_DRAFT)
                        putExtra(EXTRA_AMBIENT_PROMPT_DRAFT_ID, payload.draftId.toString())
                    }

                    is AmbientPromptPayload.MemoryRecall -> {
                        putExtra(EXTRA_AMBIENT_PROMPT_TARGET, AMBIENT_PROMPT_TARGET_MEMORY_RECALL)
                        putExtra(EXTRA_AMBIENT_PROMPT_RECALL_DATE, payload.date.toString())
                    }

                    is AmbientPromptPayload.EventNudge -> {
                        putExtra(EXTRA_AMBIENT_PROMPT_TARGET, AMBIENT_PROMPT_TARGET_EVENT_DETAIL)
                        putExtra(EXTRA_AMBIENT_PROMPT_EVENT_ID, payload.eventId.toString())
                    }
                }
            }

        return PendingIntent.getActivity(
            context,
            candidate.dedupeKey.hashCode(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun AmbientPromptCandidate.channelKey(): LogDateNotificationChannelKey =
        when (family) {
            AmbientPromptFamily.CAPTURE_NUDGES -> LogDateNotificationChannelKey.CAPTURE_NUDGES

            AmbientPromptFamily.DRAFT_RESCUE -> LogDateNotificationChannelKey.DRAFT_RESCUE

            AmbientPromptFamily.MEMORY_RECALL -> LogDateNotificationChannelKey.MEMORY_RECALL

            // Event nudges live on the capture-nudge channel — they're the same kind of
            // "act now" reminder semantically, and the user already groups channel preferences
            // for capture nudges; piggybacking avoids forcing them to manage another channel.
            AmbientPromptFamily.EVENT_NUDGE -> LogDateNotificationChannelKey.CAPTURE_NUDGES
        }

    private fun notificationContent(candidate: AmbientPromptCandidate): AmbientNotificationContent =
        when (val payload = candidate.payload) {
            is AmbientPromptPayload.CaptureNudge -> {
                when (payload.style) {
                    AmbientCaptureNudgeStyle.MORNING -> {
                        AmbientNotificationContent(
                            title = context.getString(NotificationResources.string.ambient_prompt_capture_morning_title),
                            body = context.getString(NotificationResources.string.ambient_prompt_capture_morning_body),
                            category = NotificationCompat.CATEGORY_REMINDER,
                        )
                    }

                    AmbientCaptureNudgeStyle.EVENING -> {
                        AmbientNotificationContent(
                            title = context.getString(NotificationResources.string.ambient_prompt_capture_evening_title),
                            body = context.getString(NotificationResources.string.ambient_prompt_capture_evening_body),
                            category = NotificationCompat.CATEGORY_REMINDER,
                        )
                    }

                    AmbientCaptureNudgeStyle.NOVEL_PLACE -> {
                        AmbientNotificationContent(
                            title =
                                payload.placeName?.let {
                                    context.getString(NotificationResources.string.ambient_prompt_capture_novel_place_title, it)
                                } ?: context.getString(NotificationResources.string.ambient_prompt_capture_evening_title),
                            body =
                                payload.placeName?.let {
                                    context.getString(NotificationResources.string.ambient_prompt_capture_novel_place_body)
                                } ?: context.getString(NotificationResources.string.ambient_prompt_capture_novel_place_body_fallback),
                            category = NotificationCompat.CATEGORY_REMINDER,
                        )
                    }
                }
            }

            is AmbientPromptPayload.DraftRescue -> {
                AmbientNotificationContent(
                    title = context.getString(NotificationResources.string.ambient_prompt_draft_rescue_title),
                    body = context.getString(NotificationResources.string.ambient_prompt_draft_rescue_body),
                    category = NotificationCompat.CATEGORY_REMINDER,
                )
            }

            is AmbientPromptPayload.MemoryRecall -> {
                AmbientNotificationContent(
                    title = context.getString(NotificationResources.string.ambient_prompt_memory_recall_title),
                    body = payload.summary.ifBlank { context.getString(NotificationResources.string.ambient_prompt_memory_recall_body) },
                    category = NotificationCompat.CATEGORY_RECOMMENDATION,
                )
            }

            is AmbientPromptPayload.EventNudge -> {
                AmbientNotificationContent(
                    title = payload.title,
                    body = eventNudgeBody(payload),
                    category = NotificationCompat.CATEGORY_REMINDER,
                )
            }
        }

    /**
     * Builds the event-nudge body. All-day events show their date only (their start is anchored to
     * UTC midnight, so a clock time would be meaningless and could read as the wrong day); timed
     * events show the local start time.
     */
    private fun eventNudgeBody(payload: AmbientPromptPayload.EventNudge): String =
        if (payload.isAllDay) {
            context.getString(
                NotificationResources.string.ambient_prompt_event_nudge_all_day_body,
                payload.startTime.toReadableDateAllDay(),
            )
        } else {
            context.getString(
                NotificationResources.string.ambient_prompt_event_nudge_body,
                payload.startTime.toReadableDateTimeShort(),
            )
        }
}

private data class AmbientNotificationContent(
    val title: String,
    val body: String,
    val category: String,
)
