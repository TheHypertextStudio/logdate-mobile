@file:Suppress("ktlint:standard:function-naming", "ktlint:standard:no-wildcard-imports")

package app.logdate.feature.timeline.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.logdate.client.repository.journals.JournalNote
import app.logdate.ui.common.DropdownMenu
import app.logdate.ui.common.MarkdownText
import app.logdate.ui.common.noteDragSource
import app.logdate.ui.handwriting.HandwritingPreview
import app.logdate.ui.platform.PlatformIcons
import app.logdate.ui.theme.Spacing
import app.logdate.ui.timeline.TimelineLine
import app.logdate.util.toReadableDateShort
import app.logdate.util.weeksAgo
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import logdate.client.feature.timeline.generated.resources.*
import logdate.client.feature.timeline.generated.resources.Res
import logdate.client.feature.timeline.generated.resources.no_notes_from_today
import logdate.client.feature.timeline.generated.resources.period_last_week
import logdate.client.feature.timeline.generated.resources.period_months_ago
import logdate.client.feature.timeline.generated.resources.period_this_week
import logdate.client.feature.timeline.generated.resources.period_weeks_ago
import logdate.client.feature.timeline.generated.resources.period_years_ago
import logdate.client.feature.timeline.generated.resources.tap_to_view_content
import logdate.client.feature.timeline.generated.resources.timeline_today
import logdate.client.feature.timeline.generated.resources.timeline_yesterday
import logdate.client.ui.generated.resources.common_delete
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid
import logdate.client.ui.generated.resources.Res as UiRes

/**
 * Checks that there is an entry within the past eight hours.
 */
internal fun hasEntryFromToday(timelineItems: List<JournalNote>): Boolean {
    val now = Clock.System.now()
    val eightHoursAgo = now.minus(8.hours)
    return timelineItems.any { it.creationTimestamp > eightHoursAgo }
}

/**
 * Displays a scrollable list of journal entries with options to edit, delete, or open in new windows.
 *
 * Each entry in the timeline can be clicked to edit, long-pressed for a context menu with additional options,
 * or deleted. The "Open in New Window" option in the context menu enables multi-window editing on supported devices.
 *
 * @param timelineItems The list of journal notes to display in the timeline
 * @param onItemSelected Callback invoked when a timeline item is clicked to edit
 * @param onItemDeleted Callback invoked when a user confirms deletion of an entry
 * @param onNewEntry Callback invoked when the user taps the action to create a new entry
 * @param onOpenInNewWindow Callback invoked when the user selects "Open in New Window" from an entry's context menu.
 *        The callback receives the entry's UUID to identify which entry to open.
 * @param modifier Optional Compose modifier for customizing the Timeline's layout and appearance
 */
@Composable
internal fun Timeline(
    timelineItems: List<JournalNote>,
    onItemSelected: (uid: Uuid) -> Unit,
    onItemDeleted: (uid: Uuid) -> Unit,
    onNewEntry: () -> Unit,
    onOpenInNewWindow: (uid: Uuid) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(top = Spacing.xl),
    ) {
        constructTimeline(
            items = timelineItems,
            onItemSelected = onItemSelected,
            onItemDeleted = onItemDeleted,
            onNewEntry = onNewEntry,
            onOpenInNewWindow = onOpenInNewWindow,
        )
    }
}

@Composable
private fun TimelineContentHeader(title: String) {
    Row(
        modifier =
            Modifier
                .height(56.dp)
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
                .fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
    }
}

enum class TimeDetail {
    /**
     *
     */
    DAY,

    /**
     *
     */
    HOUR,
}

/**
 * A dropdown menu that displays options for a timeline entry.
 */
@Composable
private fun EntryDropdownMenu(
    isExpanded: Boolean,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onOpenInNewWindow: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    DropdownMenu(
        expanded = isExpanded,
        onDismissRequest = onDismiss,
        modifier = modifier,
    ) {
        DropdownMenuItem(
            text = {
                Text(stringResource(Res.string.open_in_new_window))
            },
            onClick = {
                onDismiss()
                onOpenInNewWindow()
            },
            leadingIcon = {
                Icon(painter = PlatformIcons.openInNew(), contentDescription = null)
            },
        )
        DropdownMenuItem(
            text = {
                Text(stringResource(UiRes.string.common_delete))
            },
            onClick = onDelete,
            leadingIcon = {
                Icon(painter = PlatformIcons.delete(), contentDescription = null)
            },
        )
    }
}

