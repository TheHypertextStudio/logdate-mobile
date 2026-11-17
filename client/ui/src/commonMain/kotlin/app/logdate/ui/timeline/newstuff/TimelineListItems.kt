@file:OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@file:Suppress("ktlint:standard:function-naming")

package app.logdate.ui.timeline.newstuff

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.logdate.ui.common.formatting.LocalToday
import app.logdate.ui.common.formatting.asRelativeDate
import app.logdate.ui.notes.NoteMediaImage
import app.logdate.ui.notes.NoteMediaKind
import app.logdate.ui.theme.Spacing
import app.logdate.ui.timeline.INITIAL_DAY_ENTRY_COUNT
import app.logdate.ui.timeline.TimelineDayUiState
import app.logdate.ui.timeline.TimelineMediaItemUiState
import app.logdate.ui.timeline.entryCount
import app.logdate.ui.timeline.entryPreview
import app.logdate.util.now
import kotlinx.datetime.LocalDate
import logdate.client.ui.generated.resources.Res
import logdate.client.ui.generated.resources.a_long_time_passed
import logdate.client.ui.generated.resources.add_your_birthday_in_settings_to_see_something_special_here
import logdate.client.ui.generated.resources.cd_timeline_photo
import logdate.client.ui.generated.resources.cd_timeline_video
import logdate.client.ui.generated.resources.congrats_curious_explorer
import logdate.client.ui.generated.resources.happy_birthday
import logdate.client.ui.generated.resources.journey_days_count
import logdate.client.ui.generated.resources.timeline_media_badge_clip
import logdate.client.ui.generated.resources.timeline_show_fewer_entries
import logdate.client.ui.generated.resources.timeline_show_more_entries
import logdate.client.ui.generated.resources.youve_reached_the_end
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid

@Composable
internal fun TimeGapMessageItem(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(Res.string.a_long_time_passed),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            modifier
                .padding(horizontal = Spacing.lg)
                .padding(start = Spacing.lg),
    )
}

@Composable
internal fun EndOfTimelineItem(
    state: EndOfTimelineUiState,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        modifier =
            modifier
                .defaultMinSize(minWidth = 320.dp)
                .padding(horizontal = Spacing.lg, vertical = Spacing.xl),
    ) {
        when (state) {
            is EndOfTimelineUiState.BirthdayCelebration -> {
                Text(
                    text = state.birthDate.asRelativeDate(LocalToday.current),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(Res.string.happy_birthday),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    text = stringResource(Res.string.journey_days_count, state.daysSinceBirth),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            EndOfTimelineUiState.DiscoveryEasterEgg -> {
                Text(
                    text = stringResource(Res.string.youve_reached_the_end),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(Res.string.congrats_curious_explorer),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    text = stringResource(Res.string.add_your_birthday_in_settings_to_see_something_special_here),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun BirthdayListItem(
    birthDate: LocalDate,
    modifier: Modifier = Modifier,
) {
    val daysSinceBirthday by remember(birthDate) {
        derivedStateOf {
            (LocalDate.now().toEpochDays() - birthDate.toEpochDays()).toInt()
        }
    }

    EndOfTimelineItem(
        state =
            EndOfTimelineUiState.BirthdayCelebration(
                birthDate = birthDate,
                daysSinceBirth = daysSinceBirthday,
            ),
        modifier = modifier,
    )
}

@Composable
internal fun TimelineDayListItem(
    item: TimelineDayUiState,
    layoutMode: TimelineDayLayoutMode,
    pendingMomentContent: @Composable (app.logdate.client.repository.moments.MarkedMoment) -> Unit,
    onOpenDay: (LocalDate) -> Unit,
    onOpenTextNote: (Uuid) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(item.date.toString()) { mutableStateOf(false) }
    val today = LocalToday.current
    val preview = remember(item, expanded, today) { item.entryPreview(expanded || item.date != today) }
    val remaining = if (item.date == today) item.entryCount() - INITIAL_DAY_ENTRY_COUNT else 0
    Column(
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
    ) {
        item.supportingSummary?.let { summary ->
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clickable { onOpenDay(item.date) },
            )
        }
        SemanticTimelineDayContent(
            item = preview,
            style = item.layout.style(),
            layoutMode = layoutMode,
            pendingMomentContent = pendingMomentContent,
            onOpenTextNote = onOpenTextNote,
        )
        if (remaining > 0) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(
                    if (expanded) {
                        stringResource(
                            Res.string.timeline_show_fewer_entries,
                        )
                    } else {
                        pluralStringResource(Res.plurals.timeline_show_more_entries, remaining, remaining)
                    },
                )
            }
        }
    }
}

/**
 * The day's date, pinned while that day's content is on screen.
 *
 * This replaces the old left rail, which reserved up to 88dp of every row to show a day number
 * beside a header that already named the same date, and whose connecting spine never drew: it
 * sized itself with [fillMaxHeight] inside a row whose height constraint is unbounded.
 */
@Composable
internal fun TimelineDayHeaderBar(
    item: TimelineDayUiState,
    modifier: Modifier = Modifier,
    onOpenDay: (LocalDate) -> Unit = {},
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth().clickable { onOpenDay(item.date) },
    ) {
        Text(
            text = item.date.asRelativeDate(LocalToday.current),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = item.layout.style().accentColor,
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        )
    }
}

@Composable
fun TimelineMediaTile(
    media: TimelineMediaItemUiState,
    aspectRatio: Float,
    modifier: Modifier = Modifier,
    previewPainter: Painter? = null,
    noteId: Uuid? = null,
    onOpenNote: (Uuid) -> Unit = {},
) {
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .aspectRatio(aspectRatio)
                .then(if (noteId == null) Modifier else Modifier.clickable { onOpenNote(noteId) }),
    ) {
        val description = stringResource(if (media.isVideo) Res.string.cd_timeline_video else Res.string.cd_timeline_photo)
        if (previewPainter != null) {
            Image(
                painter = previewPainter,
                contentDescription = description,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            NoteMediaImage(
                mediaRef = media.uri,
                kind = if (media.isVideo) NoteMediaKind.Video else NoteMediaKind.Photo,
                contentDescription = description,
                modifier = Modifier.fillMaxSize(),
                showVideoBadge = false,
            )
        }
        if (media.isVideo) {
            MediaBadge(
                icon = Icons.Default.PlayArrow,
                text = stringResource(Res.string.timeline_media_badge_clip),
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(Spacing.sm),
            )
        }
    }
}

@Composable
internal fun MediaBadge(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.84f),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
