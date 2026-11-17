@file:Suppress("ktlint:standard:function-naming")
@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.logdate.feature.core.settings.account.plan.cleanup

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.logdate.client.domain.storage.StoredItem
import app.logdate.client.domain.storage.StoredKind
import app.logdate.feature.core.settings.ui.formatByteSize
import app.logdate.ui.common.SettingsGridScaffold
import app.logdate.ui.platform.LocalLogDateHaptics
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.storage_cleanup_cancel
import logdate.client.feature.core.generated.resources.storage_cleanup_confirm_body
import logdate.client.feature.core.generated.resources.storage_cleanup_confirm_body_unsized
import logdate.client.feature.core.generated.resources.storage_cleanup_delete
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

const val STORAGE_CLEANUP_ROOT_TAG = "storage_cleanup_root"
const val STORAGE_CLEANUP_SUMMARY_TAG = "storage_cleanup_summary"
const val STORAGE_CLEANUP_BAR_TAG = "storage_cleanup_bar"
const val STORAGE_CLEANUP_DELETE_TAG = "storage_cleanup_delete"
const val STORAGE_CLEANUP_CLEAR_TAG = "storage_cleanup_clear"
const val STORAGE_CLEANUP_CONFIRM_TAG = "storage_cleanup_confirm"
const val STORAGE_CLEANUP_RESULT_TAG = "storage_cleanup_result"
const val STORAGE_CLEANUP_EMPTY_TAG = "storage_cleanup_empty"

/** The test tag of the stored thing [id] in the list. */
fun storageCleanupItemTag(id: Uuid): String = "storage_cleanup_item_$id"

