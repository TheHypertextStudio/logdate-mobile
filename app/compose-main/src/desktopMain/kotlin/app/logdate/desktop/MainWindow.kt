@file:OptIn(FlowPreview::class)

package app.logdate.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import app.logdate.feature.core.AppViewModel
import app.logdate.feature.core.GlobalAppUiLoadedState
import app.logdate.navigation.AppShortcutRequests
import app.logdate.navigation.LogDateNavDisplay
import app.logdate.ui.keyboard.AppKeyboardShortcut
import app.logdate.ui.keyboard.KeyChord
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import logdate.app.composemain.generated.resources.Res
import logdate.app.composemain.generated.resources.desktop_menu_cancel_quit
import logdate.app.composemain.generated.resources.desktop_menu_close_window
import logdate.app.composemain.generated.resources.desktop_menu_file
import logdate.app.composemain.generated.resources.desktop_menu_go
import logdate.app.composemain.generated.resources.desktop_menu_quit
import logdate.app.composemain.generated.resources.desktop_window_title
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

private val DEFAULT_MAIN_WINDOW_SIZE = DpSize(1024.dp, 720.dp)
private const val RESIZE_PERSIST_DEBOUNCE_MS = 300L

@Suppress("ktlint:standard:function-naming")
@Composable
internal fun MainWindow(
    appState: LogDateApplicationState,
    onOpenEditor: () -> Unit,
    state: MainWindowState = rememberMainWindowState(appState),
) {
    val preferences = remember { DesktopWindowPreferences() }
    val shortcutRequests = remember { AppShortcutRequests() }
    val windowState =
        rememberWindowState(
            size = preferences.readMainWindowSize(DEFAULT_MAIN_WINDOW_SIZE),
            position = preferences.readMainWindowPosition(),
        )

    LaunchedEffect(Unit) {
        snapshotFlow { windowState.size }
            .distinctUntilChanged()
            .debounce(RESIZE_PERSIST_DEBOUNCE_MS)
            .collect { size -> preferences.writeMainWindowSize(size) }
    }

    LaunchedEffect(Unit) {
        snapshotFlow { windowState.position }
            .distinctUntilChanged()
            .debounce(RESIZE_PERSIST_DEBOUNCE_MS)
            .collect { position -> preferences.writeMainWindowPosition(position) }
    }

    Window(
        onCloseRequest = { appState.closeWindow(state) },
        state = windowState,
        title = stringResource(Res.string.desktop_window_title),
    ) {
        // Intercept Cmd+Q (and the platform equivalent) so it routes through the
        // same save-aware exit cascade as closing the main window — bypassing the
        // JVM's default Quit handler would skip every editor's save-before-close.
        MenuBar {
            Menu(text = stringResource(Res.string.desktop_menu_file), mnemonic = 'F') {
                ShortcutItems(listOf(AppKeyboardShortcut.NewEntry, AppKeyboardShortcut.RecordVoice), shortcutRequests::request)
                Separator()
                Item(
                    text = stringResource(Res.string.desktop_menu_close_window),
                    shortcut = KeyChord(Key.W, primary = true).toKeyShortcut(),
                    onClick = { appState.closeWindow(state) },
                )
                Separator()
                Item(
                    text = stringResource(Res.string.desktop_menu_quit),
                    shortcut = KeyChord(Key.Q, primary = true).toKeyShortcut(),
                    onClick = appState::exit,
                )
                // Only surfaced while the cascade is fanning out so the keyboard shortcut and
                // menu both vanish back to normal once there's nothing to cancel.
                if (appState.exitCascadeInFlight) {
                    Item(
                        text = stringResource(Res.string.desktop_menu_cancel_quit),
                        shortcut = KeyChord(Key.Period, primary = true).toKeyShortcut(),
                        onClick = appState::cancelExit,
                    )
                }
            }
            Menu(text = stringResource(Res.string.desktop_menu_go), mnemonic = 'G') {
                ShortcutItems(listOf(AppKeyboardShortcut.Search, AppKeyboardShortcut.Settings), shortcutRequests::request)
            }
        }
        MainWindowContent(
            onOpenEditor = onOpenEditor,
            shortcutRequests = shortcutRequests,
        )
    }
}

/**
 * Must be called from inside a window.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun MainWindowContent(
    onOpenEditor: () -> Unit,
    shortcutRequests: AppShortcutRequests,
    viewModel: AppViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val loadedState = (uiState as? GlobalAppUiLoadedState) ?: GlobalAppUiLoadedState()

    LogDateNavDisplay(
        appUiState = loadedState,
        onShowUnlockPrompt = viewModel::showNativeUnlockPrompt,
        shortcutRequests = shortcutRequests,
        onAutomaticUnlockPrompt = viewModel::showAutomaticUnlockPrompt,
    )
}

@Composable
internal fun rememberMainWindowState(appState: LogDateApplicationState): MainWindowState =
    remember {
        MainWindowState(appState = appState)
    }

class MainWindowState(
    private val appState: LogDateApplicationState,
) : LogDateWindowState {
    override fun exit(): Boolean = true
}
