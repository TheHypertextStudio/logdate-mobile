@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.library.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.logdate.feature.library.ui.LibraryGridGroup
import app.logdate.ui.common.MultiSelectState
import app.logdate.ui.common.rememberMultiSelectState
import kotlin.uuid.Uuid

/**
 * A responsive grid of media thumbnails grouped by month.
 *
 * Supports multi-selection via long-press or Ctrl+click when
 * [multiSelectState] has active selections.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun MediaThumbnailGrid(
    groups: List<LibraryGridGroup>,
    onItemClick: (Uuid) -> Unit,
    modifier: Modifier = Modifier,
    multiSelectState: MultiSelectState = rememberMultiSelectState(),
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        MediaThumbnailGridPane(
            groups,
            (maxWidth / 140.dp).toInt().coerceAtLeast(2),
            onItemClick,
            multiSelectState,
            Modifier.fillMaxSize(),
        )
    }
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun MediaThumbnailGridPane(
    groups: List<LibraryGridGroup>,
    columnCount: Int,
    onItemClick: (Uuid) -> Unit,
    multiSelectState: MultiSelectState,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columnCount),
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        groups.forEach { group ->
            item(
                key = "header-${group.label}",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                DateGroupHeader(label = group.label)
            }
            items(
                items = group.items,
                key = { it.uid },
            ) { item ->
                val id = item.uid.toString()
                MediaThumbnailItem(
                    item = item,
                    onItemClick = { uid ->
                        if (multiSelectState.hasSelection) {
                            multiSelectState.toggle(id)
                        } else {
                            onItemClick(uid)
                        }
                    },
                    isSelected = multiSelectState.isSelected(id),
                )
            }
        }
    }
}
