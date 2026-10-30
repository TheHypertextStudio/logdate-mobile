@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.journals.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.logdate.ui.common.DropdownMenu
import app.logdate.ui.theme.Spacing
import app.logdate.ui.workspace.LocalWorkspaceEnabled
import app.logdate.ui.workspace.workspaceControlBackdrop
import logdate.client.feature.journal.generated.resources.Res
import logdate.client.feature.journal.generated.resources.cd_switch_to_carousel
import logdate.client.feature.journal.generated.resources.cd_switch_to_grid
import logdate.client.feature.journal.generated.resources.label_sorting_by
import logdate.client.feature.journal.generated.resources.sort_created
import logdate.client.feature.journal.generated.resources.sort_last_updated
import logdate.client.feature.journal.generated.resources.sort_menu_created
import logdate.client.feature.journal.generated.resources.sort_menu_last_updated
import logdate.client.feature.journal.generated.resources.sort_menu_title
import logdate.client.feature.journal.generated.resources.sort_title
import org.jetbrains.compose.resources.stringResource

/**
 * Pinned action bar above the journal list content.
 *
 * Horizontally scrollable row containing a layout mode toggle and a sort dropdown.
 * The toggle button shows the *opposite* mode's icon as an affordance (e.g. grid icon when
 * in carousel mode). The background animates to `surfaceContainerHigh` when content scrolls
 * underneath, providing a visual separation cue. Only grid mode triggers this — carousel
 * content scrolls horizontally and never underlaps the bar.
 */
@Composable
fun JournalFilterBar(
    layoutMode: JournalLayoutMode,
    sortOption: JournalSortOption,
    onToggleLayoutMode: () -> Unit,
    onSortOptionSelected: (JournalSortOption) -> Unit,
    modifier: Modifier = Modifier,
    isScrolled: Boolean = false,
) {
    val backgroundColor by animateColorAsState(
        targetValue =
            if (isScrolled) {
                MaterialTheme.colorScheme.surfaceContainerHigh
            } else {
                MaterialTheme.colorScheme.surface
            },
        animationSpec = tween(200),
        label = "FilterBarBackground",
    )

    Row(
        modifier =
            (if (LocalWorkspaceEnabled.current) modifier.workspaceControlBackdrop(isScrolled) else modifier.background(backgroundColor))
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
                .testTag("JournalFilterBar"),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val layoutToggleDescription =
            when (layoutMode) {
                JournalLayoutMode.CAROUSEL -> stringResource(Res.string.cd_switch_to_grid)
                JournalLayoutMode.GRID -> stringResource(Res.string.cd_switch_to_carousel)
            }

        FilledTonalIconButton(
            onClick = onToggleLayoutMode,
            modifier =
                Modifier
                    .testTag("LayoutModeToggle")
                    .semantics { stateDescription = layoutMode.name.lowercase() },
        ) {
            Icon(
                imageVector =
                    when (layoutMode) {
                        JournalLayoutMode.CAROUSEL -> Icons.Default.GridView
                        JournalLayoutMode.GRID -> Icons.Default.ViewCarousel
                    },
                contentDescription = layoutToggleDescription,
            )
        }

        SortDropdownChip(
            sortOption = sortOption,
            onSortOptionSelected = onSortOptionSelected,
        )
    }
}

/**
 * Chip label for the sort option (lowercase, for inline "Sorting by …" text).
 */
@Composable
private fun sortChipLabel(option: JournalSortOption): String =
    when (option) {
        JournalSortOption.LAST_UPDATED -> stringResource(Res.string.sort_last_updated)
        JournalSortOption.CREATED -> stringResource(Res.string.sort_created)
        JournalSortOption.TITLE -> stringResource(Res.string.sort_title)
    }

/**
 * Menu item label for the sort option (capitalized, for standalone dropdown items).
 */
@Composable
private fun sortMenuLabel(option: JournalSortOption): String =
    when (option) {
        JournalSortOption.LAST_UPDATED -> stringResource(Res.string.sort_menu_last_updated)
        JournalSortOption.CREATED -> stringResource(Res.string.sort_menu_created)
        JournalSortOption.TITLE -> stringResource(Res.string.sort_menu_title)
    }

@Composable
private fun SortDropdownChip(
    sortOption: JournalSortOption,
    onSortOptionSelected: (JournalSortOption) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        AssistChip(
            onClick = { expanded = true },
            label = { Text(stringResource(Res.string.label_sorting_by, sortChipLabel(sortOption))) },
            modifier = Modifier.testTag("SortDropdownChip"),
            trailingIcon = {
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = null,
                )
            },
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            JournalSortOption.entries.forEach { option ->
                val isSelected = option == sortOption
                DropdownMenuItem(
                    text = { Text(text = sortMenuLabel(option)) },
                    onClick = {
                        onSortOptionSelected(option)
                        expanded = false
                    },
                    leadingIcon = {
                        if (isSelected) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                            )
                        } else {
                            Spacer(Modifier.size(24.dp))
                        }
                    },
                )
            }
        }
    }
}
