@file:Suppress("ktlint:standard:function-naming")
@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.logdate.feature.core.settings.account.plan.cleanup

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.logdate.client.domain.storage.StoredItem
import app.logdate.client.domain.storage.StoredKind
import app.logdate.feature.core.settings.ui.formatByteSize
import app.logdate.ui.audio.formatAudioDuration
import app.logdate.ui.theme.Spacing
import app.logdate.util.formatDateLocalized
import coil3.compose.AsyncImage
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.storage_cleanup_detail
import logdate.client.feature.core.generated.resources.storage_cleanup_item_description
import logdate.client.feature.core.generated.resources.storage_cleanup_size_unknown
import org.jetbrains.compose.resources.stringResource

/**
 * A photo or video: its picture, its size in the corner, and a mark that fills in when it is
 * chosen. Choosing it also draws the picture in from its frame.
 */
@Composable
internal fun PictureTile(
    item: StoredItem,
    selected: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    thumbnail: @Composable (StoredItem, Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    // The spring overshoots, and neither a padding nor a corner can be less than nothing.
    val inset by animateDpAsState(if (selected) Spacing.md else 0.dp, motion.fastSpatialSpec())
    val corner by animateDpAsState(if (selected) SELECTED_CORNER else RESTING_CORNER, motion.fastSpatialSpec())
    val pictureInset = inset.coerceAtLeast(0.dp)
    val pictureCorner = corner.coerceAtLeast(0.dp)
    val frame by animateColorAsState(if (selected) colors.secondaryContainer else colors.surfaceContainerHigh, motion.fastEffectsSpec())
    val description = itemDescription(item)
    Box(
        modifier =
            modifier
                .aspectRatio(1f)
                .clip(RoundedCornerShape(RESTING_CORNER))
                .background(frame)
                .toggleable(value = selected, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
                .semantics { contentDescription = description }
                .testTag(storageCleanupItemTag(item.id)),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(pictureInset)
                    .clip(RoundedCornerShape(pictureCorner))
                    .background(colors.surfaceContainerHighest),
        ) {
            // Shows while the picture loads, and stays when its file is not on this device.
            Icon(item.kind.icon(), contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(PLACEHOLDER_ICON))
            thumbnail(item, Modifier.fillMaxSize())
            val label =
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(Spacing.xs)
                    .clip(CircleShape)
                    .background(colors.scrim.copy(alpha = LABEL_SCRIM))
                    .padding(horizontal = Spacing.sm, vertical = LABEL_PADDING)
            if (item.sizeBytes == null) {
                // A corner has no room for the words; the tile's description says them.
                Icon(Icons.Rounded.Cloud, contentDescription = null, tint = Color.White, modifier = label.size(LABEL_ICON))
            } else {
                Text(
                    text = sizeAndLength(item),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = label.clearAndSetSemantics { },
                )
            }
        }
        SelectionMark(selected = selected, modifier = Modifier.align(Alignment.TopEnd).padding(Spacing.sm))
    }
}

/** A ring over a picture that fills in with a tick once the picture is chosen. */
@Composable
private fun SelectionMark(
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val fill by animateColorAsState(
        if (selected) colors.primary else colors.scrim.copy(alpha = RING_SCRIM),
        MaterialTheme.motionScheme.fastEffectsSpec(),
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .size(MARK_SIZE)
                .clip(CircleShape)
                .background(fill)
                .then(if (selected) Modifier else Modifier.border(RING_WIDTH, Color.White.copy(alpha = RING_ALPHA), CircleShape)),
    ) {
        AnimatedVisibility(
            visible = selected,
            enter = scaleIn(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeIn(),
            exit = scaleOut(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeOut(),
        ) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(MARK_ICON))
        }
    }
}

/**
 * A recording or written entry: what it starts with, when it is from, and its size. Its shape
 * turns into a tick when it is chosen.
 */
@Composable
internal fun ItemRow(
    item: StoredItem,
    selected: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val container by animateColorAsState(if (selected) colors.secondaryContainer else colors.surfaceContainerLow, motion.fastEffectsSpec())
    val ink by animateColorAsState(if (selected) colors.onSecondaryContainer else colors.onSurface, motion.fastEffectsSpec())
    val date = dateText(item)
    val title = item.text ?: date
    // Under the title: when it is from, how long it runs, and that its file is elsewhere.
    val details =
        listOfNotNull(
            date.takeIf { item.text != null },
            item.durationMs?.let { formatAudioDuration(it) },
            stringResource(Res.string.storage_cleanup_size_unknown).takeIf { item.sizeBytes == null },
        )
    val supporting =
        details.drop(1).fold(details.firstOrNull()) { line, detail ->
            stringResource(Res.string.storage_cleanup_detail, line.orEmpty(), detail)
        }
    val description = itemDescription(item)
    Surface(
        shape = MaterialTheme.shapes.large,
        color = container,
        contentColor = ink,
        modifier =
            modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .toggleable(value = selected, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
                .semantics { contentDescription = description }
                .testTag(storageCleanupItemTag(item.id)),
    ) {
        Row(
            modifier = Modifier.heightIn(min = ROW_MIN_HEIGHT).padding(horizontal = Spacing.lg, vertical = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KindMark(kind = item.kind, selected = selected)
            Column(modifier = Modifier.weight(1f).clearAndSetSemantics { }) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (supporting != null) {
                    Text(
                        supporting,
                        style = MaterialTheme.typography.bodyMedium,
                        color = ink.copy(alpha = CLEANUP_QUIET_INK),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = LABEL_PADDING),
                    )
                }
            }
            item.sizeBytes?.let { size ->
                Text(
                    formatByteSize(size),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    modifier = Modifier.clearAndSetSemantics { },
                )
            }
        }
    }
}

/** The kind's own shape with its icon; chosen, the same shape in the action colour with a tick. */
@Composable
private fun KindMark(
    kind: StoredKind,
    selected: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val fill by animateColorAsState(
        if (selected) colors.primary else colors.surfaceContainerHighest,
        MaterialTheme.motionScheme.fastEffectsSpec(),
    )
    val swap = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(KIND_MARK_SIZE).clip(kind.shape()).background(fill)) {
        AnimatedContent(
            targetState = selected,
            transitionSpec = { (scaleIn(swap) + fadeIn()) togetherWith (scaleOut(swap) + fadeOut()) },
        ) { chosen ->
            Icon(
                imageVector = if (chosen) Icons.Rounded.Check else kind.icon(),
                contentDescription = null,
                tint = if (chosen) colors.onPrimary else colors.onSurfaceVariant,
                modifier = Modifier.size(KIND_MARK_ICON),
            )
        }
    }
}

