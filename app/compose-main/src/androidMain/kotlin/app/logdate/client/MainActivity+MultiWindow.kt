@file:Suppress("ktlint:standard:filename")

package app.logdate.client

import android.content.Intent
import androidx.lifecycle.lifecycleScope
import app.logdate.client.editor.EditorManager
import app.logdate.client.media.MediaManager
import app.logdate.client.shortcuts.parseLauncherShortcutTargetJournalId
import app.logdate.ui.common.EditorWindows
import io.github.aakira.napier.Napier
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import kotlin.uuid.Uuid

/** Initializes the multi-window feature gate without starting any background observers. */
fun MainActivity.setupMultiWindowSupport() {
    val editorManager: EditorManager by inject()
    if (!editorManager.supportsMultiWindow()) {
        return
    }
}

fun MainActivity.handleMultiWindowIntent(intent: Intent): Boolean {
    val editorManager: EditorManager by inject()
    val mediaManager: MediaManager by inject()

    return when (intent.action) {
        Intent.ACTION_SEND,
        Intent.ACTION_SEND_MULTIPLE,
        -> {
            lifecycleScope.launch {
                val sharedContent = importIncomingEditorShare(intent, mediaManager)
                if (sharedContent == null) {
                    Napier.w("Ignored unsupported incoming share intent")
                    return@launch
                }
                editorManager.openNewEditorWindow(
                    initialText = sharedContent.initialText,
                    attachments = sharedContent.attachments,
                    journalIds = sharedContent.targetJournalIds,
                )
                finish()
            }
            true
        }

        Intent.ACTION_VIEW -> {
            val targetJournalId = intent.parseLauncherShortcutTargetJournalId() ?: return false
            editorManager.openNewEditorWindow(journalIds = listOf(targetJournalId))
            finish()
            true
        }

        else -> {
            false
        }
    }
}

/**
 * Lets screens open entries in their own windows, or null where this device cannot show another
 * LogDate window. New windows open beside this one in split screen or when the window is wide.
 */
internal fun MainActivity.editorWindows(): EditorWindows? {
    val editorManager: EditorManager by inject()
    if (!editorManager.supportsMultiWindow()) return null
    return object : EditorWindows {
        override fun openNewEntry(journalId: String?) {
            val journalIds = listOfNotNull(journalId?.let { runCatching { Uuid.parse(it) }.getOrNull() })
            editorManager.openNewEditorWindow(journalIds = journalIds, adjacentTo = adjacentWindowAnchor())
        }

        override fun openEntry(entryId: String) {
            val id = runCatching { Uuid.parse(entryId) }.getOrNull() ?: return
            editorManager.openEntryInNewWindow(id, adjacentTo = adjacentWindowAnchor())
        }
    }
}

/** This activity when a new window should open beside it: in split screen, or when the window is wide. */
private fun MainActivity.adjacentWindowAnchor(): MainActivity? =
    takeIf { isInMultiWindowMode || resources.configuration.screenWidthDp >= EXPANDED_WIDTH_DP }

private const val EXPANDED_WIDTH_DP = 840
