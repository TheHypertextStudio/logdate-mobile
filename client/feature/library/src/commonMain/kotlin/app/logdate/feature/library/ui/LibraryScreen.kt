@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.library.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.logdate.ui.theme.Spacing
import app.logdate.ui.workspace.LocalWorkspaceHosted
import app.logdate.ui.workspace.LocalWorkspaceSearchAction
import app.logdate.ui.workspace.PanelContainment
import app.logdate.ui.workspace.WorkspacePanel
import app.logdate.ui.workspace.WorkspaceSearchActionScope
import logdate.client.feature.library.generated.resources.Res
import logdate.client.feature.library.generated.resources.library_postcards
import logdate.client.feature.library.generated.resources.search_library
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import kotlin.uuid.Uuid

/**
 * Stateful Library screen that injects the ViewModel and lists media in the panel it is given.
 */
@Composable
fun LibraryScreen(
    onOpenMediaDetail: (Uuid) -> Unit,
    onOpenSearch: () -> Unit = {},
    onOpenPostcards: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = koinViewModel(),
    onAddPhotos: (() -> Unit)? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LibraryScreenContent(state, onOpenMediaDetail, onOpenSearch, onOpenPostcards, viewModel::retry, modifier, onAddPhotos)
}

/**
 * Stateless library overview layout.
 *
 * Contributes the library search action to the shared workspace header and lists media in a
 * collection panel. Follows the same layout strategy as the journals overview screen.
 */
@Composable
fun LibraryScreenContent(
    state: LibraryUiState,
    onItemClick: (Uuid) -> Unit,
    onOpenSearch: () -> Unit = {},
    onOpenPostcards: (() -> Unit)? = null,
    onRetry: () -> Unit = {},
    modifier: Modifier = Modifier,
    onAddPhotos: (() -> Unit)? = null,
) {
    val openSearch = LocalWorkspaceSearchAction.current ?: onOpenSearch
    if (!LocalWorkspaceHosted.current) {
        WorkspaceSearchActionScope(stringResource(Res.string.search_library), openSearch)
    }
    WorkspacePanel(modifier, containment = PanelContainment.Collection) {
        Column(Modifier.fillMaxSize()) {
            if (onOpenPostcards != null) {
                AssistChip(
                    onClick = onOpenPostcards,
                    label = { Text(stringResource(Res.string.library_postcards)) },
                    modifier = Modifier.padding(horizontal = Spacing.lg),
                )
            }
            LibraryPanel(state, onItemClick, onRetry, Modifier.weight(1f).fillMaxWidth(), onAddPhotos)
        }
    }
}
