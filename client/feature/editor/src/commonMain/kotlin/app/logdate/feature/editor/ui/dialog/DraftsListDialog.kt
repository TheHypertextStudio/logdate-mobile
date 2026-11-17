@file:OptIn(ExperimentalMaterial3Api::class)

package app.logdate.feature.editor.ui.dialog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.logdate.client.repository.journals.EntryDraft
import app.logdate.client.repository.journals.JournalNote
import app.logdate.ui.common.SwipeToAction
import app.logdate.ui.notes.NoteThumbnail
import app.logdate.ui.notes.noteWords
import app.logdate.ui.platform.PlatformIcons
import app.logdate.ui.platform.PlatformSheet
import app.logdate.ui.theme.Spacing
import app.logdate.util.toReadableDateTimeShort
import logdate.client.feature.editor.generated.resources.Res
import logdate.client.feature.editor.generated.resources.clear_all
import logdate.client.feature.editor.generated.resources.draft_item_count
import logdate.client.feature.editor.generated.resources.empty_draft
import logdate.client.feature.editor.generated.resources.loading_drafts
import logdate.client.feature.editor.generated.resources.no_drafts_found
import logdate.client.feature.editor.generated.resources.previous_drafts
import logdate.client.feature.editor.generated.resources.start_writing_to_create_your_first_draft
import logdate.client.ui.generated.resources.common_delete
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid
import logdate.client.ui.generated.resources.Res as UiRes

/**
 * Bottom sheet for viewing and managing drafts.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun DraftsBottomSheet(
    drafts: List<EntryDraft>,
    isLoading: Boolean = false,
    onDismiss: () -> Unit,
    onDraftSelected: (EntryDraft) -> Unit,
    onDraftDeleted: (Uuid) -> Unit,
    onDeleteAllDrafts: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    PlatformSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg)
                    .navigationBarsPadding(),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.previous_drafts),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                if (drafts.isNotEmpty() && !isLoading) {
                    TextButton(
                        onClick = onDeleteAllDrafts,
                    ) {
                        Text(
                            text = stringResource(Res.string.clear_all),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(Spacing.md))

            // Content
            when {
                isLoading -> {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(32.dp),
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 3.dp,
                            )
                            Spacer(modifier = Modifier.height(Spacing.md))
                            Text(
                                text = stringResource(Res.string.loading_drafts),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                drafts.isEmpty() -> {
                    DraftsEmptyState(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .testTag("drafts_empty_state"),
                    )
                }

                else -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        items(
                            items = drafts.sortedByDescending { it.updatedAt },
                            key = { it.id },
                        ) { draft ->
                            SwipeToDeleteDraftItem(
                                draft = draft,
                                onDraftSelected = { onDraftSelected(draft) },
                                onDraftDeleted = { onDraftDeleted(draft.id) },
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .testTag("draft_item_${draft.id}"),
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(Spacing.md))
        }
    }
}

/**
 * Empty state displayed when no drafts are available.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun DraftsEmptyState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Schedule,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
            Spacer(modifier = Modifier.height(Spacing.md))
            Text(
                text = stringResource(Res.string.no_drafts_found),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(Spacing.sm))
            Text(
                text = stringResource(Res.string.start_writing_to_create_your_first_draft),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun SwipeToDeleteDraftItem(
    draft: EntryDraft,
    onDraftSelected: () -> Unit,
    onDraftDeleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SwipeToAction(
        onAction = onDraftDeleted,
        actionLabel = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    painter = PlatformIcons.delete(),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(UiRes.string.common_delete),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        },
        content = {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .clickable { onDraftSelected() }
                        .padding(Spacing.md),
            ) {
                DraftListItemContent(draft = draft)
            }
        },
        modifier = modifier.height(80.dp),
        contentShape = MaterialTheme.shapes.extraLarge,
        actionShape = MaterialTheme.shapes.extraLarge,
        spacing = Spacing.sm,
    )
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun DraftListItemContent(
    draft: EntryDraft,
    modifier: Modifier = Modifier,
) {
    // The draft's first piece of media, shown as itself, and its first words. Only a draft with
    // nothing to show (no media and no words) falls back to a label.
    val leadingMedia = draft.notes.firstOrNull { it !is JournalNote.Text }
    val previewText = draft.notes.firstNotNullOfOrNull { it.noteWords() }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leadingMedia?.let { NoteThumbnail(it, size = 48.dp) }
        Column(modifier = Modifier.weight(1f)) {
            if (previewText != null || leadingMedia == null) {
                Text(
                    text = previewText ?: stringResource(Res.string.empty_draft),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = draft.updatedAt.toReadableDateTimeShort(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (draft.notes.size > 1) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(Res.string.draft_item_count, draft.notes.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
