@file:OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@file:Suppress("ktlint:standard:function-naming")

package app.logdate.ui.timeline.newstuff

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.logdate.client.text.asString
import app.logdate.ui.common.applyPaddingIfLast
import app.logdate.ui.restore.LocalIsPostCloudRestore
import app.logdate.ui.theme.Spacing
import app.logdate.ui.timeline.TimelineDayUiState
import app.logdate.ui.timeline.TimelineLoadingState
import app.logdate.ui.timeline.TimelineSuggestionBlock
import app.logdate.ui.timeline.TimelineSuggestionBlockType
import app.logdate.ui.timeline.TimelineSuggestionBlockUiState
import app.logdate.ui.timeline.collectLazyTimelineAudioNoteIds
import app.logdate.ui.workspace.LocalWorkspaceEnabled
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.datetime.LocalDate
import logdate.client.ui.generated.resources.Res
import logdate.client.ui.generated.resources.suggestion_after_moving_message
import logdate.client.ui.generated.resources.suggestion_after_trip_message
import logdate.client.ui.generated.resources.suggestion_draft_fallback
import logdate.client.ui.generated.resources.suggestion_empty_day_prompt
import logdate.client.ui.generated.resources.suggestion_morning_message
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.absoluteValue
import kotlin.uuid.Uuid

sealed class EndOfTimelineUiState {
    data class BirthdayCelebration(
        val birthDate: LocalDate,
        val daysSinceBirth: Int,
    ) : EndOfTimelineUiState()

    data object DiscoveryEasterEgg : EndOfTimelineUiState()
}

internal enum class TimelineDayLayoutMode {
    COMPACT,
    MEDIUM,
    EXPANDED,
}

internal enum class TimelineListContentType {
    SUGGESTION,
    DAY_COMPACT,
    DAY_MEDIUM,
    DAY_EXPANDED,
    GAP,
    SKELETON,
    EMPTY_STATE,
    APPEND_LOADING,
    APPEND_ERROR,
    END_OF_TIMELINE,
}

/** Widest the timeline's reading column is allowed to get, regardless of window size. */
private val TIMELINE_MAX_CONTENT_WIDTH = 720.dp

internal data class TimelineDayStyle(
    val accentColor: Color,
    val softAccentColor: Color,
    val chipColor: Color,
    val textHighlightColor: Color,
)