/** Everything of one [kind] that is stored, largest first, to choose from and delete. */
@Composable
fun StorageCleanupScreen(
    kind: StoredKind,
    onBack: () -> Unit,
    viewModel: StorageCleanupViewModel = koinViewModel(key = "storage_cleanup_${kind.name}") { parametersOf(kind) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    StorageCleanupContent(
        kind = kind,
        state = state,
        onBack = onBack,
        actions =
            StorageCleanupActions(
                onToggle = viewModel::toggle,
                onClearSelection = viewModel::clearSelection,
                onDelete = viewModel::deleteSelected,
                onResultShown = viewModel::dismissOutcome,
            ),
    )
}

/** What the clean-up page can ask for. */
data class StorageCleanupActions(
    val onToggle: (Uuid) -> Unit = {},
    val onClearSelection: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onResultShown: () -> Unit = {},
)

/**
 * The clean-up page. Photos and videos are a grid of pictures; recordings and written entries are
 * rows, since there is no picture to know them by. Either way a touch chooses a thing, the bar at
 * the bottom adds up what is chosen, and deleting asks once before it happens.
 *
 * [thumbnail] draws the picture for a photo or video.
 */
@Composable
fun StorageCleanupContent(
    kind: StoredKind,
    state: StorageCleanupUiState,
    onBack: () -> Unit,
    actions: StorageCleanupActions = StorageCleanupActions(),
    thumbnail: @Composable (StoredItem, Modifier) -> Unit = { item, modifier -> StoredThumbnail(item, modifier) },
) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    val ready = state as? StorageCleanupUiState.Ready
    val haptics = LocalLogDateHaptics.current
    val isPictures = kind == StoredKind.Photos || kind == StoredKind.Videos

    SettingsGridScaffold(
        title = stringResource(kind.title()),
        onBack = onBack,
        columns = if (isPictures) PictureCells else GridCells.Adaptive(ROW_MIN_WIDTH),
        maxContentWidth = MAX_CONTENT_WIDTH,
        itemSpacing = if (isPictures) Spacing.xs else Spacing.sm,
        modifier = Modifier.testTag(STORAGE_CLEANUP_ROOT_TAG),
        bottomBar = { if (ready != null) CleanupBar(ready, onDelete = { confirming = true }, actions = actions) },
    ) {
        when {
            ready == null -> {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(modifier = Modifier.fillMaxWidth().padding(Spacing.xxxl), contentAlignment = Alignment.Center) {
                        LoadingIndicator()
                    }
                }
            }

            ready.items.isEmpty() -> {
                item(span = { GridItemSpan(maxLineSpan) }) { NothingStored(kind) }
            }

            else -> {
                item(span = { GridItemSpan(maxLineSpan) }, key = "summary") { Summary(kind, ready) }
                items(ready.items, key = { it.id.toString() }) { item ->
                    val onToggle = {
                        haptics.detentTick()
                        actions.onToggle(item.id)
                    }
                    if (isPictures) {
                        PictureTile(
                            item = item,
                            selected = item.id in ready.selected,
                            enabled = !ready.deleting,
                            onToggle = onToggle,
                            thumbnail = thumbnail,
                            modifier = Modifier.animateItem(),
                        )
                    } else {
                        ItemRow(
                            item = item,
                            selected = item.id in ready.selected,
                            enabled = !ready.deleting,
                            onToggle = onToggle,
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }

    if (confirming && ready != null && ready.selected.isNotEmpty()) {
        ConfirmDelete(
            kind = kind,
            count = ready.selected.size,
            bytes = ready.selectedBytes,
            onConfirm = {
                confirming = false
                haptics.confirmDestruction()
                actions.onDelete()
            },
            onDismiss = { confirming = false },
        )
    }
}

/**
 * Columns of pictures: three on a phone, and on a wider page larger pictures as well as more of
 * them.
 */
private object PictureCells : GridCells {
    override fun Density.calculateCrossAxisCellSizes(
        availableSize: Int,
        spacing: Int,
    ): List<Int> {
        val target = (if (availableSize.toDp() >= WIDE_PAGE) WIDE_PICTURE_SIZE else PICTURE_MIN_SIZE).roundToPx()
        val count = ((availableSize + spacing) / (target + spacing)).coerceAtLeast(MIN_PICTURE_COLUMNS)
        val room = availableSize - spacing * (count - 1)
        return List(count) { index -> room / count + if (index < room % count) 1 else 0 }
    }
}

/** How much room this kind takes and how many there are, in plain text above the list. */
@Composable
private fun Summary(
    kind: StoredKind,
    state: StorageCleanupUiState.Ready,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.md)
                .semantics(mergeDescendants = true) { heading() }
                .testTag(STORAGE_CLEANUP_SUMMARY_TAG),
    ) {
        if (state.totalBytes > 0) {
            val arrive = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
            val leave = MaterialTheme.motionScheme.fastSpatialSpec<IntOffset>()
            // The figure counts down as things are deleted.
            AnimatedContent(
                targetState = formatByteSize(state.totalBytes),
                transitionSpec = {
                    (slideInVertically(arrive) { it / 2 } + fadeIn()) togetherWith (slideOutVertically(leave) { -it / 2 } + fadeOut())
                },
            ) { total ->
                Text(total, style = MaterialTheme.typography.displaySmall)
            }
        }
        Text(
            text = pluralStringResource(kind.countLabel(), state.items.size, state.items.size),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs),
        )
    }
}

@Composable
private fun NothingStored(kind: StoredKind) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xxxl).testTag(STORAGE_CLEANUP_EMPTY_TAG),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(EMPTY_MARK_SIZE).clip(kind.shape()).background(colors.surfaceContainerHigh),
        ) {
            Icon(kind.icon(), contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(EMPTY_MARK_ICON))
        }
        Text(stringResource(kind.emptyLabel()), style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun ConfirmDelete(
    kind: StoredKind,
    count: Int,
    bytes: Long,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(kind.confirmTitle(), count, count)) },
        text = {
            Text(
                if (bytes > 0) {
                    pluralStringResource(Res.plurals.storage_cleanup_confirm_body, count, formatByteSize(bytes))
                } else {
                    pluralStringResource(Res.plurals.storage_cleanup_confirm_body_unsized, count)
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag(STORAGE_CLEANUP_CONFIRM_TAG)) {
                Text(stringResource(Res.string.storage_cleanup_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.storage_cleanup_cancel)) } },
    )
}

private val MAX_CONTENT_WIDTH = 840.dp
private val PICTURE_MIN_SIZE = 104.dp
private val WIDE_PICTURE_SIZE = 148.dp
private val WIDE_PAGE = 560.dp
private const val MIN_PICTURE_COLUMNS = 3
private val ROW_MIN_WIDTH = 320.dp
private val EMPTY_MARK_SIZE = 96.dp
private val EMPTY_MARK_ICON = 40.dp
