@file:Suppress("ktlint:standard:function-naming", "ktlint:standard:no-wildcard-imports")

package app.logdate.ui.timeline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.logdate.client.text.UiText
import app.logdate.ui.common.MetadataChip
import app.logdate.ui.common.PeopleMetadataChip
import app.logdate.ui.theme.Spacing
import coil3.compose.AsyncImage
import kotlinx.datetime.LocalDate
import logdate.client.ui.generated.resources.Res
import logdate.client.ui.generated.resources.common_dismiss
import logdate.client.ui.generated.resources.finish_draft
import logdate.client.ui.generated.resources.share_link
import logdate.client.ui.generated.resources.start_writing
import logdate.client.ui.generated.resources.suggestion_add_moment
import logdate.client.ui.generated.resources.suggestion_capture_for_event
import logdate.client.ui.generated.resources.suggestion_label_activity
import logdate.client.ui.generated.resources.suggestion_label_draft
import logdate.client.ui.generated.resources.suggestion_label_memory
import logdate.client.ui.generated.resources.suggestion_label_morning
import logdate.client.ui.generated.resources.suggestion_label_today
import logdate.client.ui.generated.resources.suggestion_label_trip
import logdate.client.ui.generated.resources.suggestion_label_upcoming
import logdate.client.ui.generated.resources.suggestion_media_item
import logdate.client.ui.generated.resources.suggestion_near_location
import logdate.client.ui.generated.resources.suggestion_record_voice
import logdate.client.ui.generated.resources.view_memory
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/** Test tags for the suggestion block's actions. */
object TimelineSuggestionTags {
    const val ADD_MOMENT = "timeline_suggestion_add_moment"
    const val DISMISS = "timeline_suggestion_dismiss"
    const val RECORD_VOICE = "timeline_suggestion_record_voice"
}

/**
 * Represents different types of timeline suggestion blocks that can be shown to users.
 */
sealed interface TimelineSuggestionBlock {
    /**
     * The user has an unfinished draft to complete.
     */
    data class CompleteDraft(
        val draftId: String,
    ) : TimelineSuggestionBlock

    /**
     * Encourage writing when the user has no entries today.
     *
     * @property locationName Where the user is now, or null when it is not known.
     */
    data class EmptyDay(
        val locationName: UiText? = null,
    ) : TimelineSuggestionBlock

    /**
     * "On this day" memory recall — past entries worth revisiting.
     */
    data class MemoryRecall(
        val memoryDate: LocalDate,
        val title: String,
        val people: List<String> = emptyList(),
        val mediaUris: List<MediaObjectUiState> = emptyList(),
        val isAiGenerated: Boolean = false,
    ) : TimelineSuggestionBlock

    /**
     * An event the user has on their calendar that's about to start. Surfaces a "Capture for
     * this" action so the user can jump straight into recording before the event begins.
     */
    data class UpcomingEvent(
        val eventId: String,
        val title: String,
        val startTime: Instant,
        val placeName: String? = null,
    ) : TimelineSuggestionBlock

    /** A walk, ride or trip that lasted [minutes] just ended; [trip] for a vehicle. */
    data class AfterActivity(
        val minutes: Int,
        val trip: Boolean,
    ) : TimelineSuggestionBlock

    /** The person just woke up. */
    data object MorningStart : TimelineSuggestionBlock
}

data class TimelineSuggestionBlockUiState(
    val type: TimelineSuggestionBlockType,
    val message: String,
    val draftId: String? = null,
    val memoryDate: LocalDate? = null,
    val location: String? = null,
    val people: List<String> = emptyList(),
    val mediaUris: List<MediaObjectUiState> = emptyList(),
    val isAiGenerated: Boolean = false,
    val eventId: String? = null,
    val eventStartTime: Instant? = null,
    /** For [TimelineSuggestionBlockType.AFTER_ACTIVITY]: whether it was a trip in a vehicle. */
    val activityTrip: Boolean = false,
)

enum class TimelineSuggestionBlockType {
    /**
     * The user has an unfinished draft.
     */
    COMPLETE_DRAFT,

    /**
     * The user has no entries for today.
     */
    EMPTY_DAY,

    /**
     * A past memory worth revisiting — "on this day" style.
     */
    MEMORY_RECALL,

    /**
     * An upcoming event the user can capture media for.
     */
    UPCOMING_EVENT,

    /** A walk, ride or trip just ended, while activity awareness is on. */
    AFTER_ACTIVITY,

    /** The person just woke up, while activity awareness is on. */
    MORNING_START,
}

