@file:Suppress("ktlint:standard:function-naming")
@file:OptIn(ExperimentalMaterial3Api::class)

package app.logdate.feature.journals.ui.picker

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.logdate.ui.theme.Spacing
import app.logdate.ui.workspace.LocalWorkspaceEnabled
import app.logdate.ui.workspace.PanelHeader
import app.logdate.ui.workspace.WorkspacePanel
import app.logdate.ui.workspace.WorkspaceSearchField
import app.logdate.ui.workspace.WorkspaceSearchScope
import app.logdate.util.formatDateLocalized
import coil3.compose.AsyncImage
import logdate.client.feature.journal.generated.resources.Res
import logdate.client.feature.journal.generated.resources.cd_back_to_journal
import logdate.client.feature.journal.generated.resources.cd_content_picker_remove_selection
import logdate.client.feature.journal.generated.resources.cd_search_existing_content
import logdate.client.feature.journal.generated.resources.content_picker_add_count
import logdate.client.feature.journal.generated.resources.content_picker_adding
import logdate.client.feature.journal.generated.resources.content_picker_empty
import logdate.client.feature.journal.generated.resources.content_picker_item_description
import logdate.client.feature.journal.generated.resources.content_picker_kind_photo
import logdate.client.feature.journal.generated.resources.content_picker_kind_recording
import logdate.client.feature.journal.generated.resources.content_picker_kind_video
import logdate.client.feature.journal.generated.resources.content_picker_kind_writing
import logdate.client.feature.journal.generated.resources.content_picker_no_matches
import logdate.client.feature.journal.generated.resources.content_picker_not_selected
import logdate.client.feature.journal.generated.resources.content_picker_search_hint
import logdate.client.feature.journal.generated.resources.content_picker_selected
import logdate.client.feature.journal.generated.resources.content_picker_selected_count
import logdate.client.feature.journal.generated.resources.content_picker_subtitle
import logdate.client.feature.journal.generated.resources.content_picker_title
import logdate.client.feature.journal.generated.resources.content_picker_title_for_journal
import logdate.client.ui.generated.resources.common_retry
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import kotlin.uuid.Uuid
import androidx.compose.foundation.lazy.items as lazyItems
import logdate.client.ui.generated.resources.Res as UiRes

@Composable
fun JournalContentPickerScreen(
    journalId: Uuid,
    onBack: () -> Unit,
    onAdded: () -> Unit = onBack,
    modifier: Modifier = Modifier,
    viewModel: JournalContentPickerViewModel = koinViewModel(),
) {
    LaunchedEffect(journalId) { viewModel.setJournalId(journalId) }
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(state.addedCount) {
        if (state.addedCount != null) onAdded()
    }
    JournalContentPickerScreenContent(
        state = state,
        onBack = onBack,
        onQueryChange = viewModel::updateSearchQuery,
        onToggleSelection = viewModel::toggleSelection,
        onRemoveSelection = viewModel::removeSelection,
        onAddSelected = viewModel::addSelected,
        modifier = modifier,
    )
}

