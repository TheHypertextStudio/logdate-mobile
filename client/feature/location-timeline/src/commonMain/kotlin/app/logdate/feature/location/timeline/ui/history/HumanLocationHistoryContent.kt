@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.location.timeline.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.logdate.client.text.asString
import logdate.client.feature.location.timeline.generated.resources.Res
import logdate.client.feature.location.timeline.generated.resources.history_add_visit
import logdate.client.feature.location.timeline.generated.resources.history_details
import logdate.client.feature.location.timeline.generated.resources.history_empty_day
import logdate.client.feature.location.timeline.generated.resources.history_empty_day_body
import logdate.client.feature.location.timeline.generated.resources.history_next_day
import logdate.client.feature.location.timeline.generated.resources.history_next_stop
import logdate.client.feature.location.timeline.generated.resources.history_pause
import logdate.client.feature.location.timeline.generated.resources.history_play
import logdate.client.feature.location.timeline.generated.resources.history_previous_day
import logdate.client.feature.location.timeline.generated.resources.history_previous_stop
import logdate.client.feature.location.timeline.generated.resources.history_replay
import org.jetbrains.compose.resources.stringResource

@Composable
fun HumanLocationHistoryContent(
    state: HumanLocationHistoryState,
    actions: HumanLocationHistoryActions,
    modifier: Modifier = Modifier,
    mapContent: @Composable (Modifier) -> Unit = {},
    toolbarActions: @Composable RowScope.() -> Unit = {},
    supportingDetail: (@Composable () -> Unit)? = null,
    initialSupportingExtent: app.logdate.ui.workspace.SupportingExtent = app.logdate.ui.workspace.SupportingExtent.Peek,
    mapAvailable: Boolean = true,
) {
    HumanHistoryWorkspace(state, actions, modifier, mapContent, toolbarActions, supportingDetail, initialSupportingExtent, mapAvailable)
}

@Composable
internal fun HistoryDateHeader(
    state: HumanLocationHistoryState,
    actions: HumanLocationHistoryActions,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        HistoryDateButton(state, actions, Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                state.daySummary.asString(),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = { actions.onDayOffset(-1) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.history_previous_day))
            }
            IconButton(onClick = { actions.onDayOffset(1) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(Res.string.history_next_day))
            }
        }
    }
}

@Composable
private fun HistoryDateButton(
    state: HumanLocationHistoryState,
    actions: HumanLocationHistoryActions,
    modifier: Modifier,
) {
    TextButton(onClick = actions.onCalendar, modifier = modifier, contentPadding = PaddingValues(0.dp)) {
        Text(
            state.dateLabel.asString(),
            Modifier.weight(1f),
            textAlign = TextAlign.Start,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Icon(Icons.Default.CalendarMonth, null, Modifier.padding(horizontal = 8.dp).size(18.dp))
    }
}

@Composable
internal fun HistoryDayList(
    state: HumanLocationHistoryState,
    actions: HumanLocationHistoryActions,
    modifier: Modifier,
    listState: androidx.compose.foundation.lazy.LazyListState =
        androidx.compose.foundation.lazy
            .rememberLazyListState(),
    header: @Composable () -> Unit = {},
) {
    LazyColumn(modifier, state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
        item { header() }
        if (state.items.isEmpty()) {
            item {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(Res.string.history_empty_day), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(Res.string.history_empty_day_body))
                }
            }
        }
        itemsIndexed(state.items, key = { _, item -> item.id }) { index, item ->
            HistoryTimelineItem(
                item,
                state.selectedItemId == item.id,
                actions,
                Modifier.padding(horizontal = 16.dp),
                isLast = index == state.items.lastIndex,
                isFirst = index == 0,
            )
        }
        item {
            TextButton(
                onClick = { actions.onEdit(null, HistoryEditAction.AddVisit) },
                modifier = Modifier.padding(start = 60.dp, top = 8.dp),
            ) {
                Text(stringResource(Res.string.history_add_visit))
            }
        }
    }
}

@Composable
internal fun HistoryReplay(
    state: HumanLocationHistoryState,
    actions: HumanLocationHistoryActions,
) {
    val replayLabel = stringResource(Res.string.history_replay)
    Column(Modifier.padding(horizontal = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(replayLabel, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            actions.onReplayPlaying?.let { onPlay ->
                TextButton(onClick = { onPlay(!state.replayPlaying) }) {
                    Text(stringResource(if (state.replayPlaying) Res.string.history_pause else Res.string.history_play))
                }
            }
            IconButton(
                onClick = { state.adjacentItem(-1)?.let { actions.onSelectItem(it.id) } },
                enabled = state.adjacentItem(-1) != null,
            ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.history_previous_stop)) }
            IconButton(
                onClick = { state.adjacentItem(1)?.let { actions.onSelectItem(it.id) } },
                enabled = state.adjacentItem(1) != null,
            ) { Icon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(Res.string.history_next_stop)) }
        }
        val selectedIndex = state.items.indexOfFirst { it.id == state.selectedItemId }.coerceAtLeast(0)
        Slider(
            value = selectedIndex.toFloat() / (state.items.size - 1).coerceAtLeast(1),
            onValueChange = { position -> state.itemAtReplayPosition(position)?.let { actions.onSelectItem(it.id) } },
            steps = (state.items.size - 2).coerceAtLeast(0),
            enabled = state.items.size > 1,
            modifier = Modifier.semantics { contentDescription = replayLabel },
        )
        state.selectedItem?.let { item ->
            Column(
                Modifier.fillMaxWidth().testTag("history-replay-preview"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(item.title.asString(), style = MaterialTheme.typography.titleMedium)
                Text(
                    item.timeLabel.asString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                item.memories.take(2).forEach { HistoryMemoryPreview(it, actions.onOpenMemory) }
                TextButton(onClick = { actions.onOpenDetail(item.id) }, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(Res.string.history_details))
                }
            }
        }
    }
}
