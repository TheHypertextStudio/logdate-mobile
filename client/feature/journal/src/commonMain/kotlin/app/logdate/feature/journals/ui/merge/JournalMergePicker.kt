@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.journals.ui.merge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.logdate.feature.journals.ui.JournalCover
import app.logdate.ui.theme.Spacing
import logdate.client.feature.journal.generated.resources.Res
import logdate.client.feature.journal.generated.resources.journal_merge_clear_search
import logdate.client.feature.journal.generated.resources.journal_merge_empty
import logdate.client.feature.journal.generated.resources.journal_merge_item_count
import logdate.client.feature.journal.generated.resources.journal_merge_loading
import logdate.client.feature.journal.generated.resources.journal_merge_no_matches
import logdate.client.feature.journal.generated.resources.journal_merge_picker_source_subtitle
import logdate.client.feature.journal.generated.resources.journal_merge_picker_source_title
import logdate.client.feature.journal.generated.resources.journal_merge_picker_subtitle
import logdate.client.feature.journal.generated.resources.journal_merge_picker_title
import logdate.client.feature.journal.generated.resources.journal_merge_recovery
import logdate.client.feature.journal.generated.resources.journal_merge_search
import logdate.client.feature.journal.generated.resources.journal_merge_source_empty
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid

@Composable
internal fun JournalMergeSourceContext(
    state: JournalMergeUiState,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.testTag("journal_merge_source")) {
        val compact = maxWidth < 320.dp || LocalDensity.current.fontScale >= 1.5f
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(
                if (state.pickerTarget ==
                    JournalMergePickerTarget.Source
                ) {
                    stringResource(Res.string.journal_merge_picker_source_title)
                } else {
                    stringResource(Res.string.journal_merge_picker_title, state.sourceTitle)
                },
                style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                if (state.pickerTarget ==
                    JournalMergePickerTarget.Source
                ) {
                    stringResource(Res.string.journal_merge_picker_source_subtitle, state.destinationTitle)
                } else {
                    stringResource(Res.string.journal_merge_picker_subtitle)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun JournalMergeDestinationPicker(
    state: JournalMergeUiState,
    onQueryChange: (String) -> Unit,
    onChoose: (Uuid) -> Unit,
    modifier: Modifier = Modifier,
    showSource: Boolean = false,
    listState: LazyGridState = rememberLazyGridState(),
) {
    Surface(
        modifier.fillMaxSize().padding(horizontal = Spacing.lg).padding(bottom = Spacing.lg),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val minimumCoverWidth = (if (maxWidth >= 760.dp) 208.dp else 156.dp) * LocalDensity.current.fontScale.coerceIn(1f, 1.5f)
            val availableWidth = maxWidth - Spacing.lg * 2
            val singleColumn = availableWidth < minimumCoverWidth * 2 + Spacing.lg
            val coverWidth = if (singleColumn) minimumCoverWidth.coerceAtMost(256.dp) else 256.dp
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minimumCoverWidth),
                modifier = Modifier.fillMaxSize().testTag("journal_merge_picker"),
                state = listState,
                contentPadding = PaddingValues(start = Spacing.lg, top = 80.dp, end = Spacing.lg, bottom = Spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                if (showSource) {
                    item(key = "source", span = { GridItemSpan(maxLineSpan) }) {
                        JournalMergeSourceContext(state)
                    }
                }
                item(key = "search", span = { GridItemSpan(maxLineSpan) }) {
                    TextField(
                        value = state.query,
                        onValueChange = onQueryChange,
                        label = { Text(stringResource(Res.string.journal_merge_search)) },
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        trailingIcon = {
                            if (state.query.isNotEmpty()) {
                                IconButton(onClick = { onQueryChange("") }) {
                                    Icon(Icons.Rounded.Close, stringResource(Res.string.journal_merge_clear_search))
                                }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("journal_merge_search"),
                        shape = MaterialTheme.shapes.medium,
                    )
                }
                if (state.recovery) {
                    item(key = "recovery", span = { GridItemSpan(maxLineSpan) }) {
                        JournalMergeNotice(stringResource(Res.string.journal_merge_recovery))
                    }
                }
                state.error?.let { error ->
                    item(key = "error", span = { GridItemSpan(maxLineSpan) }) { JournalMergeFeedback(error) }
                }
                when {
                    state.loading -> {
                        item(
                            span = { GridItemSpan(maxLineSpan) },
                        ) { JournalMergeNotice(stringResource(Res.string.journal_merge_loading)) }
                    }

                    state.candidates.isEmpty() -> {
                        item(span = {
                            GridItemSpan(maxLineSpan)
                        }) {
                            JournalMergeNotice(
                                stringResource(
                                    if (state.pickerTarget ==
                                        JournalMergePickerTarget.Source
                                    ) {
                                        Res.string.journal_merge_source_empty
                                    } else {
                                        Res.string.journal_merge_empty
                                    },
                                ),
                            )
                        }
                    }

                    state.visibleCandidates.isEmpty() -> {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Column {
                                Text(stringResource(Res.string.journal_merge_no_matches))
                                TextButton(onClick = { onQueryChange("") }) { Text(stringResource(Res.string.journal_merge_clear_search)) }
                            }
                        }
                    }

                    else -> {
                        items(state.visibleCandidates, key = { it.journal.id }) { candidate ->
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                                Column(
                                    Modifier.widthIn(max = coverWidth).fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                                ) {
                                    JournalCover(candidate.journal, Modifier.fillMaxWidth(), onClick = onChoose, fitContent = true)
                                    Text(
                                        pluralStringResource(
                                            Res.plurals.journal_merge_item_count,
                                            candidate.itemCount,
                                            candidate.itemCount,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
