package app.logdate.feature.postcards.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass.Companion.WIDTH_DP_EXPANDED_LOWER_BOUND
import androidx.window.core.layout.WindowSizeClass.Companion.WIDTH_DP_MEDIUM_LOWER_BOUND
import app.logdate.client.repository.postcards.Postcard
import app.logdate.feature.postcards.model.CanvasElement
import app.logdate.feature.postcards.model.PostcardDocument
import app.logdate.ui.adaptive.FoldableBookLayout
import app.logdate.ui.common.ContextMenuArea
import app.logdate.ui.common.ContextMenuItem
import app.logdate.ui.common.focusableWithRing
import app.logdate.ui.common.rememberMultiSelectState
import app.logdate.ui.common.verticalScrollbar
import coil3.compose.AsyncImage
import io.github.aakira.napier.Napier
import logdate.client.feature.postcards.generated.resources.Res
import logdate.client.feature.postcards.generated.resources.postcards_clear_selection
import logdate.client.feature.postcards.generated.resources.postcards_delete_selected
import logdate.client.feature.postcards.generated.resources.postcards_empty
import logdate.client.feature.postcards.generated.resources.postcards_menu_open
import logdate.client.feature.postcards.generated.resources.postcards_menu_select
import logdate.client.feature.postcards.generated.resources.postcards_new
import logdate.client.feature.postcards.generated.resources.postcards_selection_count
import logdate.client.feature.postcards.generated.resources.postcards_title
import logdate.client.feature.postcards.generated.resources.postcards_untitled
import logdate.client.ui.generated.resources.common_delete
import logdate.client.ui.generated.resources.common_edit
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.floor
import kotlin.uuid.Uuid
import logdate.client.ui.generated.resources.Res as UiRes