/**
 * A prompt to suggest adding memories to the timeline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineSuggestionBlock(
    state: TimelineSuggestionBlockUiState,
    onStartWriting: () -> Unit,
    onOpenDraft: (draftId: String) -> Unit,
    onViewMemoryDay: (LocalDate) -> Unit,
    onShareMemory: (TimelineSuggestionBlockUiState) -> Unit,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit = {},
    onUse: () -> Unit = {},
    onRecordVoice: () -> Unit = onStartWriting,
) {
    val suggestedTypeTitle =
        when (state.type) {
            TimelineSuggestionBlockType.COMPLETE_DRAFT -> {
                stringResource(Res.string.suggestion_label_draft)
            }

            TimelineSuggestionBlockType.EMPTY_DAY -> {
                stringResource(Res.string.suggestion_label_today)
            }

            TimelineSuggestionBlockType.MEMORY_RECALL -> {
                stringResource(Res.string.suggestion_label_memory)
            }

            TimelineSuggestionBlockType.UPCOMING_EVENT -> {
                stringResource(Res.string.suggestion_label_upcoming)
            }

            TimelineSuggestionBlockType.AFTER_ACTIVITY -> {
                stringResource(if (state.activityTrip) Res.string.suggestion_label_trip else Res.string.suggestion_label_activity)
            }

            TimelineSuggestionBlockType.MORNING_START -> {
                stringResource(Res.string.suggestion_label_morning)
            }
        }

    val containerColor =
        when (state.type) {
            TimelineSuggestionBlockType.EMPTY_DAY -> MaterialTheme.colorScheme.tertiaryContainer
            else -> MaterialTheme.colorScheme.primaryContainer
        }

    val contentColor =
        when (state.type) {
            TimelineSuggestionBlockType.EMPTY_DAY -> MaterialTheme.colorScheme.onTertiaryContainer
            else -> MaterialTheme.colorScheme.onPrimaryContainer
        }

    val labelColor =
        when (state.type) {
            TimelineSuggestionBlockType.EMPTY_DAY -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.primary
        }

    Column(
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        modifier =
            modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = containerColor,
            contentColor = contentColor,
            shape = MaterialTheme.shapes.large,
        ) {
            Column {
                Column(
                    modifier =
                        Modifier
                            .padding(Spacing.lg)
                            .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    Text(
                        suggestedTypeTitle,
                        style =
                            MaterialTheme.typography.labelMedium.copy(
                                color = labelColor,
                            ),
                    )
                    Text(state.message, style = MaterialTheme.typography.titleMedium)
                }
                if (state.mediaUris.isNotEmpty()) {
                    RecentMediaCarousel(state.mediaUris)
                }
                SuggestionMetadata(state)
            }
        }

        SuggestionActions(
            state = state,
            onStartWriting = onStartWriting,
            onOpenDraft = onOpenDraft,
            onViewMemoryDay = onViewMemoryDay,
            onShareMemory = onShareMemory,
            onDismiss = onDismiss,
            onUse = onUse,
            onRecordVoice = onRecordVoice,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SuggestionMetadata(
    state: TimelineSuggestionBlockUiState,
    modifier: Modifier = Modifier,
) {
    val hasMetadata = state.people.isNotEmpty() || state.location != null

    if (!hasMetadata) return

    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        modifier =
            modifier.padding(
                start = Spacing.lg,
                end = Spacing.lg,
                top = Spacing.sm,
                bottom = Spacing.lg,
            ),
    ) {
        if (state.people.isNotEmpty()) {
            PeopleMetadataChip(state.people)
        }
        if (state.location != null) {
            MetadataChip(
                label = stringResource(Res.string.suggestion_near_location, state.location),
                icon = {
                    Icon(
                        Icons.Default.LocationOn,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecentMediaCarousel(
    mediaUris: List<MediaObjectUiState>,
    modifier: Modifier = Modifier,
) {
    val carouselState = rememberCarouselState(itemCount = mediaUris::size)

    HorizontalMultiBrowseCarousel(
        state = carouselState,
        preferredItemWidth = 200.dp,
        itemSpacing = Spacing.sm,
        contentPadding =
            PaddingValues(
                horizontal = Spacing.lg,
                vertical = Spacing.sm,
            ),
        modifier =
            modifier
                .height(142.dp)
                .fillMaxWidth(),
    ) {
        mediaUris.forEach { media ->
            key(media.uid) {
                AsyncImage(
                    model = media.uri,
                    contentDescription = stringResource(Res.string.suggestion_media_item),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.extraLarge),
                    contentScale = ContentScale.Crop,
                )
            }
        }
    }
}

/**
 * Actions section for timeline suggestions that adapts based on suggestion type.
 */
