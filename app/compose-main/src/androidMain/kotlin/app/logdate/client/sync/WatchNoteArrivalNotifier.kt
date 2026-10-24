package app.logdate.client.sync

import android.content.Context
import androidx.annotation.StringRes
import app.logdate.client.domain.watch.WatchSettingsRepository
import app.logdate.client.repository.journals.JournalNote
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.first
import kotlin.uuid.Uuid
import app.logdate.client.notifications.R as NotificationResources

/** The words on the notification for one note that arrived from the watch. */
data class WatchNoteArrivalContent(
    val title: String,
    val body: String,
)

/** Shows the notification for one watch note; [AndroidWatchNoteNotificationPoster] is the real one. */
fun interface WatchNoteNotificationPoster {
    fun post(
        noteId: Uuid,
        content: WatchNoteArrivalContent,
    )
}

/**
 * Tells you a note arrived from your watch, only while "Show notifications for new entries" is on
 * in the Watch settings, and in the phone's language.
 */
class WatchNoteArrivalNotifier(
    private val context: Context,
    private val settingsRepository: WatchSettingsRepository,
    private val poster: WatchNoteNotificationPoster,
) : WatchNoteNotifier {
    override suspend fun noteReceived(note: JournalNote) {
        if (!settingsRepository.observeNotificationSettings().first().showEntryNotifications) return
        try {
            poster.post(note.uid, contentFor(note))
        } catch (e: RuntimeException) {
            Napier.e("Could not show the notification for watch note ${note.uid}", e)
        }
    }

    private fun contentFor(note: JournalNote): WatchNoteArrivalContent =
        when (note) {
            is JournalNote.Handwriting -> {
                openInEditor(NotificationResources.string.notification_watch_note_handwriting_title)
            }

            is JournalNote.Audio -> {
                openInEditor(NotificationResources.string.notification_watch_note_audio_title)
            }

            is JournalNote.Image -> {
                openInEditor(NotificationResources.string.notification_watch_note_photo_title)
            }

            is JournalNote.Video -> {
                openInEditor(NotificationResources.string.notification_watch_note_video_title)
            }

            is JournalNote.Text -> {
                WatchNoteArrivalContent(
                    title = context.getString(NotificationResources.string.notification_watch_note_text_title),
                    body = note.content.take(TEXT_PREVIEW_LENGTH),
                )
            }
        }

    private fun openInEditor(
        @StringRes title: Int,
    ): WatchNoteArrivalContent =
        WatchNoteArrivalContent(
            title = context.getString(title),
            body = context.getString(NotificationResources.string.notification_watch_note_open_body),
        )

    private companion object {
        const val TEXT_PREVIEW_LENGTH = 80
    }
}
