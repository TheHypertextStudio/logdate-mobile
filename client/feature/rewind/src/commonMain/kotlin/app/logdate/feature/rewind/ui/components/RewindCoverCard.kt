@file:Suppress("ktlint:standard:function-naming", "ktlint:standard:no-wildcard-imports")

package app.logdate.feature.rewind.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.logdate.feature.rewind.ui.overview.RewindPreviewUiState
import app.logdate.shared.model.ActivityType
import app.logdate.ui.platform.PlatformIcons
import app.logdate.ui.theme.Spacing
import app.logdate.util.formatDateLocalized
import app.logdate.util.toReadableDateShort
import logdate.client.feature.rewind.generated.resources.*
import logdate.client.feature.rewind.generated.resources.Res
import org.jetbrains.compose.resources.stringResource

/**
 * Content layout for a rewind card with dates at the top and title at the bottom.
 *
 * Three visual states:
 * - **Unviewed**: Bold title, primary label, and a small "NEW" pill badge
 * - **Viewed**: Bold title, primary label, no badge
 * - **Pending**: Medium-weight title, muted label with a status dot
 *
 * Available cards render over a photo or accent-gradient background (drawn by the
 * caller, [app.logdate.feature.rewind.ui.FloatingRewindCard]), so their text uses a
 * white palette; pending cards have no background art and keep the neutral theme
 * palette.
 *
 * @param rewind The rewind data to display
 * @param modifier Modifier for customizing the content container
 */
@Composable
fun RewindCoverCard(
    rewind: RewindPreviewUiState,
    modifier: Modifier = Modifier,
) {
    val isNew = rewind.rewindAvailable && !rewind.isViewed
    val onBackground = if (rewind.rewindAvailable) Color.White else MaterialTheme.colorScheme.onSurfaceVariant

    val typographicCover = rewind.rewindAvailable && rewind.heroImageUri == null
    Column(modifier, verticalArrangement = if (typographicCover) Arrangement.spacedBy(Spacing.xl) else Arrangement.SpaceBetween) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            RewindCoverDates(rewind, onBackground)
            if (rewind.milestone != null) {
                MilestoneBadge(summary = rewind.milestone.summary)
            } else if (isNew) {
                NewRewindBadge()
            }
        }
        if (typographicCover) Spacer(Modifier.height(Spacing.xxxl))
        RewindCoverStory(rewind, onBackground)
    }
}

@Composable
private fun RewindCoverDates(
    rewind: RewindPreviewUiState,
    onBackground: Color,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        modifier = modifier,
    ) {
        val dateColor = onBackground.copy(alpha = if (rewind.rewindAvailable) 0.85f else 1f)
        val dominantActivity = rewind.dominantActivity
        if (rewind.rewindAvailable && dominantActivity != null) {
            Icon(
                painter = activityTypeIcon(dominantActivity),
                contentDescription = null,
                tint = dateColor,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = "${rewind.start.toReadableDateShort()} – ${formatDateLocalized(rewind.end)}",
            style = MaterialTheme.typography.bodyMedium,
            color = dateColor,
        )
    }
}

@Composable
private fun RewindCoverStory(
    rewind: RewindPreviewUiState,
    onBackground: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
        modifier = modifier,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = rewind.label,
                style = MaterialTheme.typography.labelLarge,
                color = if (rewind.rewindAvailable) onBackground else MaterialTheme.colorScheme.outline,
            )
            if (!rewind.rewindAvailable) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(6.dp),
                ) {}
            }
        }

        Text(
            text = rewind.title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = if (rewind.rewindAvailable) FontWeight.Bold else FontWeight.Medium,
            color = onBackground,
        )

        if (rewind.rewindAvailable && hasMetadata(rewind)) {
            RewindStatChipsRow(rewind = rewind, color = onBackground.copy(alpha = 0.85f))
        }

        val quote = rewind.highlightedQuote
        Text(
            text = if (quote != null) "“$quote”" else rewind.message,
            style = MaterialTheme.typography.bodyLarge,
            fontStyle = if (quote != null) FontStyle.Italic else FontStyle.Normal,
            color = onBackground.copy(alpha = if (rewind.rewindAvailable) 0.9f else 1f),
        )
    }
}

