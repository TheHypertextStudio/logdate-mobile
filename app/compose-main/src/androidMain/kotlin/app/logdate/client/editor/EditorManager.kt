package app.logdate.client.editor

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import app.logdate.client.EditorActivity
import io.github.aakira.napier.Napier
import kotlin.uuid.Uuid

/**
 * Manager class for creating and handling multiple editor windows.
 *
 * This class provides a central point for launching editor windows and
 * managing multi-window capabilities.
 */
class EditorManager(
    private val context: Context,
) {
    fun supportsMultiWindow(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N

    /**
     * Opens a new entry in its own window.
     *
     * @param adjacentTo The activity to place the window beside, in split screen. Android needs the
     *   launch to come from that activity: a launch from the application context has no window to be
     *   adjacent to, so it opens full screen. Android ignores the request where there is no room.
     */
    fun openNewEditorWindow(
        initialText: String? = null,
        attachments: List<String>? = null,
        journalIds: List<Uuid> = emptyList(),
        adjacentTo: Activity? = null,
    ) {
        try {
            val intent =
                EditorActivity.createIntent(
                    context = context,
                    initialText = initialText,
                    attachments = attachments,
                    journalIds = journalIds,
                )
            if (adjacentTo != null) intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
            (adjacentTo ?: context).startActivity(intent)
            Napier.d("Launched new editor window")
        } catch (e: Exception) {
            Napier.e("Failed to open new editor window", e)
        }
    }

    /**
     * Opens the existing entry [entryId] in its own window.
     *
     * @param adjacentTo The activity to place the window beside, as for [openNewEditorWindow].
     */
    fun openEntryInNewWindow(
        entryId: Uuid,
        journalId: Uuid? = null,
        adjacentTo: Activity? = null,
    ) {
        try {
            val intent =
                EditorActivity.createIntent(
                    context = context,
                    entryId = entryId,
                    journalId = journalId,
                )
            if (adjacentTo != null) intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
            (adjacentTo ?: context).startActivity(intent)
            Napier.d("Opened entry $entryId in new window")
        } catch (e: Exception) {
            Napier.e("Failed to open entry in new window", e)
        }
    }
}
