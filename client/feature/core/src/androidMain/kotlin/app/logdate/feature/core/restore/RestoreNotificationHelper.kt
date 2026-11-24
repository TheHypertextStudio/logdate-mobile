package app.logdate.feature.core.restore

import android.content.Context
import androidx.work.ForegroundInfo
import app.logdate.client.feature.core.R
import app.logdate.client.notifications.LogDateNotificationChannelKey
import app.logdate.feature.core.notifications.DataTransferNotificationHelper
import kotlin.uuid.Uuid

/**
 * Restore-specific notification helper. Resolves typed [RestoreStage] values
 * to localized messages and progress percentages, then delegates all
 * notification building to [DataTransferNotificationHelper].
 */
class RestoreNotificationHelper(
    context: Context,
    workId: Uuid,
) : DataTransferNotificationHelper(context, workId) {
    override val channelId = LogDateNotificationChannelKey.DATA_RESTORE.id
    override val notificationId = (workId.hashCode() and 0x3fffffff) + 1002
    override val progressTitleResId = R.string.restore_title_progress
    private var incompleteMedia = false
    override val completeTitleResId get() = if (incompleteMedia) R.string.restore_title_partial else R.string.restore_title_complete
    override val failedTitleResId = R.string.restore_title_failed
    override val phaseStarts = RestoreStage.entries.map { it.defaultProgressPercent }

    fun createForegroundInfo(stage: RestoreStage): ForegroundInfo =
        createForegroundInfo(
            progress = stage.defaultProgressPercent,
            message = resolveStageMessage(stage),
        )

    fun showCompletionNotification(unavailable: Int = 0) {
        incompleteMedia = unavailable > 0
        super.showCompletionNotification(
            context.getString(
                if (incompleteMedia) R.string.restore_notification_partial_message else R.string.restore_notification_complete_message,
            ),
        )
    }

    private fun resolveStageMessage(stage: RestoreStage): String =
        when (stage) {
            RestoreStage.IDLE -> context.getString(R.string.restore_notification_stage_preparing)
            RestoreStage.PREPARING -> context.getString(R.string.restore_notification_stage_preparing)
            RestoreStage.COPYING_ARCHIVE -> context.getString(R.string.restore_notification_stage_copying)
            RestoreStage.OPENING_ARCHIVE -> context.getString(R.string.restore_notification_stage_opening)
            RestoreStage.READING_CONTENTS -> context.getString(R.string.restore_notification_stage_reading)
            RestoreStage.RESTORING_JOURNALS -> context.getString(R.string.restore_notification_stage_journals)
            RestoreStage.RESTORING_NOTES -> context.getString(R.string.restore_notification_stage_notes)
            RestoreStage.RESTORING_LINKS -> context.getString(R.string.restore_notification_stage_links)
            RestoreStage.RESTORING_DRAFTS -> context.getString(R.string.restore_notification_stage_drafts)
            RestoreStage.RESTORING_PROFILE -> context.getString(R.string.restore_notification_stage_profile)
            RestoreStage.RESTORING_PLACES -> context.getString(R.string.restore_notification_stage_places)
            RestoreStage.RESTORING_LOCATION_HISTORY -> context.getString(R.string.restore_notification_stage_location_history)
            RestoreStage.IMPORTING_MEDIA -> context.getString(R.string.restore_notification_stage_media)
        }
}