data class TimelineItemMetadata(
    val peopleSeen: Int = 0,
    val placesVisited: Int = 0,
    val notesRecorded: Int = 0,
)

@Composable
private fun TimelineItemMetadataBlock(
    metadata: TimelineItemMetadata,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        if (metadata.peopleSeen > 0) {
            MetadataItem(
                text =
                    stringResource(
                        Res.string.metadata_people_count,
                        metadata.peopleSeen,
                    ),
                icon = {
                    Icon(painter = PlatformIcons.people(), contentDescription = stringResource(Res.string.people))
                },
            )
        }
        if (metadata.placesVisited > 0) {
            MetadataItem(
                text =
                    stringResource(
                        Res.string.metadata_places_count,
                        metadata.placesVisited,
                    ),
                icon = {
                    Icon(painter = PlatformIcons.location(), contentDescription = stringResource(Res.string.places))
                },
            )
        }
        if (metadata.notesRecorded > 0) {
            MetadataItem(
                text =
                    stringResource(
                        Res.string.metadata_notes_count,
                        metadata.notesRecorded,
                    ),
                icon = {
                    Icon(
                        painter = PlatformIcons.note(),
                        contentDescription = stringResource(Res.string.notes),
                    )
                },
            )
        }
    }
}

@Composable
internal fun MetadataItem(
    text: String,
    icon: @Composable () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Box(modifier = Modifier.size(20.dp)) {
            icon()
        }
        Text(text = text)
    }
}

