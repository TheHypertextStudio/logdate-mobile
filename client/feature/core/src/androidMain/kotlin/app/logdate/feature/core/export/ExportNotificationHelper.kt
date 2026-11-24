package app.logdate.feature.core.export

import android.content.Context
import app.logdate.client.feature.core.R
import app.logdate.client.notifications.LogDateNotificationChannelKey
import app.logdate.feature.core.notifications.DataTransferNotificationHelper
import kotlin.uuid.Uuid

/**
 * Export-specific notification helper. Delegates all notification building
 * to [DataTransferNotificationHelper] and adds export-specific completion
 * content (file path).
 */
class ExportNotificationHelper(
    context: Context,
    workId: Uuid,
) : DataTransferNotificationHelper(context, workId) {
    override val channelId = LogDateNotificationChannelKey.DATA_EXPORT.id
    override val notificationId = LogDateNotificationChannelKey.DATA_EXPORT.notificationId ?: 1003
    override val progressTitleResId = R.string.export_title_progress
    override val completeTitleResId = R.string.export_title_complete
    override val failedTitleResId = R.string.export_title_failed

    override fun showCompletionNotification(contentText: String) {
        super.showCompletionNotification(context.getString(R.string.export_text_path, contentText))
    }
}
