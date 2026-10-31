@file:Suppress("ktlint:standard:function-naming", "ktlint:standard:no-wildcard-imports")

package app.logdate.ui.timeline

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import app.logdate.client.text.toUiText
import app.logdate.ui.theme.Spacing
import app.logdate.ui.timeline.newstuff.EndOfTimelineUiState
import app.logdate.ui.timeline.newstuff.TimelineList
import app.logdate.ui.workspace.WorkspacePanel
import app.logdate.util.now
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import logdate.client.ui.generated.resources.*
import logdate.client.ui.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant
import kotlin.uuid.Uuid

data class TimelineUiState(
    val items: List<TimelineDayUiState> = emptyList(),
    val loadingState: TimelineLoadingState = TimelineLoadingState.Loaded,
    val isLoadingMore: Boolean = false,
    val hasMoreOlderContent: Boolean = false,
    val appendError: String? = null,
)

@Composable
fun TimelinePane(
    uiState: TimelineUiState,
    onNewEntry: () -> Unit,
    onOpenDay: (LocalDate) -> Unit,
    onVisibleAudioNoteIdsChanged: (Set<Uuid>) -> Unit = {},
    onLoadMoreOlder: () -> Unit = {},
    timelineSuggestion: TimelineSuggestionBlock? = null,
    listState: LazyListState = rememberLazyListState(),
    onStartWriting: () -> Unit = onNewEntry,
    onOpenDraft: (draftId: String) -> Unit = {},
    onShareMemory: (TimelineSuggestionBlockUiState) -> Unit = {},
    onImportBackup: () -> Unit = {},
    onDismissSuggestion: () -> Unit = {},
    onUseSuggestion: () -> Unit = {},
    onRecordVoice: () -> Unit = onStartWriting,
    birthday: Instant? = null,
    modifier: Modifier = Modifier,
    pendingMomentContent: @Composable (app.logdate.client.repository.moments.MarkedMoment) -> Unit = {},
    onOpenTextNote: (Uuid) -> Unit = {},
) {
    val endOfTimelineState by remember(birthday) {
        derivedStateOf {
            if (birthday != null && birthday != Instant.DISTANT_PAST) {
                val birthDate = birthday.toLocalDateTime(TimeZone.currentSystemDefault()).date
                val daysSinceBirth = (LocalDate.now().toEpochDays() - birthDate.toEpochDays()).toInt()
                EndOfTimelineUiState.BirthdayCelebration(birthDate, daysSinceBirth)
            } else {
                EndOfTimelineUiState.DiscoveryEasterEgg
            }
        }
    }

    WorkspacePanel(modifier) {
        Column {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                TimelineList(
                    uiState.items,
                    endOfTimelineState,
                    onOpenDay,
                    Modifier,
                    uiState.loadingState,
                    uiState.isLoadingMore,
                    uiState.hasMoreOlderContent,
                    uiState.appendError,
                    onLoadMoreOlder,
                    timelineSuggestion,
                    onStartWriting,
                    onOpenDraft,
                    onOpenDay,
                    onShareMemory,
                    onVisibleAudioNoteIdsChanged,
                    onImportBackup,
                    listState,
                    pendingMomentContent,
                    onOpenTextNote,
                    onDismissSuggestion = onDismissSuggestion,
                    onUseSuggestion = onUseSuggestion,
                    onRecordVoice = onRecordVoice,
                )
                ScrollToTopButton(listState, Modifier.align(Alignment.BottomCenter).padding(bottom = Spacing.lg))
            }
        }
    }
}

@Composable
internal fun ScrollToTopButton(
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val panelCoroutineScope = rememberCoroutineScope()
    val showScrollToTop by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0
        }
    }

    fun scrollToTop() {
        panelCoroutineScope.launch {
            listState.animateScrollToItem(0)
        }
    }

    AnimatedVisibility(
        visible = showScrollToTop,
        modifier = modifier,
        enter = fadeIn() + slideInVertically { it },
        exit = fadeOut() + slideOutVertically { it },
    ) {
        ElevatedButton(
            onClick = ::scrollToTop,
        ) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowUp,
                contentDescription = stringResource(Res.string.scroll_to_top),
                modifier = Modifier.padding(end = Spacing.sm),
            )
            Text(stringResource(Res.string.back_to_today))
        }
    }
}

@Preview
@Composable
private fun TimelinePanePreview() {
    TimelinePane(
        uiState = TimelineUiState(),
        onOpenDay = {},
        onNewEntry = {},
        onVisibleAudioNoteIdsChanged = {},
        timelineSuggestion =
            TimelineSuggestionBlock.EmptyDay(
                locationName = "Dallas".toUiText(),
            ),
        birthday = Instant.fromEpochMilliseconds(1655342400000),
    )
}