/** The picture for a photo or video, read the way the Library reads it. */
@Composable
internal fun StoredThumbnail(
    item: StoredItem,
    modifier: Modifier = Modifier,
) {
    AsyncImage(model = item.mediaRef, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
}

@Composable
private fun dateText(item: StoredItem): String =
    remember(item.createdAt) { formatDateLocalized(item.createdAt.toLocalDateTime(TimeZone.currentSystemDefault()).date) }

@Composable
private fun sizeText(item: StoredItem): String =
    item.sizeBytes?.let { formatByteSize(it) } ?: stringResource(Res.string.storage_cleanup_size_unknown)

/** A picture's corner label: its size, and for a video how long it runs. */
@Composable
private fun sizeAndLength(item: StoredItem): String {
    val size = sizeText(item)
    if (item.sizeBytes == null) return size
    val length = item.durationMs?.let { formatAudioDuration(it) } ?: return size
    return stringResource(Res.string.storage_cleanup_detail, size, length)
}

/** What a screen reader says for one thing: what it starts with or when it is from, and how large it is. */
@Composable
private fun itemDescription(item: StoredItem): String =
    stringResource(
        Res.string.storage_cleanup_item_description,
        item.text ?: dateText(item),
        if (item.mediaRef != null && item.text == null) sizeAndLength(item) else sizeText(item),
    )

/** How strong secondary text is against the ink of its surface. */
internal const val CLEANUP_QUIET_INK = 0.7f

// Room for a title of two lines, so rows side by side are the same height.
private val ROW_MIN_HEIGHT = 96.dp
private val RESTING_CORNER = 12.dp
private val SELECTED_CORNER = 20.dp
private val PLACEHOLDER_ICON = 28.dp
private val MARK_SIZE = 24.dp
private val MARK_ICON = 16.dp
private val RING_WIDTH = 2.dp
private val LABEL_PADDING = 2.dp
private val LABEL_ICON = 16.dp
private val KIND_MARK_SIZE = 40.dp
private val KIND_MARK_ICON = 20.dp
private const val LABEL_SCRIM = 0.6f
private const val RING_SCRIM = 0.2f
private const val RING_ALPHA = 0.9f
