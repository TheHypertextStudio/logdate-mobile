package app.logdate.wear.presentation.timeline

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import app.logdate.wear.R
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.viewmodel.koinViewModel
import kotlin.time.Clock

@Composable
fun WearTimelineScreen(
    onNavigateBack: () -> Unit,
    onNavigateToDay: (LocalDate) -> Unit,
    viewModel: WearTimelineViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    WearTimelineContent(
        uiState = uiState,
        onDaySelected = onNavigateToDay,
    )
}

@Composable
internal fun WearTimelineContent(
    uiState: WearTimelineUiState,
    onDaySelected: (LocalDate) -> Unit = {},
) {
    val listState = rememberScalingLazyListState()

    ScreenScaffold(
        timeText = { TimeText() },
        scrollState = listState,
    ) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
        ) {
            item(key = "header") {
                Text(
                    text = stringResource(R.string.wear_timeline_title),
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp),
                )
            }

            if (uiState.days.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = stringResource(R.string.wear_timeline_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp),
                    )
                }
            } else {
                items(
                    items = uiState.days,
                    key = { it.date.toString() },
                ) { day ->
                    TimelineDayCard(
                        day = day,
                        onClick = { onDaySelected(day.date) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TimelineDayCard(
    day: WearTimelineDayUiState,
    onClick: () -> Unit,
) {
    val entryCountText =
        if (day.entryCount == 1) {
            stringResource(R.string.wear_timeline_entry_count_one, day.entryCount)
        } else {
            stringResource(R.string.wear_timeline_entry_count_other, day.entryCount)
        }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = formatDayLabel(day.date),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = entryCountText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (day.feeling != null) {
            Text(
                text = "${day.feeling.emoji} ${stringResource(day.feeling.labelRes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (day.previewText != null) {
            Text(
                text = day.previewText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
internal fun formatDayLabel(date: LocalDate): String {
    val now = Clock.System.now()
    val today = now.toLocalDateTime(TimeZone.currentSystemDefault()).date
    val yesterday = today.minus(1, DateTimeUnit.DAY)
    return when (date) {
        today -> {
            stringResource(R.string.wear_timeline_today)
        }

        yesterday -> {
            stringResource(R.string.wear_timeline_yesterday)
        }

        else -> {
            val monthName =
                date.month.name
                    .lowercase()
                    .replaceFirstChar { it.uppercase() }
            stringResource(R.string.wear_timeline_date_format, monthName, date.day)
        }
    }
}