/**
 * Maps a rewind's dominant activity to the same icon language used for the
 * accent-color fallback, so the badge and the color always agree.
 */
@Composable
private fun activityTypeIcon(activityType: ActivityType): Painter =
    when (activityType) {
        ActivityType.TRAVEL -> PlatformIcons.travel()
        ActivityType.SOCIAL -> PlatformIcons.people()
        ActivityType.FOCUSED_WORK -> PlatformIcons.focusedWork()
        ActivityType.QUIET -> PlatformIcons.quiet()
        ActivityType.MILESTONE -> PlatformIcons.milestone()
        ActivityType.MIXED -> PlatformIcons.mixed()
    }

/**
 * Returns true when the rewind has at least one piece of metadata worth rendering
 * as a chip (entries, photos, audio notes, people, or a primary location).
 *
 * When false, the chips row is omitted entirely to avoid a distracting empty slot.
 */
private fun hasMetadata(rewind: RewindPreviewUiState): Boolean =
    rewind.entryCount > 0 ||
        rewind.photoCount > 0 ||
        rewind.audioCount > 0 ||
        rewind.peopleCount > 0 ||
        rewind.primaryLocation != null

/**
 * A row of icon+count indicators showing at-a-glance metadata for a rewind.
 */
@Composable
private fun RewindStatChipsRow(
    rewind: RewindPreviewUiState,
    color: Color,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        modifier = modifier,
    ) {
        if (rewind.entryCount > 0) {
            RewindStatChip(label = "${rewind.entryCount}", color = color) {
                Icon(painter = PlatformIcons.note(), contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
        if (rewind.photoCount > 0) {
            RewindStatChip(label = "${rewind.photoCount}", color = color) {
                Icon(painter = PlatformIcons.camera(), contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
        if (rewind.audioCount > 0) {
            RewindStatChip(label = "${rewind.audioCount}", color = color) {
                Icon(painter = PlatformIcons.mic(), contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
        if (rewind.peopleCount > 0) {
            RewindStatChip(label = "${rewind.peopleCount}", color = color) {
                Icon(painter = PlatformIcons.people(), contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
        if (rewind.primaryLocation != null) {
            RewindStatChip(label = rewind.primaryLocation, color = color) {
                Icon(painter = PlatformIcons.location(), contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/**
 * A single icon+label pair rendered inline for the metadata chip row.
 *
 * Deliberately unbordered — this is a glance-level affordance, not a tap target. For
 * a standalone, bordered chip see [app.logdate.ui.common.MetadataChip].
 */
@Composable
private fun RewindStatChip(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides color,
        ) {
            icon()
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}

/**
 * A small pill badge that signals an unviewed rewind.
 *
 * Uses the tertiary color family to stand out against the card's photo/gradient
 * background while staying within the Material 3 palette.
 */
@Composable
private fun NewRewindBadge(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.tertiary,
        contentColor = MaterialTheme.colorScheme.onTertiary,
        shape = RoundedCornerShape(50),
        modifier = modifier,
    ) {
        Text(
            text = stringResource(Res.string.badge_new),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/**
 * A pill badge marking a milestone rewind — the "special pull" of the set. Takes
 * priority over [NewRewindBadge] in the same corner slot when both would apply.
 */
@Composable
private fun MilestoneBadge(
    summary: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = Color(0xFFE8C547), // honey gold — matches the milestone card border
        contentColor = Color(0xFF3D2E00),
        shape = RoundedCornerShape(50),
        modifier = modifier,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Icon(painter = PlatformIcons.milestone(), contentDescription = null, modifier = Modifier.size(14.dp))
            Text(
                text = summary,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
