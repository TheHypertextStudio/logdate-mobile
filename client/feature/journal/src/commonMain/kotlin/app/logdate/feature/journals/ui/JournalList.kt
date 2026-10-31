@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.journals.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import app.logdate.shared.model.Journal
import app.logdate.ui.common.ContextMenuItem
import app.logdate.ui.common.LocalEditorWindows
import app.logdate.ui.theme.Spacing
import logdate.client.feature.journal.generated.resources.Res
import logdate.client.feature.journal.generated.resources.journal_menu_new_entry_window
import logdate.client.feature.journal.generated.resources.journal_menu_open
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Grid layout for journals using adaptive columns sized to the panel width.
 *
 * The [gridState] is hoisted so the parent can observe scroll position — used by the
 * [JournalFilterBar] to raise its backdrop when content scrolls underneath.
 */
@Composable
fun JournalList(
    journals: List<JournalListItemUiState>,
    onOpenJournal: JournalClickCallback,
    onCreateJournal: () -> Unit,
    modifier: Modifier = Modifier,
    gridState: LazyGridState = rememberLazyGridState(),
) {
    BoxWithConstraints(modifier) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = journalGridMinimumCoverWidth(maxWidth)),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
            contentPadding = PaddingValues(Spacing.lg),
        ) {
            items(journals) { item ->
                when (item) {
                    is JournalListItemUiState.ExistingJournal -> {
                        JournalCover(
                            item.data,
                            onClick = onOpenJournal,
                            contextMenuItems = journalContextMenuItems(item.data, onOpenJournal),
                        )
                    }

                    is JournalListItemUiState.CreateJournalPlaceholder -> {
                        CreateJournalPlaceholder(
                            onClick = onCreateJournal,
                        )
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun JournalListPreview() {
    val journals =
        List(10) {
            Journal(
                id = Uuid.random(),
                title = "Journal $it",
                created = Clock.System.now(),
                description = "Journal $it description",
                isFavorited = false,
                lastUpdated = Clock.System.now(),
            )
        }.map { JournalListItemUiState.ExistingJournal(it) }
    JournalList(journals, onOpenJournal = {}, onCreateJournal = {})
}

/** Right-click and long-press actions for a journal cover, wherever covers are shown. */
@Composable
internal fun journalContextMenuItems(
    journal: Journal,
    onOpenJournal: JournalClickCallback,
): List<ContextMenuItem> {
    val windows = LocalEditorWindows.current
    val open = stringResource(Res.string.journal_menu_open)
    val newWindow = stringResource(Res.string.journal_menu_new_entry_window)
    return buildList {
        add(ContextMenuItem(open) { onOpenJournal(journal.id) })
        if (windows != null) add(ContextMenuItem(newWindow) { windows.openNewEntry(journal.id.toString()) })
    }
}