@Composable
fun JournalContentPickerScreenContent(
    state: JournalContentPickerUiState,
    onBack: () -> Unit = {},
    onQueryChange: (String) -> Unit = {},
    onToggleSelection: (Uuid) -> Unit = {},
    onRemoveSelection: (Uuid) -> Unit = {},
    onAddSelected: () -> Unit = {},
    previewMedia: Map<Uuid, Painter> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    val workspaceEnabled = LocalWorkspaceEnabled.current
    val pickerTitle =
        if (state.journalTitle.isBlank()) {
            stringResource(Res.string.content_picker_title)
        } else {
            stringResource(Res.string.content_picker_title_for_journal, state.journalTitle)
        }
    if (workspaceEnabled) {
        WorkspaceSearchScope(
            query = state.query,
            hint = stringResource(Res.string.content_picker_search_hint),
            onQuery = onQueryChange,
        )
    }

    val body: @Composable (PaddingValues) -> Unit = { padding ->
        WorkspacePanel(
            modifier = modifier.padding(padding),
        ) {
            Column(Modifier.fillMaxSize()) {
                if (workspaceEnabled) {
                    PanelHeader(
                        title = pickerTitle,
                        subtitle = stringResource(Res.string.content_picker_subtitle),
                        actions = {
                            IconButton(onClick = onBack) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.ArrowBack,
                                    contentDescription = stringResource(Res.string.cd_back_to_journal),
                                )
                            }
                        },
                    )
                }
                if (!workspaceEnabled) {
                    val searchDescription = stringResource(Res.string.cd_search_existing_content)
                    Box(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg)) {
                        WorkspaceSearchField(
                            query = state.query,
                            hint = stringResource(Res.string.content_picker_search_hint),
                            onQueryChange = onQueryChange,
                            modifier = Modifier.semantics { contentDescription = searchDescription },
                        )
                    }
                }
                PickerGallery(
                    state = state,
                    onToggleSelection = onToggleSelection,
                    previewMedia = previewMedia,
                    modifier = Modifier.weight(1f),
                )
                SelectionReview(
                    selectedItems = state.selectedItems,
                    isAdding = state.isAdding,
                    error = state.addError,
                    onRemoveSelection = onRemoveSelection,
                    onAddSelected = onAddSelected,
                )
            }
        }
    }

    if (workspaceEnabled) {
        body(PaddingValues())
    } else {
        Scaffold(
            modifier = modifier,
            topBar = {
                TopAppBar(
                    title = { Text(pickerTitle) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(Res.string.cd_back_to_journal))
                        }
                    },
                )
            },
            content = body,
        )
    }
}

