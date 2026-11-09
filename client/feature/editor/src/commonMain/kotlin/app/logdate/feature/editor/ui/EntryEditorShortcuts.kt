package app.logdate.feature.editor.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import app.logdate.ui.keyboard.AppKeyboardShortcut
import app.logdate.ui.keyboard.AppShortcutScope

internal fun Modifier.entryEditorShortcuts(
    canSave: Boolean,
    onClose: () -> Unit,
    onSave: () -> Unit,
): Modifier =
    onPreviewKeyEvent { event ->
        when (AppKeyboardShortcut.match(event, AppShortcutScope.Editor)) {
            AppKeyboardShortcut.SaveEntry -> {
                canSave.also { if (it) onSave() }
            }

            AppKeyboardShortcut.CloseEditor -> {
                onClose()
                true
            }

            else -> {
                false
            }
        }
    }
