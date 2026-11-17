@file:Suppress("ktlint:standard:function-naming")
@file:OptIn(ExperimentalSharedTransitionApi::class)

package app.logdate.feature.editor.ui.common

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.logdate.feature.editor.ui.layout.LocalEditorIsCompact
import app.logdate.shared.model.Journal
import app.logdate.ui.content.JournalContentCover
import app.logdate.ui.platform.PlatformIcons
import app.logdate.ui.platform.PlatformPredictiveBackHandler
import app.logdate.ui.theme.Spacing
import kotlinx.coroutines.launch
import logdate.client.feature.editor.generated.resources.Res
import logdate.client.feature.editor.generated.resources.expand
import logdate.client.feature.editor.generated.resources.journal_selector_header
import logdate.client.feature.editor.generated.resources.journal_selector_multiple_journals
import logdate.client.feature.editor.generated.resources.journal_selector_no_journals
import logdate.client.feature.editor.generated.resources.journal_selector_select_journals
import logdate.client.feature.editor.generated.resources.journal_selector_selected_count
import logdate.client.feature.editor.generated.resources.journal_selector_unknown_journal
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid

/**
 * A Material You styled selector for associating an entry with one or more journals.
 *
 * The collapsed state is a tappable [Card] rendered by [SelectorContent]. Tapping opens a
 * floating [JournalPickerList] card that rises upward from the same bottom edge via
 * [AnchoredExpandingLayout] — surrounding content is never reflowed because the expanded card
 * is placed at a higher z-layer. Animations use the MD3 Expressive motion curves
 * [EmphasizedDecelerate] and [EmphasizedAccelerate].
 *
 * [PlatformPredictiveBackHandler] tracks the system back gesture in real time, clipping
 * the journal list down via [journalListBackClip] while the "Journals" header stays fixed.
 * Cancelling the gesture snaps the list back with an elastic spring.
 *
 * Renders a compact variant when [LocalEditorIsCompact] is `true` (e.g. landscape phones).
 *
 * @param availableJournals All journals the user can assign the entry to.
 * @param selectedJournalIds IDs of journals currently associated with this entry.
 * @param onSelectionChanged Called with the full updated selection whenever the user toggles a journal.
 * @param expanded Whether the picker is currently open. Callers own this state so they can
 *   collapse the picker in response to external events (e.g. a block being selected).
 * @param onExpandedChange Called when the picker should open or close due to user interaction.
 * @param modifier Modifier applied to the outer [AnchoredExpandingLayout].
 */
@Composable
fun JournalSelectorDropdown(
    availableJournals: List<Journal>,
    selectedJournalIds: List<Uuid>,
    onSelectionChanged: (List<Uuid>) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val backProgress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    PlatformPredictiveBackHandler(
        enabled = expanded,
        onProgress = { progress -> scope.launch { backProgress.snapTo(progress) } },
        onBack = {
            scope.launch { backProgress.snapTo(0f) }
            onExpandedChange(false)
        },
        onCancel = { scope.launch { backProgress.animateTo(0f, spring()) } },
    )

    val collapsedColor =
        if (selectedJournalIds.isNotEmpty()) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }

    NoReflowLayout(modifier = modifier) {
        SharedTransitionLayout {
            AnimatedContent(
                targetState = expanded,
                transitionSpec = { journalSelectorContentTransition() },
                label = "JournalSelectorContent",
            ) { isExpanded ->
                if (isExpanded) {
                    ExpandedSelectorCard(
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = this@AnimatedContent,
                        availableJournals = availableJournals,
                        selectedJournalIds = selectedJournalIds,
                        onSelectionChanged = onSelectionChanged,
                        backProgress = { backProgress.value },
                    )
                } else {
                    CollapsedSelectorCard(
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = this@AnimatedContent,
                        availableJournals = availableJournals,
                        selectedJournalIds = selectedJournalIds,
                        collapsedColor = collapsedColor,
                        onClick = { onExpandedChange(true) },
                    )
                }
            }
        }
    }
}

