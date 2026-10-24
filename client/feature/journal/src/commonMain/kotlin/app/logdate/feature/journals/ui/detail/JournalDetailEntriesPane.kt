@file:Suppress("ktlint:standard:function-naming")
@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)

package app.logdate.feature.journals.ui.detail

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.logdate.feature.journals.ui.deriveCoverColor
import app.logdate.ui.LocalNavAnimatedVisibilityScope
import app.logdate.ui.LocalSharedTransitionScope
import app.logdate.ui.common.applyStandardContentWidth
import app.logdate.ui.common.transitions.TransitionKeys
import app.logdate.ui.handwriting.HandwritingPreview
import app.logdate.ui.theme.Spacing
import app.logdate.util.toReadableDateShort
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import logdate.client.feature.journal.generated.resources.Res
import logdate.client.feature.journal.generated.resources.journal_also_in
import logdate.client.feature.journal.generated.resources.journal_empty_hint
import logdate.client.feature.journal.generated.resources.no_entries_in_this_journal_yet
import logdate.client.feature.journal.generated.resources.sort_newest_first
import logdate.client.feature.journal.generated.resources.sort_oldest_first
import logdate.client.feature.journal.generated.resources.tab_gallery
import logdate.client.feature.journal.generated.resources.tab_timeline
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid

@Composable
internal fun JournalDetailEntriesPane(
    uiState: JournalDetailUiState.Success,
    mediaEntries: List<EntryDisplayData>,
    hasMedia: Boolean,
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    onNavigateToNoteDetail: (noteId: Uuid) -> Unit,
    onRemoveNoteFromJournal: (noteId: Uuid) -> Unit,
    constrainContentWidth: Boolean,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    Column(modifier = modifier) {
        if (hasMedia && uiState.entries.isNotEmpty()) {
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { onSelectTab(0) },
                    text = { Text(stringResource(Res.string.tab_timeline)) },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { onSelectTab(1) },
                    text = { Text(stringResource(Res.string.tab_gallery)) },
                )
            }
        }

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .weight(1f),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (selectedTab == 1 && hasMedia) {
                JournalGalleryGrid(
                    mediaEntries = mediaEntries,
                    onOpenEntry = onNavigateToNoteDetail,
                    modifier =
                        Modifier
                            .padding(vertical = Spacing.sm)
                            .journalDetailContentWidth(constrainContentWidth),
                )
            } else {
                JournalTimelinePane(
                    uiState = uiState,
                    listState = listState,
                    onNavigateToNoteDetail = onNavigateToNoteDetail,
                    onRemoveNoteFromJournal = onRemoveNoteFromJournal,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(vertical = Spacing.sm)
                            .journalDetailContentWidth(constrainContentWidth),
                )
            }
        }
    }
}

@Composable
private fun Modifier.journalDetailContentWidth(constrainContentWidth: Boolean): Modifier =
    if (constrainContentWidth) {
        applyStandardContentWidth()
    } else {
        padding(horizontal = Spacing.md)
    }

