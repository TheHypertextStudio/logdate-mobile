package app.logdate.wear.presentation.memories

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import app.logdate.wear.R
import app.logdate.wear.presentation.recording.formatDuration
import app.logdate.wear.presentation.timeline.formatDayLabel
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.viewmodel.koinViewModel
import kotlin.uuid.Uuid

@Composable
fun WearVoiceMemoriesScreen(
    onOpenMemory: (Uuid) -> Unit,
    viewModel: WearVoiceMemoriesViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    WearVoiceMemoriesContent(state = state, onOpenMemory = onOpenMemory, onLoadMore = viewModel::loadMore)
}

@Composable
internal fun WearVoiceMemoriesContent(
    state: VoiceMemoriesUiState,
    onOpenMemory: (Uuid) -> Unit = {},
    onLoadMore: () -> Unit = {},
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
) {
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    val list: @Composable BoxScope.(PaddingValues) -> Unit = { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item(key = "title") {
                ListHeader(
                    modifier = Modifier.transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                ) {
                    Text(text = stringResource(R.string.wear_memories_title))
                }
            }
            when {
                !state.isLoaded -> {
                    Unit
                }

                state.memories.isEmpty() -> {
                    item(key = "empty") { EmptyMemories(Modifier.transformedHeight(this, spec)) }
                }

                else -> {
                    state.memories.groupBy { it.createdAt.toLocalDateTime(timeZone).date }.forEach { (day, memories) ->
                        item(key = "day-$day") {
                            ListSubHeader(
                                modifier = Modifier.transformedHeight(this, spec),
                                transformation = SurfaceTransformation(spec),
                            ) {
                                Text(text = formatDayLabel(day))
                            }
                        }
                        items(items = memories, key = { it.noteId.toString() }) { memory ->
                            MemoryRow(
                                memory = memory,
                                timeZone = timeZone,
                                onClick = { onOpenMemory(memory.noteId) },
                                modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                                transformation = SurfaceTransformation(spec),
                            )
                        }
                    }
                }
            }
        }
    }
    if (state.hasMore) {
        ScreenScaffold(
            scrollState = listState,
            timeText = { TimeText() },
            edgeButton = {
                EdgeButton(onClick = onLoadMore, enabled = !state.isLoadingMore) {
                    Text(stringResource(R.string.wear_memories_older))
                }
            },
            content = list,
        )
    } else {
        ScreenScaffold(scrollState = listState, timeText = { TimeText() }, content = list)
    }
}

@Composable
private fun EmptyMemories(modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Column(
        modifier = modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.wear_memories_empty),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.wear_memories_empty_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun MemoryRow(
    memory: VoiceMemoryItem,
    timeZone: TimeZone,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    transformation: SurfaceTransformation? = null,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        transformation = transformation,
        icon = { Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null) },
        label = { Text(formatMemoryTime(memory, timeZone)) },
        secondaryLabel = { Text(text = formatDuration(memory.durationMs), style = MaterialTheme.typography.numeralExtraSmall) },
    )
}