/**
 * The collapsed state of [JournalSelectorDropdown]: a tappable card showing the current
 * journal selection. The card surface participates in a shared element transition with
 * [ExpandedSelectorCard] via [sharedBounds], and when exactly one journal is selected its
 * title text morphs to the matching row in the expanded list via [sharedElement].
 */
@Composable
private fun CollapsedSelectorCard(
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    availableJournals: List<Journal>,
    selectedJournalIds: List<Uuid>,
    collapsedColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    with(sharedTransitionScope) {
        Card(
            onClick = onClick,
            modifier =
                modifier
                    .fillMaxWidth()
                    .sharedBounds(
                        rememberSharedContentState(SELECTOR_SURFACE_KEY),
                        animatedVisibilityScope = animatedVisibilityScope,
                    ),
            colors = CardDefaults.cardColors(containerColor = collapsedColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            shape = JournalSelectorShape,
        ) {
            SelectorContent(
                selectedCount = selectedJournalIds.size,
                availableJournals = availableJournals,
                selectedJournalIds = selectedJournalIds,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
            )
        }
    }
}

/**
 * The expanded state of [JournalSelectorDropdown]: a floating card with the full journal
 * picker list. Shares a surface key with [CollapsedSelectorCard] so the card morphs
 * seamlessly between states. The selected journal title in each [JournalItem] row
 * participates in a shared element transition with the collapsed trigger text.
 */
@Composable
private fun ExpandedSelectorCard(
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    availableJournals: List<Journal>,
    selectedJournalIds: List<Uuid>,
    onSelectionChanged: (List<Uuid>) -> Unit,
    backProgress: () -> Float,
    modifier: Modifier = Modifier,
) {
    with(sharedTransitionScope) {
        Card(
            modifier =
                modifier
                    .fillMaxWidth()
                    .journalSelectorExpandedHeight()
                    .sharedBounds(
                        rememberSharedContentState(SELECTOR_SURFACE_KEY),
                        animatedVisibilityScope = animatedVisibilityScope,
                    ),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
            shape = JournalSelectorShape,
        ) {
            JournalPickerList(
                availableJournals = availableJournals,
                selectedJournalIds = selectedJournalIds,
                onSelectionChanged = onSelectionChanged,
                backProgress = backProgress,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
            )
        }
    }
}

private const val SELECTOR_SURFACE_KEY = "journal_selector_surface"

/**
 * A layout that always reports the collapsed (smallest) height to its parent, preventing
 * reflow when the [JournalSelectorDropdown] expands. Expanded content overflows upward
 * from the bottom edge on a higher z-layer.
 */
@Composable
private fun NoReflowLayout(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var collapsedHeight by remember { mutableIntStateOf(0) }

    Layout(modifier = modifier, content = content) { measurables, constraints ->
        val placeable = measurables[0].measure(constraints.copy(minHeight = 0))

        // Capture the collapsed height on first measure (or if the content shrinks).
        if (collapsedHeight == 0 || placeable.height < collapsedHeight) {
            collapsedHeight = placeable.height
        }

        val reportedHeight = minOf(placeable.height, collapsedHeight)
        layout(placeable.width, reportedHeight) {
            // Bottom-aligned: expanded content grows upward past the top edge.
            placeable.placeRelative(0, reportedHeight - placeable.height, zIndex = 1f)
        }
    }
}

/**
 * The collapsed trigger row shown inside the [JournalSelectorDropdown] card.
 *
 * Composed of three sections: a circular icon badge, a [JournalSelectionText] label in the
 * middle, and [SelectorIndicators] trailing. Sizes scale down on height-constrained screens
 * via [LocalEditorIsCompact] (e.g. landscape phones).
 */
@Composable
private fun SelectorContent(
    selectedCount: Int,
    availableJournals: List<Journal>,
    selectedJournalIds: List<Uuid>,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    modifier: Modifier = Modifier,
) {
    val isCompact = LocalEditorIsCompact.current
    val contentPadding = if (isCompact) 8.dp else 16.dp
    val iconCircleSize = if (isCompact) 24.dp else 40.dp
    val iconSize = if (isCompact) 14.dp else 20.dp

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(
            modifier =
                Modifier
                    .size(iconCircleSize)
                    .clip(CircleShape)
                    .background(
                        if (selectedCount > 0) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = PlatformIcons.journal(),
                contentDescription = null,
                tint =
                    if (selectedCount > 0) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                modifier = Modifier.size(iconSize),
            )
        }

        Spacer(Modifier.width(12.dp))

        JournalSelectionText(
            selectedCount = selectedCount,
            availableJournals = availableJournals,
            selectedJournalIds = selectedJournalIds,
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
            modifier = Modifier.weight(1f),
        )

        SelectorIndicators(selectedCount = selectedCount)
    }
}

