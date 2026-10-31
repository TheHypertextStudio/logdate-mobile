@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.journals.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import app.logdate.ui.workspace.WorkspacePanel

@Composable
fun JournalListPanel(
    journals: List<JournalListItemUiState>,
    layoutMode: JournalLayoutMode,
    sortOption: JournalSortOption,
    onOpenJournal: JournalClickCallback,
    onBrowseJournals: () -> Unit,
    onCreateJournal: () -> Unit,
    onToggleLayoutMode: () -> Unit,
    onSortOptionSelected: (JournalSortOption) -> Unit,
    modifier: Modifier = Modifier,
    showLoading: Boolean = false,
) {
    BoxWithConstraints(modifier = modifier) {
        WorkspacePanel(Modifier.fillMaxSize()) {
            JournalListPlaceholder(isVisible = showLoading)
            AnimatedVisibility(
                visible = showLoading.not(),
                enter = fadeIn(animationSpec = tween(200)),
                exit = fadeOut(animationSpec = tween(200)),
            ) {
                if (journals.isEmpty()) {
                    NoJournalsScreen(onCreateJournal = onCreateJournal)
                } else {
                    JournalListContent(
                        journals = journals,
                        layoutMode = layoutMode,
                        sortOption = sortOption,
                        onOpenJournal = onOpenJournal,
                        onBrowseJournals = onBrowseJournals,
                        onCreateJournal = onCreateJournal,
                        onToggleLayoutMode = onToggleLayoutMode,
                        onSortOptionSelected = onSortOptionSelected,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/**
 * Main content column: filter bar pinned at top, then the active layout mode below.
 */
@Composable
private fun JournalListContent(
    journals: List<JournalListItemUiState>,
    layoutMode: JournalLayoutMode,
    sortOption: JournalSortOption,
    onOpenJournal: JournalClickCallback,
    onBrowseJournals: () -> Unit,
    onCreateJournal: () -> Unit,
    onToggleLayoutMode: () -> Unit,
    onSortOptionSelected: (JournalSortOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
    val isScrolled by remember {
        derivedStateOf {
            layoutMode == JournalLayoutMode.GRID &&
                (gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0)
        }
    }

    Column(modifier = modifier) {
        JournalFilterBar(
            layoutMode = layoutMode,
            sortOption = sortOption,
            onToggleLayoutMode = onToggleLayoutMode,
            onSortOptionSelected = onSortOptionSelected,
            isScrolled = isScrolled,
            modifier = Modifier.fillMaxWidth(),
        )

        AnimatedContent(
            targetState = layoutMode,
            transitionSpec = {
                fadeIn(tween(200)) togetherWith fadeOut(tween(200))
            },
            modifier = Modifier.fillMaxSize(),
            label = "LayoutModeTransition",
        ) { mode ->
            when (mode) {
                JournalLayoutMode.CAROUSEL -> {
                    JournalCarouselContent(
                        journals = journals,
                        onOpenJournal = onOpenJournal,
                        onCreateJournal = onCreateJournal,
                        onBrowseJournals = onBrowseJournals,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                JournalLayoutMode.GRID -> {
                    JournalList(
                        journals = journals,
                        onOpenJournal = onOpenJournal,
                        onCreateJournal = onCreateJournal,
                        gridState = gridState,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}
