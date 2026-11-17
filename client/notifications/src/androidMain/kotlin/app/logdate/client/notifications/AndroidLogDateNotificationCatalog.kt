package app.logdate.client.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

data class AndroidLogDateNotificationGroupSpec(
    val key: LogDateNotificationGroupKey,
    val nameResId: Int,
)

data class AndroidLogDateNotificationChannelSpec(
    val key: LogDateNotificationChannelKey,
    val nameResId: Int,
    val descriptionResId: Int,
    val importance: Int,
    val showBadge: Boolean = true,
    val vibrationEnabled: Boolean = false,
    val soundEnabled: Boolean = false,
    val lockscreenVisibility: Int = Notification.VISIBILITY_PRIVATE,
    val allowBubbles: Boolean = false,
)

object AndroidLogDateNotificationCatalog {
    val groups: List<AndroidLogDateNotificationGroupSpec> =
        listOf(
            AndroidLogDateNotificationGroupSpec(
                key = LogDateNotificationGroupKey.CAPTURE_PLAYBACK,
                nameResId = R.string.notification_group_capture_playback,
            ),
            AndroidLogDateNotificationGroupSpec(
                key = LogDateNotificationGroupKey.BACKGROUND_SERVICES,
                nameResId = R.string.notification_group_background_services,
            ),
            AndroidLogDateNotificationGroupSpec(
                key = LogDateNotificationGroupKey.AMBIENT_INTELLIGENCE,
                nameResId = R.string.notification_group_ambient_intelligence,
            ),
            AndroidLogDateNotificationGroupSpec(
                key = LogDateNotificationGroupKey.CONNECTED_DEVICES,
                nameResId = R.string.notification_group_connected_devices,
            ),
            AndroidLogDateNotificationGroupSpec(
                key = LogDateNotificationGroupKey.IMPORT_EXPORT,
                nameResId = R.string.notification_group_import_export,
            ),
        )

    val phoneChannels: List<AndroidLogDateNotificationChannelSpec> =
        listOf(
            AndroidLogDateNotificationChannelSpec(
                key = LogDateNotificationChannelKey.AUDIO_RECORDING,
                nameResId = R.string.notification_channel_audio_recording_name,
                descriptionResId = R.string.notification_channel_audio_recording_description,
                importance = NotificationManager.IMPORTANCE_LOW,
                showBadge = false,
                vibrationEnabled = false,
                soundEnabled = false,
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC,
            ),
            AndroidLogDateNotificationChannelSpec(
                key = LogDateNotificationChannelKey.FLOATING_RECORDER,
                nameResId = R.string.notification_channel_floating_recorder_name,
                descriptionResId = R.string.notification_channel_floating_recorder_description,
                importance = NotificationManager.IMPORTANCE_DEFAULT,
                showBadge = false,
                vibrationEnabled = false,
                soundEnabled = false,
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE,
                allowBubbles = true,
            ),
            AndroidLogDateNotificationChannelSpec(
                key = LogDateNotificationChannelKey.AUDIO_PLAYBACK,
                nameResId = R.string.notification_channel_audio_playback_name,
                descriptionResId = R.string.notification_channel_audio_playback_description,
                importance = NotificationManager.IMPORTANCE_LOW,
                showBadge = false,
                vibrationEnabled = false,
                soundEnabled = false,
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC,
            ),
            AndroidLogDateNotificationChannelSpec(
                key = LogDateNotificationChannelKey.LOCATION_HISTORY,
                nameResId = R.string.notification_channel_location_history_name,
                descriptionResId = R.string.notification_channel_location_history_description,
                importance = NotificationManager.IMPORTANCE_MIN,
                showBadge = false,
                vibrationEnabled = false,
                soundEnabled = false,
                lockscreenVisibility = Notification.VISIBILITY_SECRET,
            ),
            AndroidLogDateNotificationChannelSpec(
                key = LogDateNotificationChannelKey.CAPTURE_NUDGES,
                nameResId = R.string.notification_channel_capture_nudges_name,
                descriptionResId = R.string.notification_channel_capture_nudges_description,
                importance = NotificationManager.IMPORTANCE_DEFAULT,
                showBadge = true,
                vibrationEnabled = false,
                soundEnabled = true,
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE,
            ),
            AndroidLogDateNotificationChannelSpec(
                key = LogDateNotificationChannelKey.DRAFT_RESCUE,
                nameResId = R.string.notification_channel_draft_rescue_name,
                descriptionResId = R.string.notification_channel_draft_rescue_description,
                importance = NotificationManager.IMPORTANCE_DEFAULT,
                showBadge = true,
                vibrationEnabled = false,
                soundEnabled = true,
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE,
            ),
            AndroidLogDateNotificationChannelSpec(
                key = LogDateNotificationChannelKey.MEMORY_RECALL,
                nameResId = R.string.notification_channel_memory_recall_name,
                descriptionResId = R.string.notification_channel_memory_recall_description,
                importance = NotificationManager.IMPORTANCE_DEFAULT,
                showBadge = true,
                vibrationEnabled = false,
                soundEnabled = true,
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE,
            ),
            AndroidLogDateNotificationChannelSpec(
                key = LogDateNotificationChannelKey.REWIND_READY,
                nameResId = R.string.notification_channel_rewind_ready_name,
                descriptionResId = R.string.notification_channel_rewind_ready_description,
                importance = NotificationManager.IMPORTANCE_HIGH,
                showBadge = true,
                vibrationEnabled = true,
                soundEnabled = true,
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE,
            ),
            AndroidLogDateNotificationChannelSpec(
                key = LogDateNotificationChannelKey.WATCH_SYNC,
                nameResId = R.string.notification_channel_watch_sync_name,
                descriptionResId = R.string.notification_channel_watch_sync_description,
                importance = NotificationManager.IMPORTANCE_DEFAULT,
                showBadge = true,
                vibrationEnabled = false,
                soundEnabled = false,
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE,
            ),
            AndroidLogDateNotificationChannelSpec(
                key = LogDateNotificationChannelKey.DATA_EXPORT,
                nameResId = R.string.notification_channel_data_export_name,
                descriptionResId = R.string.notification_channel_data_export_description,
                importance = NotificationManager.IMPORTANCE_LOW,
                showBadge = false,
                vibrationEnabled = false,
                soundEnabled = false,
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE,
            ),
            AndroidLogDateNotificationChannelSpec(
                key = LogDateNotificationChannelKey.DATA_RESTORE,
                nameResId = R.string.notification_channel_data_restore_name,
                descriptionResId = R.string.notification_channel_data_restore_description,
                importance = NotificationManager.IMPORTANCE_LOW,
                showBadge = false,
                vibrationEnabled = false,
                soundEnabled = false,
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE,
            ),
            AndroidLogDateNotificationChannelSpec(
                key = LogDateNotificationChannelKey.CLOUD_SYNC,
                nameResId = R.string.notification_channel_cloud_sync_name,
                descriptionResId = R.string.notification_channel_cloud_sync_description,
                importance = NotificationManager.IMPORTANCE_LOW,
                showBadge = false,
                vibrationEnabled = false,
                soundEnabled = false,
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE,
            ),
        )