@Composable
private fun TimelineContentItem(
    item: JournalNote,
    metadata: TimelineItemMetadata,
    timeDetail: TimeDetail = TimeDetail.DAY,
    onItemSelected: (uid: Uuid) -> Unit,
    onDeleteItem: (uid: Uuid) -> Unit = {},
    onOpenInNewWindow: (uid: Uuid) -> Unit = {},
) {
    var showOptions by rememberSaveable { mutableStateOf(false) }
    Row(
        modifier =
            Modifier
                .clip(MaterialTheme.shapes.medium)
                .clickable {
                    onItemSelected(item.uid)
                }.fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        TimelineLine(
            modifier =
                Modifier
                    .padding(top = Spacing.lg)
                    .fillMaxHeight(),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            // Content container
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Header block
                Column(
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    modifier =
                        Modifier
                            .weight(1f)
                            .padding(vertical = Spacing.sm),
                ) {
                    Text(
                        item.creationTimestamp.toReadableDateShort(),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    TimelineItemMetadataBlock(metadata = metadata)
                }
                Box(
                    modifier =
                        Modifier
                            .wrapContentSize(Alignment.TopEnd),
                ) {
                    EntryDropdownMenu(
                        isExpanded = showOptions,
                        onDismiss = { showOptions = false },
                        onEdit = {
                            showOptions = false
                        },
                        onDelete = {
                            showOptions = false
                            onDeleteItem(item.uid)
                        },
                        onOpenInNewWindow = {
                            showOptions = false
                            onOpenInNewWindow(item.uid)
                        },
                    )
                    IconButton(onClick = { showOptions = true }) {
                        Icon(painter = PlatformIcons.more(), contentDescription = stringResource(Res.string.more))
                    }
                }
            }
            // Actual content
            when (item) {
                is JournalNote.Handwriting -> {
                    HandwritingPreview(
                        item.document,
                        Modifier.fillMaxWidth().height(240.dp),
                        unreadable =
                            item.unreadableContent != null,
                    )
                }

                is JournalNote.Text -> {
                    MarkdownText(
                        content = item.content,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.noteDragSource(item.content),
                    )
//                    Text(
//                        item.content,
//                        style = MaterialTheme.typography.bodyMedium.copy(
//                            lineBreak = LineBreak.Paragraph,
//                        ),
//                    )
                }

                else -> {
                    // Audio and image notes only appear in detail view
                    Text(
                        stringResource(Res.string.tap_to_view_content, item.type.name.lowercase()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun determineHeaderTitle(date: Instant): String {
    val localDateTime = date.toLocalDateTime(TimeZone.currentSystemDefault())
    val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

    return when (localDateTime.date) {
        // If the date is within this week, show "This Week"
        in today.date.minus(DatePeriod(days = 1))..today.date.plus(DatePeriod(days = 7)) -> {
            stringResource(Res.string.period_this_week)
        }

        // If the date is within the previous week, show "Last Week"
        in today.date.minus(DatePeriod(days = 7))..today.date -> {
            stringResource(Res.string.period_last_week)
        }

        // If the date is within the last month, show the number of weeks ago
        in today.date.minus(DatePeriod(days = 30))..today.date -> {
            val weeksAgo = (today.date.dayOfYear - localDateTime.date.dayOfYear) / 7
            pluralStringResource(Res.plurals.period_weeks_ago, weeksAgo, weeksAgo)
        }

        // If the date was more than five weeks ago, show the number of months ago
        in today.date.minus(DatePeriod(days = 365))..today.date -> {
            val monthsAgo = (today.date.dayOfYear - localDateTime.date.dayOfYear) / 30
            pluralStringResource(Res.plurals.period_months_ago, monthsAgo, monthsAgo)
        }

        // If the date was more than a year ago, show the number of years ago
        else -> {
            val yearsAgo = today.date.year - localDateTime.date.year
            stringResource(Res.string.period_years_ago, yearsAgo)
        }
    }
}

@Composable
private fun determineTitle(date: Instant): String {
    val localDateTime = date.toLocalDateTime(TimeZone.currentSystemDefault())
    val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    return when (localDateTime.date) {
        // If the date is today, show "Today"
        today.date -> {
            stringResource(Res.string.timeline_today)
        }

        // If the date is yesterday, show "Yesterday"
        today.date.minus(DatePeriod(days = 1)) -> {
            stringResource(Res.string.timeline_yesterday)
        }

        // If the date is within the last calendar week, show the day of the week
        in today.date.minus(DatePeriod(days = 7))..today.date -> {
            localDateTime.date.dayOfWeek.name
        }

        // Otherwise, show the date
        else -> {
            localDateTime.toReadableDateShort()
        }
    }
}

enum class ZoomLevel {
    /**
     * Group by day
     */
    DEFAULT,

    /**
     * Show every note under a separate header.
     *
     * If there are multiple entries under the same day, the date will only appear once, and each
     * item should have its own recorded time.
     */
    DETAILED,
}

/**
 * Converts a sequential list of [TimelineItem]s into a timeline view.
 * This creates a list of [TimelineContentItem]s and [TimelineContentHeader]s
 * to represent the timeline, dividing items by week.
 */
fun LazyListScope.constructTimeline(
    items: List<JournalNote>,
    onNewEntry: () -> Unit,
    onItemSelected: (uid: Uuid) -> Unit,
    onItemDeleted: (uid: Uuid) -> Unit,
    onOpenInNewWindow: (uid: Uuid) -> Unit = {},
    zoomLevel: ZoomLevel = ZoomLevel.DETAILED,
) {
    // Sort items in reverse order, grouping them by weeks before this week, adding headers for each week.
    val itemsGroupedByWeek =
        items.sortedByDescending { it.creationTimestamp }.groupBy {
            val weeksBeforeNow = it.creationTimestamp.weeksAgo()
            weeksBeforeNow
        }

    // Group items by day, sort in reverse order of created

    // Now we have a map of week number to list of items for that week.
    itemsGroupedByWeek.forEach { (_, items) ->
        item {
            TimelineContentHeader(determineHeaderTitle(date = items.first().creationTimestamp))
        }
        // If there is no entry from today, show the default new entry block
        if (!hasEntryFromToday(items)) {
            item {
                DefaultNewEntryBlock(
                    onNewEntry = onNewEntry,
                    message = stringResource(Res.string.no_notes_from_today),
                )
            }
        }
        items.forEachIndexed { _, item ->
            when (zoomLevel) {
                ZoomLevel.DETAILED -> {
                    item {
                        TimelineContentItem(
                            item,
                            metadata =
                                TimelineItemMetadata(
                                    notesRecorded = 1,
                                ),
                            onItemSelected = onItemSelected,
                            onDeleteItem = onItemDeleted,
                            onOpenInNewWindow = onOpenInNewWindow,
                        )
                    }
                }

                ZoomLevel.DEFAULT -> {
                }
            }
        }
    }
}
