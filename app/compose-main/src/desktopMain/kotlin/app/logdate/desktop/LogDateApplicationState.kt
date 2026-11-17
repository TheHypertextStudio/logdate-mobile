package app.logdate.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Global state for the LogDate application.
 *
 * This manages state for the entire application, including all windows, dialogs, and notifications.
 *
 * The main window is a single, always-present `val`; editor windows live in a separate list and
 * can be opened and closed independently. Combining them in [windows] preserves the legacy
 * iteration order while making the "exactly one main" invariant structural.
 */
class LogDateApplicationState {
    val mainWindow: MainWindowState = MainWindowState(this)

    private val _editorWindows = mutableStateListOf<EntryEditorWindowState>()
    val editorWindows: List<EntryEditorWindowState>
        get() = _editorWindows

    val windows: List<LogDateWindowState>
        get() =
            buildList(_editorWindows.size + 1) {
                add(mainWindow)
                addAll(_editorWindows)
            }

    /**
     * Set to true when the application should terminate. [LogDateApplication] observes this and
     * invokes `ApplicationScope.exitApplication()` so the process can shut down cleanly.
     */
    var shouldExitApplication: Boolean by mutableStateOf(false)
        private set

    /**
     * True while the main-window close request is fanning out through the editor windows. Used so
     * the last editor to actually close (after persisting its draft) can trip
     * [shouldExitApplication] without the application state needing to know which save finished
     * last. Read-only from outside so menus can disable "Cancel Quit" unless there's a quit to
     * cancel.
     */
    var exitCascadeInFlight: Boolean by mutableStateOf(false)
        private set

    fun openNoteEditor() {
        if (exitCascadeInFlight || shouldExitApplication) return
        _editorWindows.add(EntryEditorWindowState(this))
    }

    /**
     * Closes a single window. Returns true when the window actually closed (or, for the main
     * window, when an orderly application exit was kicked off); false when
     * [LogDateWindowState.exit] vetoed the close.
     *
     * Closing the main window asks every editor window to close itself. Each editor saves its
     * dirty draft through its own save flow, and the application exits once the last editor has
     * finished closing. If an editor's save fails, the application stays alive with the editor's
     * error visible so the user can intervene.
     */
    fun closeWindow(window: LogDateWindowState): Boolean =
        when (window) {
            mainWindow -> beginApplicationExit()
            is EntryEditorWindowState -> closeEditor(window)
            else -> false
        }

    /**
     * Closes all windows, performing cleanup as needed, and exits the application.
     */
    fun exit() {
        beginApplicationExit()
    }

    /**
     * Aborts an in-flight quit so editors that haven't finished saving stay open. No-op when no
     * quit is in flight (so the user can mash the shortcut without surprise). Once the cascade has
     * already flipped [shouldExitApplication], the application is past the point of return —
     * `cancelExit` will not resurrect it.
     */
    fun cancelExit() {
        if (!exitCascadeInFlight || shouldExitApplication) return
        exitCascadeInFlight = false
        for (editor in _editorWindows) {
            editor.clearCloseRequest()
        }
    }

    private fun closeEditor(editor: EntryEditorWindowState): Boolean {
        if (!editor.exit()) return false
        _editorWindows.remove(editor)
        if (exitCascadeInFlight && _editorWindows.isEmpty()) {
            shouldExitApplication = true
        }
        return true
    }

    private fun beginApplicationExit(): Boolean {
        if (!mainWindow.exit()) return false
        if (_editorWindows.isEmpty()) {
            shouldExitApplication = true
            return true
        }
        // Fan out the close request to each editor; the last one to finish trips the exit flag.
        exitCascadeInFlight = true
        for (editor in _editorWindows) {
            editor.requestClose()
        }
        return true
    }
}

/**
 * Utility function to remember the [LogDateApplicationState] in a composable.
 */
@Composable
fun rememberApplicationState(): LogDateApplicationState =
    remember {
        LogDateApplicationState()
    }

/**
 * A generic interface for a window in the LogDate application.
 */
sealed interface LogDateWindowState {
    /**
     * Attempts to close this window.
     *
     * This method should perform any necessary cleanup and check if the window can be closed using
     * domain-specific logic. Once the method returns, it is assumed that the window has already
     * performed all necessary cleanup and can be safely removed from the application state.
     *
     * @return `true` if the window can be closed, `false` if the window should remain open.
     */
    fun exit(): Boolean
}