    val watchChannels: List<AndroidLogDateNotificationChannelSpec> =
        listOf(
            AndroidLogDateNotificationChannelSpec(
                key = LogDateNotificationChannelKey.MOMENT_REMINDERS,
                nameResId = R.string.notification_channel_moment_reminders_name,
                descriptionResId = R.string.notification_channel_moment_reminders_description,
                importance = NotificationManager.IMPORTANCE_DEFAULT,
                showBadge = false,
                vibrationEnabled = true,
                soundEnabled = false,
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE,
            ),
        )

    fun channel(key: LogDateNotificationChannelKey): AndroidLogDateNotificationChannelSpec =
        (phoneChannels + watchChannels).first {
            it.key ==
                key
        }
}

class LogDateNotificationRegistrar(
    private val context: Context,
) {
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** Destination permission and channel choices govern delivery, independently of restored settings. */
    fun canPost(key: LogDateNotificationChannelKey): Boolean {
        registerChannel(key)
        return canPostToChannel(key.id)
    }

    /** Checks an existing channel without changing its importance or group. */
    fun canPostToChannel(channelId: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        if (!notificationManager.areNotificationsEnabled()) return false
        val channel = notificationManager.getNotificationChannel(channelId) ?: return false
        if (channel.importance == NotificationManager.IMPORTANCE_NONE) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            channel.group?.let { notificationManager.getNotificationChannelGroup(it)?.isBlocked } == true
        ) {
            return false
        }
        return true
    }

    fun registerAllPhoneChannels() {
        notificationManager.createNotificationChannelGroups(
            AndroidLogDateNotificationCatalog.groups.map { group ->
                NotificationChannelGroup(group.key.id, context.getString(group.nameResId))
            },
        )

        deleteLegacyChannels()

        notificationManager.createNotificationChannels(
            AndroidLogDateNotificationCatalog.phoneChannels.map(::buildChannel),
        )
    }

    fun registerChannel(key: LogDateNotificationChannelKey) {
        val group = AndroidLogDateNotificationCatalog.groups.first { it.key == key.groupKey }
        notificationManager.createNotificationChannelGroup(
            NotificationChannelGroup(group.key.id, context.getString(group.nameResId)),
        )
        notificationManager.createNotificationChannel(
            buildChannel(AndroidLogDateNotificationCatalog.channel(key)),
        )
    }

    private fun deleteLegacyChannels() {
        LogDateNotificationChannels.legacyChannelIds.forEach(notificationManager::deleteNotificationChannel)
    }

    private fun buildChannel(spec: AndroidLogDateNotificationChannelSpec): NotificationChannel =
        NotificationChannel(
            spec.key.id,
            context.getString(spec.nameResId),
            spec.importance,
        ).apply {
            description = context.getString(spec.descriptionResId)
            group = spec.key.groupKey.id
            setShowBadge(spec.showBadge)
            enableVibration(spec.vibrationEnabled)
            lockscreenVisibility = spec.lockscreenVisibility
            if (!spec.soundEnabled) {
                setSound(null, null)
            }
            if (spec.allowBubbles) {
                setAllowBubbles(true)
            }
        }
}