/**
 * Grid collection view for the user's Postcards.
 *
 * Lives in Library > Postcards tab. Shows all saved Postcards as cards
 * with a thumbnail of the first photo and the title. Tap opens the viewer;
 * FAB creates a new one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostcardsCollectionScreen(
    viewModel: PostcardsCollectionViewModel = koinViewModel(),
    onOpenPostcard: (Uuid) -> Unit = {},
    onEditPostcard: (Uuid) -> Unit = {},
    onDeletePostcard: (Uuid) -> Unit = {},
    onCreateNew: () -> Unit = {},
) {
    val postcards by viewModel.postcards.collectAsState()
    val windowSizeClass = currentWindowAdaptiveInfoV2().windowSizeClass
    val columnCount =
        when {
            windowSizeClass.isWidthAtLeastBreakpoint(WIDTH_DP_EXPANDED_LOWER_BOUND) -> 4
            windowSizeClass.isWidthAtLeastBreakpoint(WIDTH_DP_MEDIUM_LOWER_BOUND) -> 3
            else -> 2
        }

    val multiSelect = rememberMultiSelectState()
    val allIds = postcards.map { it.id.toString() }

    Scaffold(
        topBar = {
            if (multiSelect.hasSelection) {
                TopAppBar(
                    title = { Text(stringResource(Res.string.postcards_selection_count, multiSelect.selectionCount)) },
                    navigationIcon = {
                        IconButton(onClick = { multiSelect.clear() }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.postcards_clear_selection))
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            multiSelect.selectedIds.forEach { id ->
                                onDeletePostcard(Uuid.parse(id))
                            }
                            multiSelect.clear()
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(Res.string.postcards_delete_selected))
                        }
                    },
                )
            } else {
                TopAppBar(title = { Text(stringResource(Res.string.postcards_title)) })
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCreateNew) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(Res.string.postcards_new))
            }
        },
    ) { paddingValues ->
        if (postcards.isEmpty()) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(Res.string.postcards_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val (startPanePostcards, endPanePostcards) = splitPostcardsForBookPosture(postcards)
            FoldableBookLayout(
                modifier =
                    Modifier
                        .padding(paddingValues)
                        .fillMaxSize(),
                minPaneWidth = MINIMUM_BOOK_PANE_WIDTH,
                startPane = { paneInfo ->
                    PostcardsGridPane(
                        postcards = startPanePostcards,
                        columnCount =
                            postcardGridColumnCountForBookPane(
                                paneWidth = paneInfo.width,
                                requestedColumnCount = columnCount,
                            ),
                        isSelected = { id -> multiSelect.isSelected(id) },
                        hasSelection = multiSelect.hasSelection,
                        onToggleSelection = { id -> multiSelect.toggle(id) },
                        onOpenPostcard = onOpenPostcard,
                        onEditPostcard = onEditPostcard,
                        onDeletePostcard = onDeletePostcard,
                    )
                },
                endPane = { paneInfo ->
                    PostcardsGridPane(
                        postcards = endPanePostcards,
                        columnCount =
                            postcardGridColumnCountForBookPane(
                                paneWidth = paneInfo.width,
                                requestedColumnCount = columnCount,
                            ),
                        isSelected = { id -> multiSelect.isSelected(id) },
                        hasSelection = multiSelect.hasSelection,
                        onToggleSelection = { id -> multiSelect.toggle(id) },
                        onOpenPostcard = onOpenPostcard,
                        onEditPostcard = onEditPostcard,
                        onDeletePostcard = onDeletePostcard,
                    )
                },
                standardContent = {
                    PostcardsGridPane(
                        postcards = postcards,
                        columnCount = columnCount,
                        isSelected = { id -> multiSelect.isSelected(id) },
                        hasSelection = multiSelect.hasSelection,
                        onToggleSelection = { id -> multiSelect.toggle(id) },
                        onOpenPostcard = onOpenPostcard,
                        onEditPostcard = onEditPostcard,
                        onDeletePostcard = onDeletePostcard,
                    )
                },
            )
        }
    }
}

private val thumbnailJson = PostcardDocument.json

@Composable
private fun PostcardsGridPane(
    postcards: List<Postcard>,
    columnCount: Int,
    isSelected: (String) -> Boolean,
    hasSelection: Boolean,
    onToggleSelection: (String) -> Unit,
    onOpenPostcard: (Uuid) -> Unit,
    onEditPostcard: (Uuid) -> Unit,
    onDeletePostcard: (Uuid) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
    val openLabel = stringResource(Res.string.postcards_menu_open)
    val editLabel = stringResource(UiRes.string.common_edit)
    val selectLabel = stringResource(Res.string.postcards_menu_select)
    val deleteLabel = stringResource(UiRes.string.common_delete)
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(columnCount),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier =
            modifier
                .fillMaxSize()
                .verticalScrollbar(gridState),
    ) {
        items(postcards, key = { it.id.toString() }) { postcard ->
            val id = postcard.id.toString()
            ContextMenuArea(
                items =
                    listOf(
                        ContextMenuItem(openLabel) { onOpenPostcard(postcard.id) },
                        ContextMenuItem(editLabel) { onEditPostcard(postcard.id) },
                        ContextMenuItem(selectLabel) { onToggleSelection(id) },
                        ContextMenuItem(deleteLabel) { onDeletePostcard(postcard.id) },
                    ),
            ) {
                PostcardCard(
                    postcard = postcard,
                    isSelected = isSelected(id),
                    onClick = {
                        if (hasSelection) {
                            onToggleSelection(id)
                        } else {
                            onOpenPostcard(postcard.id)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun PostcardCard(
    postcard: Postcard,
    onClick: () -> Unit,
    isSelected: Boolean = false,
) {
    val thumbnailUri =
        remember(postcard.documentJson) {
            try {
                val doc =
                    thumbnailJson.decodeFromString(
                        PostcardDocument.serializer(),
                        postcard.documentJson,
                    )
                doc.elements
                    .filterIsInstance<CanvasElement.Photo>()
                    .firstOrNull()
                    ?.mediaUri
            } catch (e: Exception) {
                Napier.w("Failed to extract thumbnail from postcard", e)
                null
            }
        }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(12.dp))
                .then(
                    if (isSelected) {
                        Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                    } else {
                        Modifier
                    },
                ).focusableWithRing()
                .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
        ) {
            if (thumbnailUri != null) {
                AsyncImage(
                    model = thumbnailUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Text(
                text = postcard.title.ifEmpty { stringResource(Res.string.postcards_untitled) },
                style = MaterialTheme.typography.titleSmall,
                color =
                    if (thumbnailUri != null) {
                        MaterialTheme.colorScheme.inverseOnSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(12.dp),
            )
        }
    }
}

internal fun <T> splitPostcardsForBookPosture(items: List<T>): Pair<List<T>, List<T>> {
    if (items.size <= 1) {
        return items to emptyList()
    }

    val startPaneCount = (items.size + 1) / 2
    return items.take(startPaneCount) to items.drop(startPaneCount)
}

internal fun postcardGridColumnCountForBookPane(
    paneWidth: Dp,
    requestedColumnCount: Int,
): Int {
    val paneColumnCount =
        floor(paneWidth.value / MINIMUM_BOOK_PANE_CARD_WIDTH.value)
            .toInt()
            .coerceAtLeast(MINIMUM_BOOK_PANE_COLUMN_COUNT)
    return paneColumnCount.coerceAtMost(
        requestedColumnCount.coerceAtLeast(MINIMUM_BOOK_PANE_COLUMN_COUNT),
    )
}

private val MINIMUM_BOOK_PANE_WIDTH = 320.dp
private val MINIMUM_BOOK_PANE_CARD_WIDTH = 220.dp
private const val MINIMUM_BOOK_PANE_COLUMN_COUNT = 1