@Composable
private fun PickerGallery(
    state: JournalContentPickerUiState,
    onToggleSelection: (Uuid) -> Unit,
    previewMedia: Map<Uuid, Painter>,
    modifier: Modifier = Modifier,
) {
    val selectedIds = state.selectedItems.mapTo(mutableSetOf()) { it.id }
    if (state.groups.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                if (state.query.isBlank()) {
                    stringResource(Res.string.content_picker_empty)
                } else {
                    stringResource(Res.string.content_picker_no_matches)
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val gridColumns = contentPickerGridColumnCount(maxWidth, LocalDensity.current.fontScale)
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(gridColumns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalItemSpacing = Spacing.md,
        ) {
            state.groups.forEach { group ->
                item(span = StaggeredGridItemSpan.FullLine) {
                    Text(
                        text = formatDateLocalized(group.date),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = Spacing.lg, bottom = Spacing.xs),
                    )
                }
                items(
                    items = group.items,
                    key = { it.id },
                ) { item ->
                    PickerItemTile(
                        item = item,
                        selected = item.id in selectedIds,
                        previewMedia = previewMedia[item.id],
                        onClick = { onToggleSelection(item.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PickerItemTile(
    item: JournalContentPickerItem,
    selected: Boolean,
    previewMedia: Painter?,
    onClick: () -> Unit,
) {
    val typeLabel = item.kind.label
    val title = item.displayTitle
    val selectionLabel =
        if (selected) {
            stringResource(Res.string.content_picker_selected)
        } else {
            stringResource(Res.string.content_picker_not_selected)
        }
    val itemDescription = stringResource(Res.string.content_picker_item_description, typeLabel, title, selectionLabel)
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics {
                    role = Role.Checkbox
                    this.selected = selected
                    contentDescription = itemDescription
                }.clickable(onClick = onClick),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (selected && !item.isVisualMedia) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
                contentColor =
                    if (selected && !item.isVisualMedia) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
            ),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        if (item.isVisualMedia) {
            Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f)) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Column(
                        modifier = Modifier.padding(Spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        Icon(
                            if (item.kind == JournalContentPickerItemKind.VIDEO) {
                                Icons.Rounded.PlayCircle
                            } else {
                                Icons.Rounded.Image
                            },
                            contentDescription = null,
                            modifier = Modifier.size(36.dp),
                        )
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                when {
                    previewMedia != null -> {
                        Image(
                            painter = previewMedia,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }

                    item.mediaRef != null -> {
                        AsyncImage(
                            model = item.mediaRef,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
                if (item.kind == JournalContentPickerItemKind.VIDEO) {
                    Icon(
                        Icons.Rounded.PlayCircle,
                        contentDescription = null,
                        modifier = Modifier.align(Alignment.Center).size(28.dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                SelectionMarker(
                    selected = selected,
                    modifier = Modifier.align(Alignment.TopEnd).padding(Spacing.sm),
                )
            }
        } else if (item.kind == JournalContentPickerItemKind.RECORDING) {
            Box(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(end = 36.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    Icon(
                        Icons.Rounded.GraphicEq,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                SelectionMarker(selected, Modifier.align(Alignment.TopEnd))
            }
        } else {
            Box(Modifier.fillMaxWidth().padding(Spacing.lg)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 36.dp).widthIn(max = 720.dp),
                )
                SelectionMarker(selected, Modifier.align(Alignment.TopEnd))
            }
        }
    }
}

internal fun contentPickerGridColumnCount(
    availableWidth: Dp,
    fontScale: Float,
): Int {
    if (fontScale >= 1.5f) return 1
    val usableWidth = (availableWidth - Spacing.lg * 2).coerceAtLeast(0.dp)
    return (usableWidth / 320.dp).toInt().coerceIn(1, 3)
}

@Composable
private fun SelectionMarker(
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    if (selected) {
        Surface(
            modifier = modifier,
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.primary,
        ) {
            Icon(
                Icons.Rounded.CheckCircle,
                contentDescription = null,
                modifier = Modifier.padding(4.dp).size(20.dp),
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

private val JournalContentPickerItem.isVisualMedia: Boolean
    get() = kind == JournalContentPickerItemKind.PHOTO || kind == JournalContentPickerItemKind.VIDEO

private val JournalContentPickerItem.displayTitle: String
    @Composable
    get() = title ?: kind.label

private val JournalContentPickerItemKind.label: String
    @Composable
    get() =
        when (this) {
            JournalContentPickerItemKind.WRITING -> stringResource(Res.string.content_picker_kind_writing)
            JournalContentPickerItemKind.PHOTO -> stringResource(Res.string.content_picker_kind_photo)
            JournalContentPickerItemKind.VIDEO -> stringResource(Res.string.content_picker_kind_video)
            JournalContentPickerItemKind.RECORDING -> stringResource(Res.string.content_picker_kind_recording)
        }

@Composable
private fun SelectionReview(
    selectedItems: List<JournalContentPickerItem>,
    isAdding: Boolean,
    error: StringResource?,
    onRemoveSelection: (Uuid) -> Unit,
    onAddSelected: () -> Unit,
) {
    if (selectedItems.isEmpty() && error == null) return
    Surface(tonalElevation = 3.dp) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth().padding(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                if (selectedItems.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        lazyItems(selectedItems, key = { it.id }) { item ->
                            val itemTitle = item.displayTitle
                            Card(Modifier.widthIn(max = 168.dp)) {
                                Row(
                                    modifier = Modifier.padding(start = Spacing.sm),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(itemTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    IconButton(onClick = { onRemoveSelection(item.id) }) {
                                        Icon(
                                            Icons.Rounded.Close,
                                            contentDescription = stringResource(Res.string.cd_content_picker_remove_selection, itemTitle),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                error?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(it), Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onAddSelected) { Text(stringResource(UiRes.string.common_retry)) }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End),
                ) {
                    Text(
                        text = stringResource(Res.string.content_picker_selected_count, selectedItems.size),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = onAddSelected, enabled = selectedItems.isNotEmpty() && !isAdding) {
                        Text(
                            if (isAdding) {
                                stringResource(Res.string.content_picker_adding)
                            } else {
                                stringResource(Res.string.content_picker_add_count, selectedItems.size)
                            },
                        )
                    }
                }
            }
        }
    }
}
