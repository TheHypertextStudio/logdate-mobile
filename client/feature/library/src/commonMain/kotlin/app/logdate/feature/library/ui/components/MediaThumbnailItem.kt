@file:OptIn(ExperimentalSharedTransitionApi::class)

package app.logdate.feature.library.ui.components

import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import app.logdate.feature.library.ui.LibraryMediaItem
import app.logdate.ui.LocalNavAnimatedVisibilityScope
import app.logdate.ui.LocalSharedTransitionScope
import app.logdate.ui.common.ContextMenuArea
import app.logdate.ui.common.ContextMenuItem
import app.logdate.ui.common.focusableWithRing
import app.logdate.ui.common.mediaDragSource
import app.logdate.ui.common.transitions.TransitionKeys
import app.logdate.ui.notes.NoteAudioThumbnail
import app.logdate.ui.notes.NoteMediaImage
import app.logdate.ui.notes.NoteMediaKind
import app.logdate.ui.platform.PlatformIcons
import app.logdate.util.formatDateLocalized
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import logdate.client.feature.library.generated.resources.Res
import logdate.client.feature.library.generated.resources.cd_library_audio
import logdate.client.feature.library.generated.resources.cd_library_photo
import logdate.client.feature.library.generated.resources.cd_library_video
import logdate.client.feature.library.generated.resources.cd_library_video_badge
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid

/**
 * Bounds transform for the thumbnail-to-detail transition.
 * Uses a smooth ease-in-out curve for a polished feel.
 */
val MediaBoundsTransform =
    BoundsTransform { _, _ ->
        tween(durationMillis = 400, easing = FastOutSlowInEasing)
    }

private val ThumbnailShape = RoundedCornerShape(4.dp)

/**
 * A single thumbnail in the media grid, displaying an image preview with a video badge overlay
 * when the item is a video. Participates in shared element transitions with the detail view.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun MediaThumbnailItem(
    item: LibraryMediaItem,
    onItemClick: (Uuid) -> Unit,
    contextMenuItems: List<ContextMenuItem> = emptyList(),
    isSelected: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalNavAnimatedVisibilityScope.current

    val sharedModifier =
        if (sharedTransitionScope != null && animatedVisibilityScope != null) {
            with(sharedTransitionScope) {
                Modifier.sharedBounds(
                    rememberSharedContentState(key = "${TransitionKeys.LIBRARY_MEDIA_TRANSITION}-${item.uid}"),
                    animatedVisibilityScope,
                    boundsTransform = MediaBoundsTransform,
                    clipInOverlayDuringTransition =
                        OverlayClip(ThumbnailShape),
                    resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                )
            }
        } else {
            Modifier
        }

    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val scale by animateFloatAsState(
        targetValue = if (isHovered) 1.05f else 1f,
        label = "ThumbnailHoverScale",
    )

    // Long-press drags the media to another app, so the menu opens on right-click only.
    ContextMenuArea(items = contextMenuItems, openOnLongPress = false) {
        Box(
            modifier =
                modifier
                    .aspectRatio(1f)
                    .graphicsLayer(scaleX = scale, scaleY = scale)
                    .then(sharedModifier)
                    .clip(ThumbnailShape)
                    .then(
                        if (isSelected) {
                            Modifier.border(3.dp, MaterialTheme.colorScheme.primary, ThumbnailShape)
                        } else {
                            Modifier
                        },
                    ).hoverable(interactionSource)
                    .focusableWithRing()
                    .clickable { onItemClick(item.uid) }
                    // Inside clickable, so the drag sees the press before the click takes it and a
                    // long press can pick the media up.
                    .mediaDragSource(item.uri),
        ) {
            val capturedAt =
                remember(item.timestamp) {
                    formatDateLocalized(item.timestamp.toLocalDateTime(TimeZone.currentSystemDefault()).date)
                }
            val description =
                stringResource(
                    when {
                        item.isAudio -> Res.string.cd_library_audio
                        item.isVideo -> Res.string.cd_library_video
                        else -> Res.string.cd_library_photo
                    },
                    capturedAt,
                )
            if (item.isAudio) {
                // A recording shows its own waveform and length, not a stock audio-file icon.
                NoteAudioThumbnail(
                    mediaRef = item.uri,
                    durationMs = item.durationMs,
                    modifier = Modifier.fillMaxSize(),
                    contentDescription = description,
                )
            } else {
                NoteMediaImage(
                    mediaRef = item.thumbnailUri ?: item.uri,
                    kind = if (item.isVideo) NoteMediaKind.Video else NoteMediaKind.Photo,
                    contentDescription = description,
                    modifier = Modifier.fillMaxSize(),
                    compact = true,
                    showVideoBadge = false,
                )
            }
            if (item.isVideo) {
                Icon(
                    painter = PlatformIcons.playCircle(),
                    contentDescription = stringResource(Res.string.cd_library_video_badge),
                    tint = Color.White,
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .size(20.dp),
                )
            }
        }
    }
}
