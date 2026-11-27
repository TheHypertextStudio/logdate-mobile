@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.location.timeline.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import logdate.client.feature.location.timeline.generated.resources.history_jump_to_latest
import logdate.client.feature.location.timeline.generated.resources.history_next_day
import logdate.client.feature.location.timeline.generated.resources.history_next_stop
import logdate.client.feature.location.timeline.generated.resources.history_pause
import logdate.client.feature.location.timeline.generated.resources.history_play
import logdate.client.feature.location.timeline.generated.resources.history_previous_day
import logdate.client.feature.location.timeline.generated.resources.history_previous_stop
import logdate.client.feature.location.timeline.generated.resources.history_replay
import logdate.client.feature.location.timeline.generated.resources.history_speed_multiplier
import logdate.client.feature.location.timeline.generated.resources.history_today
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun HistoryDateHeader(
    state: HumanLocationHistoryState,
    actions: HumanLocationHistoryActions,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        HistoryDateButton(state, actions, Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                state.daySummary?.asString().orEmpty(),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!state.isToday && actions.onJumpToToday != null) {
                TextButton(onClick = actions.onJumpToToday) {
                    Text(stringResource(Res.string.history_today))
                }
            }
            IconButton(onClick = { actions.onDayOffset(-1) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.history_previous_day))
            }
            IconButton(onClick = { actions.onDayOffset(1) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(Res.string.history_next_day))
            }
        }
        actions.onSelectDate?.let { onSelectDate ->
            HistoryWeekStrip(days = state.weekDays, selectedDate = state.selectedDate, onSelectDate = onSelectDate)
        }
    }
}

@Composable
private fun HistoryDateButton(
    state: HumanLocationHistoryState,
    actions: HumanLocationHistoryActions,
    modifier: Modifier,
) {
    Surface(
        onClick = actions.onCalendar,
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.primary,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FlowRow(
                modifier = Modifier.weight(1f).padding(end = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    state.dateLabel.asString(),
                    textAlign = TextAlign.Start,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                state.timezoneBadgeLabel?.let { badge ->
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    ) {
                        Text(
                            badge.asString(),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            Icon(Icons.Default.CalendarMonth, null, Modifier.padding(horizontal = 8.dp).size(18.dp))
        }
    }
}

@Composable
internal fun HistoryDayList(
    state: HumanLocationHistoryState,
    actions: HumanLocationHistoryActions,
    modifier: Modifier,
    listState: LazyListState = rememberLazyListState(),
    header: @Composable () -> Unit = {},
) {
    LazyColumn(modifier, state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
        item { header() }
        if (state.items.isEmpty()) {
            item {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(Res.string.history_empty_day), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(Res.string.history_empty_day_body))
                    val latestDate = state.latestRecordedDate
                    val selectedDate = state.selectedDate
                    if (latestDate != null && latestDate != selectedDate && actions.onJumpToLatest != null) {
                        TextButton(onClick = actions.onJumpToLatest, contentPadding = PaddingValues(0.dp)) {
                            Text(stringResource(Res.string.history_jump_to_latest))
                        }
                    }
                }
            }
        }
        itemsIndexed(
            items = state.items,
            key = { _, item -> item.id },
            contentType = { _, item -> item.kind },
        ) { index, item ->
            HistoryTimelineItem(
                item,
                state.selectedItemId == item.id,
                actions,
                Modifier.padding(horizontal = 16.dp).animateItem(),
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
    val continuousScrub = actions.onReplayScrub != null
    Column(Modifier.padding(horizontal = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(replayLabel, style = MaterialTheme.typography.labelLarge)
                state.replayTimeLabel?.let { label ->
                    Text(label.asString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            actions.onReplaySpeed?.let { onSpeed ->
                TextButton(onClick = onSpeed) {
                    Text(stringResource(Res.string.history_speed_multiplier, state.replaySpeed.toInt()))
                }
            }
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
        val sliderValue =
            if (continuousScrub) {
                state.replayProgress.coerceIn(0f, 1f)
            } else {
                selectedIndex.toFloat() / (state.items.size - 1).coerceAtLeast(1)
            }
        Slider(
            value = sliderValue,
            onValueChange = { position ->
                val onScrub = actions.onReplayScrub
                if (onScrub != null) onScrub(position) else state.itemAtReplayPosition(position)?.let { actions.onSelectItem(it.id) }
            },
            steps = if (continuousScrub) 0 else (state.items.size - 2).coerceAtLeast(0),
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