/**
 * Displays the current selection label inside the [SelectorContent] trigger row.
 *
 * Shows the journal title when exactly one is selected, a generic "Multiple journals" label
 * when more than one is selected, and a placeholder when nothing is selected yet. A secondary
 * line showing the count appears for multi-selection on non-compact screens. Typography and
 * layout scale down on height-constrained screens via [LocalEditorIsCompact].
 */
@Composable
private fun JournalSelectionText(
    selectedCount: Int,
    availableJournals: List<Journal>,
    selectedJournalIds: List<Uuid>,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    modifier: Modifier = Modifier,
) {
    val isCompact = LocalEditorIsCompact.current
    val selectedJournal =
        if (selectedCount == 1) {
            availableJournals.find { it.id == selectedJournalIds.first() }
        } else {
            null
        }
    val titleText =
        when (selectedCount) {
            0 -> stringResource(Res.string.journal_selector_select_journals)
            1 -> selectedJournal?.title ?: stringResource(Res.string.journal_selector_unknown_journal)
            else -> stringResource(Res.string.journal_selector_multiple_journals)
        }
    // When exactly one journal is selected, the title participates in a shared element
    // transition with the matching JournalItem row in the expanded list.
    val titleModifier =
        if (selectedJournal != null) {
            with(sharedTransitionScope) {
                Modifier.sharedElement(
                    rememberSharedContentState("journal_title_${selectedJournal.id}"),
                    animatedVisibilityScope = animatedVisibilityScope,
                )
            }
        } else {
            Modifier
        }

    Column(modifier = modifier) {
        Text(
            text = titleText,
            modifier = titleModifier,
            style =
                if (isCompact) {
                    MaterialTheme.typography.bodyMedium
                } else {
                    MaterialTheme.typography.bodyLarge
                },
            fontWeight = if (selectedCount > 0) FontWeight.Medium else FontWeight.Normal,
            color =
                if (selectedCount > 0) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        if (selectedCount > 1 && !isCompact) {
            Text(
                text = stringResource(Res.string.journal_selector_selected_count, selectedCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
            )
        }
    }
}

/**
 * Trailing section of the [SelectorContent] trigger row.
 *
 * Shows a numeric [Badge] when one or more journals are selected, followed by an expand
 * chevron. Both the badge and chevron tint flip between `primary`/`onPrimaryContainer`
 * (selected) and `onSurfaceVariant` (empty) to mirror the card's container color.
 */
@Composable
private fun SelectorIndicators(selectedCount: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (selectedCount > 0) {
            Badge(
                modifier = Modifier.padding(end = 8.dp),
                containerColor = MaterialTheme.colorScheme.primary,
            ) {
                Text(
                    text = selectedCount.toString(),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        Icon(
            painter = PlatformIcons.expandMore(),
            contentDescription = stringResource(Res.string.expand),
            tint =
                if (selectedCount > 0) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
    }
}

/**
 * The scrollable journal list shown inside the expanded card of [JournalSelectorDropdown].
 *
 * Height is capped by [journalSelectorExpandedHeight] so at most four items are visible before
 * the list scrolls — the cut-off indicates more items exist. When [availableJournals] is empty,
 * an [EmptyJournalItem] placeholder is shown instead of the list. Each populated row is a
 * [JournalItem] keyed by journal ID for stable recomposition.
 */
@Composable
private fun JournalPickerList(
    availableJournals: List<Journal>,
    selectedJournalIds: List<Uuid>,
    onSelectionChanged: (List<Uuid>) -> Unit,
    backProgress: () -> Float = { 0f },
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // Header stays fixed during predictive back — only the list below clips.
        Text(
            text = stringResource(Res.string.journal_selector_header),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        )
        LazyColumn(modifier = Modifier.journalListBackClip(backProgress)) {
            if (availableJournals.isEmpty()) {
                item { EmptyJournalItem() }
            } else {
                items(availableJournals, key = { it.id.toString() }) { journal ->
                    val isSelected = selectedJournalIds.contains(journal.id)
                    JournalItem(
                        journal = journal,
                        isSelected = isSelected,
                        onClick = {
                            val newSelection = selectedJournalIds.toMutableList()
                            if (isSelected) {
                                newSelection.remove(journal.id)
                            } else {
                                newSelection.add(journal.id)
                            }
                            onSelectionChanged(newSelection)
                        },
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                    )
                }
            }
        }
    }
}

/**
 * Placeholder row shown inside [JournalPickerList] when [availableJournals] is empty.
 *
 * Uses the same [ListItem] structure as [JournalItem] so the picker card maintains a
 * consistent minimum height rather than collapsing to just its header.
 */
@Composable
private fun EmptyJournalItem() {
    ListItem(
        headlineContent = {
            Text(
                text = stringResource(Res.string.journal_selector_no_journals),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/**
 * A single selectable row in [JournalPickerList] representing one journal.
 *
 * Leading content is a [JournalContentCover] — the small spine-shaped cover used consistently
 * throughout the app to represent journals. When [isSelected] is `true`, the row background
 * transitions to `primaryContainer` and the text color to `onPrimaryContainer` via
 * `animateColorAsState`, keeping both in sync. A trailing checkmark animates in and out using
 * [journalItemCheckmarkEnterTransition] and [journalItemCheckmarkExitTransition].
 *
 * The caller ([JournalPickerList]) owns selection state and passes the updated list to
 * [JournalSelectorDropdown]'s `onSelectionChanged` callback.
 */
@Composable
private fun JournalItem(
    journal: Journal,
    isSelected: Boolean,
    onClick: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    val containerColor by animateColorAsState(
        targetValue =
            if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                Color.Transparent
            },
        label = "JournalItemBackground",
    )
    val textColor by animateColorAsState(
        targetValue =
            if (isSelected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        label = "JournalItemText",
    )
    // When this journal is selected, its title participates in a shared element transition
    // with the collapsed trigger's JournalSelectionText.
    val titleModifier =
        if (isSelected) {
            with(sharedTransitionScope) {
                Modifier.sharedElement(
                    rememberSharedContentState("journal_title_${journal.id}"),
                    animatedVisibilityScope = animatedVisibilityScope,
                )
            }
        } else {
            Modifier
        }
    ListItem(
        headlineContent = {
            Text(
                text = journal.title,
                modifier = titleModifier,
                fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = { JournalContentCover() },
        trailingContent = {
            AnimatedVisibility(
                visible = isSelected,
                enter = journalItemCheckmarkEnterTransition(),
                exit = journalItemCheckmarkExitTransition(),
            ) {
                Icon(
                    painter = PlatformIcons.check(),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = containerColor),
        modifier =
            Modifier
                .padding(horizontal = Spacing.xs, vertical = 2.dp)
                .clip(MaterialTheme.shapes.medium)
                .clickable(onClick = onClick),
    )
}