@Composable
private fun JournalTimelinePane(
    uiState: JournalDetailUiState.Success,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onNavigateToNoteDetail: (noteId: Uuid) -> Unit,
    onRemoveNoteFromJournal: (noteId: Uuid) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        if (uiState.entries.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(Res.string.no_entries_in_this_journal_yet),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    stringResource(Res.string.journal_empty_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val groupedEntries =
                remember(uiState.entries) {
                    groupEntriesByDay(uiState.entries)
                }

            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = Spacing.sm),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text =
                            if (uiState.sortOrder == SortOrder.NEWEST_FIRST) {
                                stringResource(Res.string.sort_newest_first)
                            } else {
                                stringResource(Res.string.sort_oldest_first)
                            },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding =
                        PaddingValues(
                            bottom = Spacing.xl,
                        ),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    groupedEntries.forEach { (dateLabel, entries) ->
                        item(key = "header-$dateLabel") {
                            DaySectionHeader(dateLabel)
                        }
                        entries.forEach { entry ->
                            item(key = "entry-${entry.id}") {
                                JournalEntryItem(
                                    entry = entry,
                                    onClick = { onNavigateToNoteDetail(entry.id) },
                                    onRemoveFromJournal = { onRemoveNoteFromJournal(entry.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Groups a sorted list of entries by their calendar day, preserving order.
 */
private fun groupEntriesByDay(entries: List<EntryDisplayData>): List<Pair<String, List<EntryDisplayData>>> {
    val tz = TimeZone.currentSystemDefault()
    val grouped = linkedMapOf<String, MutableList<EntryDisplayData>>()
    for (entry in entries) {
        val date = entry.timestamp.toLocalDateTime(tz).date
        val label = date.toReadableDateShort()
        grouped.getOrPut(label) { mutableListOf() }.add(entry)
    }
    return grouped.map { (label, items) -> label to items.toList() }
}

@Composable
private fun DaySectionHeader(
    dateLabel: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = dateLabel,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = Spacing.md, bottom = Spacing.xs),
    )
}

// region Entry type composables
@Composable
private fun JournalEntryItem(
    entry: EntryDisplayData,
    onClick: () -> Unit,
    onRemoveFromJournal: () -> Unit,
    onNavigateToJournal: (Uuid) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val noteViewerSharedBoundsModifier =
        rememberNoteViewerSharedBoundsModifier(entry.id)

    Column(modifier = modifier) {
        when (entry) {
            is EntryDisplayData.HandwritingEntry -> {
                VerticalEntryCardShell(
                    timestamp = entry.timestamp,
                    capturedOnWatch = entry.capturedOnWatch,
                    capturedDuring = entry.capturedDuring,
                    onClick = onClick,
                    onRemoveFromJournal = onRemoveFromJournal,
                    cardModifier = noteViewerSharedBoundsModifier,
                ) {
                    HandwritingPreview(
                        entry.document,
                        Modifier.fillMaxWidth().height(240.dp),
                        unreadable = entry.unreadable,
                    )
                }
            }

            is EntryDisplayData.TextEntry -> {
                TextEntryCard(
                    entry = entry,
                    onClick = onClick,
                    onRemoveFromJournal = onRemoveFromJournal,
                    cardModifier = noteViewerSharedBoundsModifier,
                )
            }

            is EntryDisplayData.ImageEntry -> {
                ImageEntryCard(
                    entry = entry,
                    onClick = onClick,
                    onRemoveFromJournal = onRemoveFromJournal,
                    cardModifier = noteViewerSharedBoundsModifier,
                )
            }

            is EntryDisplayData.VideoEntry -> {
                VideoEntryCard(
                    entry = entry,
                    onClick = onClick,
                    onRemoveFromJournal = onRemoveFromJournal,
                    cardModifier = noteViewerSharedBoundsModifier,
                )
            }

            is EntryDisplayData.AudioEntry -> {
                AudioEntryCard(
                    entry = entry,
                    onClick = onClick,
                    onRemoveFromJournal = onRemoveFromJournal,
                    cardModifier = noteViewerSharedBoundsModifier,
                )
            }
        }

        if (entry.otherJournals.isNotEmpty()) {
            JournalMembershipBadges(
                journals = entry.otherJournals,
                onNavigateToJournal = onNavigateToJournal,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
    }
}

@Composable
private fun rememberNoteViewerSharedBoundsModifier(noteId: Uuid): Modifier {
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalNavAnimatedVisibilityScope.current

    return if (sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope) {
            Modifier.sharedBounds(
                rememberSharedContentState(TransitionKeys.noteViewerTransition(noteId)),
                animatedVisibilityScope = animatedVisibilityScope,
                resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
            )
        }
    } else {
        Modifier
    }
}

/**
 * Row of small pills showing the other journals this entry appears in.
 * Each pill shows the journal's derived color and title, and is tappable.
 */
@Composable
private fun JournalMembershipBadges(
    journals: List<JournalReference>,
    onNavigateToJournal: (Uuid) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(
            text = stringResource(Res.string.journal_also_in),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterVertically),
        )
        journals.forEach { journal ->
            val color = remember(journal.id) { deriveCoverColor(journal.id) }
            Surface(
                onClick = { onNavigateToJournal(journal.id) },
                shape = MaterialTheme.shapes.small,
                color = color.copy(alpha = 0.2f),
                modifier = Modifier.heightIn(max = 24.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(8.dp)
                                .background(color, MaterialTheme.shapes.extraSmall),
                    )
                    Text(
                        text = journal.title,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
