package app.logdate.client.media.audio

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.logdate.client.media.R
import app.logdate.client.notifications.ACTION_FLOAT_RECORDER
import app.logdate.client.notifications.FLOAT_RECORDER_RECEIVER_CLASS
import app.logdate.client.notifications.LiveUpdateChip
import app.logdate.client.notifications.LiveUpdatesSwitch
import app.logdate.client.notifications.LogDateNotificationChannelKey
import app.logdate.client.notifications.LogDateNotificationRegistrar
import app.logdate.client.notifications.RecordingBubbleSwitch
import app.logdate.client.notifications.recordingLiveUpdate
import io.github.aakira.napier.Napier
import org.koin.mp.KoinPlatform
import kotlin.time.Clock

/**
 * Handler for audio recording notifications on Android.
 *
 * Creates and manages foreground notification required for audio recording. When the app's
 * `Application` supplies a [RecordingNotificationDecorator], every recording notification goes
 * through it before it is built.
 */
class AndroidAudioNotificationHandler(
    private val context: Context,
    private val liveUpdates: LiveUpdatesSwitch =
        KoinPlatform.getKoinOrNull()?.getOrNull<LiveUpdatesSwitch>() ?: LiveUpdatesSwitch.Off,
    private val recordingBubble: RecordingBubbleSwitch =
        KoinPlatform.getKoinOrNull()?.getOrNull<RecordingBubbleSwitch>() ?: RecordingBubbleSwitch.Off,
    private val decorator: RecordingNotificationDecorator? = recordingNotificationDecoratorOf(context.applicationContext),
) {
    companion object {
        val CHANNEL_ID = LogDateNotificationChannelKey.AUDIO_RECORDING.id
        val NOTIFICATION_ID = LogDateNotificationChannelKey.AUDIO_RECORDING.notificationId ?: 1001

        // Action constants for the notification buttons
        const val ACTION_PAUSE = "app.logdate.action.PAUSE_RECORDING"
        const val ACTION_RESUME = "app.logdate.action.RESUME_RECORDING"
        const val ACTION_STOP = "app.logdate.action.STOP_RECORDING"
    }

    private val notificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    /**
     * Creates a PendingIntent for notification actions
     */
    private fun createActionIntent(action: String): PendingIntent {
        val intent =
            Intent(context, AudioRecordingService::class.java).apply {
                this.action = action
            }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        return PendingIntent.getService(context, action.hashCode(), intent, flags)
    }

    /**
     * Creates a main activity PendingIntent to return to the editor
     */
    private fun createMainActivityIntent(): PendingIntent {
        val draftId = KoinPlatform.getKoinOrNull()?.getOrNull<AudioRecordingManager>()?.currentRecordingDraftId
        val launchIntent = recordingReturnIntent(context, draftId)

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        return PendingIntent.getActivity(context, NAV_SOURCE_AUDIO_RECORDING.hashCode(), launchIntent, flags)
    }

    /**
     * Creates a foreground notification for audio recording
     */
    fun createRecordingNotification(
        isRecording: Boolean = true,
        startTimeMillis: Long = Clock.System.now().toEpochMilliseconds(),
        recordedMillis: Long = 0L,
    ): Notification {
        LogDateNotificationRegistrar(context).registerChannel(LogDateNotificationChannelKey.AUDIO_RECORDING)
        val titleRes =
            if (isRecording) {
                R.string.audio_recording_notification_title
            } else {
                R.string.audio_recording_notification_title_paused
            }
        val textRes =
            if (isRecording) {
                R.string.audio_recording_notification_text
            } else {
                R.string.audio_recording_notification_text_paused
            }

        val builder =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_recording)
                .setContentTitle(context.getString(titleRes))
                .setContentText(context.getString(textRes))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setUsesChronometer(true)
                .setWhen(startTimeMillis)
                .setContentIntent(createMainActivityIntent())
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setSilent(true)

        applyLiveUpdate(builder, isRecording)
        addRecordingActions(builder, isRecording)
        decorate(builder, RecordingNotificationState(isRecording, startTimeMillis, recordedMillis))
        return builder.build()
    }

    /** Promotes the notification to a Live Update when the flag allows, with a chip while paused. */
    private fun applyLiveUpdate(
        builder: NotificationCompat.Builder,
        isRecording: Boolean,
    ) {
        val liveUpdate = recordingLiveUpdate(enabled = liveUpdates.enabled, isRecording = isRecording)
        if (!liveUpdate.promoted) return
        builder.setRequestPromotedOngoing(true)
        liveUpdate.chipText?.let { chip ->
            builder.setShortCriticalText(
                when (chip) {
                    LiveUpdateChip.Paused -> context.getString(R.string.audio_recording_chip_paused)
                },
            )
        }
    }

    /** Pause or Resume for the current state, Float while the bubble flag is on, and Stop. */
    private fun addRecordingActions(
        builder: NotificationCompat.Builder,
        isRecording: Boolean,
    ) {
        if (isRecording) {
            builder.addAction(
                android.R.drawable.ic_media_pause,
                context.getString(R.string.audio_recording_action_pause),
                createActionIntent(ACTION_PAUSE),
            )
        } else {
            builder.addAction(
                android.R.drawable.ic_media_play,
                context.getString(R.string.audio_recording_action_resume),
                createActionIntent(ACTION_RESUME),
            )
        }
        if (recordingBubble.enabled) {
            val float = Intent(ACTION_FLOAT_RECORDER).setClassName(context, FLOAT_RECORDER_RECEIVER_CLASS)
            builder.addAction(
                R.drawable.ic_notification_recording,
                context.getString(R.string.audio_recording_action_float),
                PendingIntent.getBroadcast(
                    context,
                    ACTION_FLOAT_RECORDER.hashCode(),
                    float,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        builder.addAction(
            android.R.drawable.ic_delete,
            context.getString(R.string.audio_recording_action_stop),
            createActionIntent(ACTION_STOP),
        )
    }

    /** A decorator that fails never stops the recording notification; it is posted without the decoration. */
    private fun decorate(
        builder: NotificationCompat.Builder,
        state: RecordingNotificationState,
    ) {
        val decorator = decorator ?: return
        try {
            decorator.decorate(context, builder, NOTIFICATION_ID, state)
        } catch (e: Exception) {
            Napier.e("The recording notification decorator failed; posting the notification without it", e)
        }
    }

    /**
     * Updates notification to reflect current recording state
     */
    fun updateRecordingNotification(
        isRecording: Boolean,
        startTimeMillis: Long,
        recordedMillis: Long = 0L,
    ) {
        try {
            val notification = createRecordingNotification(isRecording, startTimeMillis, recordedMillis)
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Napier.e("Failed to update recording notification", e)
        }
    }
}