@Composable
fun TimelineList(
    items: List<TimelineDayUiState>,
    endOfTimelineState: EndOfTimelineUiState,
    onOpenDay: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    loadingState: TimelineLoadingState = TimelineLoadingState.Loaded,
    isLoadingMore: Boolean = false,
    hasMoreOlderContent: Boolean = false,
    appendError: String? = null,
    onLoadMoreOlder: () -> Unit = {},
    timelineSuggestion: TimelineSuggestionBlock? = null,
    onStartWriting: () -> Unit = {},
    onOpenDraft: (draftId: String) -> Unit = {},
    onViewMemoryDay: (LocalDate) -> Unit = {},
    onShareMemory: (TimelineSuggestionBlockUiState) -> Unit = {},
    onVisibleAudioNoteIdsChanged: (Set<Uuid>) -> Unit = {},
    onImportBackup: () -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    pendingMomentContent: @Composable (app.logdate.client.repository.moments.MarkedMoment) -> Unit = {},
    onOpenTextNote: (Uuid) -> Unit = {},
    onDismissSuggestion: () -> Unit = {},
    onUseSuggestion: () -> Unit = {},
    onRecordVoice: () -> Unit = onStartWriting,
) {
    val isPostCloudRestore = LocalIsPostCloudRestore.current
    val workspaceEnabled = LocalWorkspaceEnabled.current
    val draftMessage = stringResource(Res.string.suggestion_draft_fallback)
    val emptyDayMessage = stringResource(Res.string.suggestion_empty_day_prompt)
    val emptyDayLocation = (timelineSuggestion as? TimelineSuggestionBlock.EmptyDay)?.locationName?.asString()
    val morningMessage = stringResource(Res.string.suggestion_morning_message)
    val activityMessage =
        (timelineSuggestion as? TimelineSuggestionBlock.AfterActivity)?.let { activity ->
            val message = if (activity.trip) Res.plurals.suggestion_after_trip_message else Res.plurals.suggestion_after_moving_message
            pluralStringResource(message, activity.minutes, activity.minutes)
        }
    val suggestionBlockState: TimelineSuggestionBlockUiState? =
        remember(timelineSuggestion, draftMessage, emptyDayMessage, emptyDayLocation, morningMessage, activityMessage) {
            when (timelineSuggestion) {
                is TimelineSuggestionBlock.AfterActivity -> {
                    TimelineSuggestionBlockUiState(
                        type = TimelineSuggestionBlockType.AFTER_ACTIVITY,
                        message = activityMessage.orEmpty(),
                        activityTrip = timelineSuggestion.trip,
                    )
                }

                TimelineSuggestionBlock.MorningStart -> {
                    TimelineSuggestionBlockUiState(type = TimelineSuggestionBlockType.MORNING_START, message = morningMessage)
                }

                is TimelineSuggestionBlock.CompleteDraft -> {
                    TimelineSuggestionBlockUiState(
                        type = TimelineSuggestionBlockType.COMPLETE_DRAFT,
                        message = draftMessage,
                        draftId = timelineSuggestion.draftId,
                    )
                }

                is TimelineSuggestionBlock.EmptyDay -> {
                    TimelineSuggestionBlockUiState(
                        type = TimelineSuggestionBlockType.EMPTY_DAY,
                        message = emptyDayMessage,
                        location = emptyDayLocation,
                    )
                }

                is TimelineSuggestionBlock.MemoryRecall -> {
                    TimelineSuggestionBlockUiState(
                        type = TimelineSuggestionBlockType.MEMORY_RECALL,
                        message = timelineSuggestion.title,
                        memoryDate = timelineSuggestion.memoryDate,
                        people = timelineSuggestion.people,
                        mediaUris = timelineSuggestion.mediaUris,
                        isAiGenerated = timelineSuggestion.isAiGenerated,
                    )
                }

                is TimelineSuggestionBlock.UpcomingEvent -> {
                    TimelineSuggestionBlockUiState(
                        type = TimelineSuggestionBlockType.UPCOMING_EVENT,
                        message = timelineSuggestion.title,
                        eventId = timelineSuggestion.eventId,
                        eventStartTime = timelineSuggestion.startTime,
                        location = timelineSuggestion.placeName,
                    )
                }

                null -> {
                    null
                }
            }
        }
    // Retain the last non-null state so the exit animation has content to render.
    var lastSuggestion by remember { mutableStateOf(suggestionBlockState) }
    if (suggestionBlockState != null) {
        lastSuggestion = suggestionBlockState
    }

    // fillMaxWidth so the capped reading column below has room to centre itself; without it the
    // box shrink-wraps to the column and the feed hugs the left edge on wide windows.
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val layoutMode = maxWidth.toTimelineLayoutMode()

        LaunchedEffect(listState, items.size, hasMoreOlderContent, isLoadingMore, appendError) {
            snapshotFlow {
                val lastVisibleIndex =
                    listState.layoutInfo.visibleItemsInfo
                        .lastOrNull()
                        ?.index ?: return@snapshotFlow false
                hasMoreOlderContent &&
                    !isLoadingMore &&
                    appendError == null &&
                    items.isNotEmpty() &&
                    lastVisibleIndex >= (listState.layoutInfo.totalItemsCount - 4).coerceAtLeast(0)
            }.distinctUntilChanged()
                .filter { shouldLoadMore -> shouldLoadMore }
                .collect {
                    onLoadMoreOlder()
                }
        }

        // Resolve visible days by item key rather than by list position. Sticky headers mean a
        // day no longer occupies a single predictable slot, and position arithmetic would keep
        // resolving to *some* day rather than failing visibly — silently prefetching
        // transcription for the wrong notes.
        val dayIndicesByKey =
            remember(items) {
                items.withIndex().associate { (index, item) -> item.date.toString() to index }
            }

        LaunchedEffect(listState, items, dayIndicesByKey) {
            snapshotFlow {
                val visibleDayIndices =
                    listState.layoutInfo.visibleItemsInfo
                        .mapNotNull { itemInfo -> dayIndicesByKey[itemInfo.key] }
                        .toSet()

                collectLazyTimelineAudioNoteIds(
                    items = items,
                    visibleDayIndices = visibleDayIndices,
                )
            }.distinctUntilChanged()
                .collect { noteIds ->
                    onVisibleAudioNoteIdsChanged(noteIds)
                }
        }

        LazyColumn(
            // Cap the reading column. Without this a day stretches to the full window on a
            // tablet, which runs the summary out to ~90 characters per line and lets a single
            // photo — sized by aspect ratio off its own width — grow taller than the viewport.
            modifier =
                Modifier
                    .fillMaxHeight()
                    .widthIn(max = TIMELINE_MAX_CONTENT_WIDTH)
                    .align(Alignment.TopCenter),
            state = listState,
            // No uniform gap: a day's sticky header must sit tight against the content it
            // labels. Day-to-day separation is applied by the day item's own bottom padding.
        ) {
            item(
                contentType = TimelineListContentType.SUGGESTION,
            ) {
                AnimatedVisibility(
                    visible = suggestionBlockState != null,
                    enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                    exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
                ) {
                    lastSuggestion?.let { blockState ->
                        TimelineSuggestionBlock(
                            state = blockState,
                            onStartWriting = onStartWriting,
                            onOpenDraft = onOpenDraft,
                            onViewMemoryDay = onViewMemoryDay,
                            onShareMemory = onShareMemory,
                            onDismiss = onDismissSuggestion,
                            onUse = onUseSuggestion,
                            onRecordVoice = onRecordVoice,
                            modifier = Modifier.padding(Spacing.lg),
                        )
                    }
                }
            }

            if (items.isEmpty() && loadingState != TimelineLoadingState.Loaded) {
                items(
                    items = listOf("loading-day-0", "loading-day-1", "loading-day-2"),
                    key = { it },
                    contentType = { TimelineListContentType.SKELETON },
                ) {
                    TimelineDaySkeleton()
                }
            } else if (items.isEmpty() && loadingState == TimelineLoadingState.Loaded) {
                item(
                    contentType = TimelineListContentType.EMPTY_STATE,
                ) {
                    if (isPostCloudRestore) {
                        PostRestoreEmptyState(
                            onImportBackup = onImportBackup,
                            onStartWriting = onStartWriting,
                            modifier = Modifier.padding(Spacing.lg),
                        )
                    } else {
                        TimelineEmptyState(
                            onStartWriting = onStartWriting,
                            modifier = Modifier.padding(Spacing.lg),
                        )
                    }
                }
            } else {
                items.forEachIndexed { index, item ->
                    stickyHeader(key = "header-${item.date}") {
                        TimelineDayHeaderBar(item = item, onOpenDay = onOpenDay)
                    }

                    item(
                        key = item.date.toString(),
                        contentType = layoutMode.contentType(),
                    ) {
                        TimelineDayListItem(
                            item = item,
                            layoutMode = layoutMode,
                            onOpenDay = onOpenDay,
                            onOpenTextNote = onOpenTextNote,
                            pendingMomentContent = pendingMomentContent,
                            modifier =
                                Modifier
                                    .padding(bottom = Spacing.xl)
                                    .applyPaddingIfLast(
                                        currentIndex = index,
                                        totalItems = items.size,
                                    ),
                        )
                    }

                    if (index < items.lastIndex) {
                        val daysBetween =
                            (item.date.toEpochDays() - items[index + 1].date.toEpochDays()).absoluteValue
                        if (daysBetween > 10) {
                            item(
                                key = "gap-${item.date}",
                                contentType = TimelineListContentType.GAP,
                            ) {
                                TimeGapMessageItem()
                            }
                        }
                    }
                }
            }

            if (isLoadingMore) {
                item(
                    contentType = TimelineListContentType.APPEND_LOADING,
                ) {
                    TimelineAppendLoadingItem()
                }
            }

            if (appendError != null) {
                item(
                    contentType = TimelineListContentType.APPEND_ERROR,
                ) {
                    TimelineAppendErrorItem(
                        message = appendError,
                        onRetry = onLoadMoreOlder,
                    )
                }
            }

            if (!workspaceEnabled && !hasMoreOlderContent && items.isNotEmpty()) {
                item(
                    contentType = TimelineListContentType.END_OF_TIMELINE,
                ) {
                    EndOfTimelineItem(endOfTimelineState)
                }
            }
        }
    }
}