@Composable
private fun SuggestionActions(
    state: TimelineSuggestionBlockUiState,
    onStartWriting: () -> Unit,
    onOpenDraft: (String) -> Unit,
    onViewMemoryDay: (LocalDate) -> Unit,
    onShareMemory: (TimelineSuggestionBlockUiState) -> Unit,
    onDismiss: () -> Unit,
    onUse: () -> Unit,
    onRecordVoice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.End),
        modifier = modifier,
    ) {
        when (state.type) {
            TimelineSuggestionBlockType.COMPLETE_DRAFT -> {
                AssistChip(
                    onClick = { state.draftId?.let(onOpenDraft) },
                    label = { Text(stringResource(Res.string.finish_draft)) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.EditNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                )
            }

            TimelineSuggestionBlockType.EMPTY_DAY -> {
                AssistChip(
                    onClick = onStartWriting,
                    label = { Text(stringResource(Res.string.start_writing)) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.EditNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                        )
                    },
                )
            }

            TimelineSuggestionBlockType.MEMORY_RECALL -> {
                AssistChip(
                    onClick = { state.memoryDate?.let(onViewMemoryDay) },
                    label = { Text(stringResource(Res.string.view_memory)) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.History,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                )
                AssistChip(
                    onClick = { state.memoryDate?.let { onShareMemory(state) } },
                    modifier = Modifier.testTag("timeline_memory_share_action"),
                    label = { Text(stringResource(Res.string.share_link)) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                )
            }

            TimelineSuggestionBlockType.AFTER_ACTIVITY, TimelineSuggestionBlockType.MORNING_START -> {
                TextButton(onClick = onDismiss, modifier = Modifier.testTag(TimelineSuggestionTags.DISMISS)) {
                    Text(stringResource(Res.string.common_dismiss))
                }
                if (state.type == TimelineSuggestionBlockType.MORNING_START) {
                    Button(
                        onClick = {
                            onUse()
                            onRecordVoice()
                        },
                        modifier = Modifier.testTag(TimelineSuggestionTags.RECORD_VOICE),
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(stringResource(Res.string.suggestion_record_voice))
                    }
                } else {
                    Button(
                        onClick = {
                            onUse()
                            onStartWriting()
                        },
                        modifier = Modifier.testTag(TimelineSuggestionTags.ADD_MOMENT),
                    ) {
                        Text(stringResource(Res.string.suggestion_add_moment))
                    }
                }
            }

            TimelineSuggestionBlockType.UPCOMING_EVENT -> {
                AssistChip(
                    onClick = onStartWriting,
                    label = { Text(stringResource(Res.string.suggestion_capture_for_event)) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.EditNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                )
            }
        }
    }
}

@Preview
@Composable
private fun TimelineSuggestionBlockPreview_MemoryRecall() {
    val state =
        TimelineSuggestionBlockUiState(
            type = TimelineSuggestionBlockType.MEMORY_RECALL,
            message = "Trip to Six Flags Over Texas",
            mediaUris =
                listOf(
                    MediaObjectUiState(uri = "https://picsum.photos/200/300", uid = "1"),
                    MediaObjectUiState(uri = "https://picsum.photos/200/300", uid = "2"),
                    MediaObjectUiState(uri = "https://picsum.photos/200/300", uid = "3"),
                    MediaObjectUiState(uri = "https://picsum.photos/200/300", uid = "4"),
                ),
            people = listOf("Lane", "Anna", "Willie", "Jake"),
        )
    TimelineSuggestionBlock(
        state,
        onStartWriting = {},
        onOpenDraft = {},
        onViewMemoryDay = {},
        onShareMemory = { _ -> },
    )
}

@Preview
@Composable
private fun TimelineSuggestionBlockPreview_CompleteDraft() {
    val state =
        TimelineSuggestionBlockUiState(
            type = TimelineSuggestionBlockType.COMPLETE_DRAFT,
            message = "Finish your draft while it's still fresh.",
            draftId = "draft-1",
        )
    TimelineSuggestionBlock(
        state,
        onStartWriting = {},
        onOpenDraft = {},
        onViewMemoryDay = {},
        onShareMemory = { _ -> },
    )
}

@Preview
@Composable
private fun TimelineSuggestionBlockPreview_EmptyDay() {
    val state =
        TimelineSuggestionBlockUiState(
            type = TimelineSuggestionBlockType.EMPTY_DAY,
            message = "What's going on?",
            location = "Dallas",
        )
    TimelineSuggestionBlock(
        state,
        onStartWriting = {},
        onOpenDraft = {},
        onViewMemoryDay = {},
        onShareMemory = { _ -> },
    )
}
