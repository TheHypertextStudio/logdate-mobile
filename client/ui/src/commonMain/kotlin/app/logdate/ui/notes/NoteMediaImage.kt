@file:Suppress("ktlint:standard:function-naming")

package app.logdate.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.logdate.ui.theme.Spacing
import coil3.compose.AsyncImage
import io.github.aakira.napier.Napier
import logdate.client.ui.generated.resources.Res
import logdate.client.ui.generated.resources.note_block_loading
import logdate.client.ui.generated.resources.note_block_photo_unavailable
import logdate.client.ui.generated.resources.note_block_video_unavailable
import org.jetbrains.compose.resources.stringResource

/** What a stored media reference holds. A video renders as a frame from the clip. */
enum class NoteMediaKind { Photo, Video }

/** Where a media block is in loading its pixels. */
enum class NoteMediaLoadState { Loading, Ready, Unavailable }

/** Test tags for [NoteMediaImage]'s states, so tests can tell a loaded block from an empty one. */
object NoteMediaTags {
    const val LOADING = "note_media_loading"
    const val READY = "note_media_ready"
    const val UNAVAILABLE = "note_media_unavailable"
}

/**
 * The pixels of a photo or video note, wherever a note shows its media.
 *
 * Every state is a real one: a tonal surface the size of the media while it loads, the photo or
 * the video's frame once it has, and a stated failure when the file can't be read. A blank
 * reference is a failure, never an empty box. [modifier] sets the size; the image fills it.
 *
 * @param compact Drop the failure sentence for small tiles; the icon carries it as its
 *   accessible description instead.
 * @param showVideoBadge Draw the play mark over a video's frame. Callers that draw their own
 *   video badge turn it off.
 */
@Composable
fun NoteMediaImage(
    mediaRef: String,
    kind: NoteMediaKind,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    compact: Boolean = false,
    showVideoBadge: Boolean = kind == NoteMediaKind.Video,
    onLoaded: (Painter) -> Unit = {},
) {
    // Where this platform can't decode a frame from a clip, a video is shown as a video (its
    // surface and play mark) rather than reported as a file that can't be read.
    val framesUnavailable = kind == NoteMediaKind.Video && !platformDecodesVideoFrames
    var state by remember(mediaRef) {
        mutableStateOf(
            when {
                mediaRef.isBlank() -> NoteMediaLoadState.Unavailable
                framesUnavailable -> NoteMediaLoadState.Ready
                else -> NoteMediaLoadState.Loading
            },
        )
    }
    val loadingLabel = stringResource(Res.string.note_block_loading)
    // The tonal surface stands in for the media until it loads; once it has, letterboxed or
    // transparent media sits on whatever surface holds it.
    val surface =
        if (state == NoteMediaLoadState.Ready && !framesUnavailable) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHigh
    Box(
        modifier
            .background(surface)
            .testTag(
                when (state) {
                    NoteMediaLoadState.Loading -> NoteMediaTags.LOADING
                    NoteMediaLoadState.Ready -> NoteMediaTags.READY
                    NoteMediaLoadState.Unavailable -> NoteMediaTags.UNAVAILABLE
                },
            ).then(
                when {
                    state == NoteMediaLoadState.Loading -> Modifier.semantics { this.contentDescription = loadingLabel }
                    framesUnavailable && contentDescription != null -> Modifier.semantics { this.contentDescription = contentDescription }
                    else -> Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (mediaRef.isNotBlank() && !framesUnavailable) {
            AsyncImage(
                model = mediaRef,
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier.matchParentSize(),
                onLoading = { state = NoteMediaLoadState.Loading },
                onSuccess = { success ->
                    state = NoteMediaLoadState.Ready
                    onLoaded(success.painter)
                },
                onError = { error ->
                    Napier.w(error.result.throwable) { "Couldn't load note media" }
                    state = NoteMediaLoadState.Unavailable
                },
            )
        }
        when {
            state == NoteMediaLoadState.Unavailable -> NoteMediaUnavailable(kind, compact)
            kind == NoteMediaKind.Video && showVideoBadge -> VideoPlayMark(compact)
        }
    }
}

/**
 * A photo or video frame at its own proportions across the available width, bounded by
 * [maxHeight] so a tall portrait can't fill the page. Until the media loads it holds
 * [initialRatio], so the loading surface has the media's likely size rather than collapsing.
 */
@Composable
fun ProportionalNoteMedia(
    mediaRef: String,
    kind: NoteMediaKind,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 400.dp,
    contentScale: ContentScale = ContentScale.Crop,
    initialRatio: Float = DEFAULT_MEDIA_RATIO,
) {
    var ratio by remember(mediaRef) { mutableFloatStateOf(initialRatio) }
    NoteMediaImage(
        mediaRef = mediaRef,
        kind = kind,
        contentDescription = contentDescription,
        modifier = modifier.fillMaxWidth().heightIn(max = maxHeight).aspectRatio(ratio),
        contentScale = contentScale,
        onLoaded = { painter -> mediaAspectRatio(painter.intrinsicSize)?.let { ratio = it } },
    )
}

/** The width-to-height ratio of [size], kept between portrait 3:4 and panoramic 2:1; null when unknown. */
internal fun mediaAspectRatio(size: Size): Float? {
    val ratio = size.width / size.height
    return if (ratio.isFinite() && ratio > 0f) ratio.coerceIn(MIN_MEDIA_RATIO, MAX_MEDIA_RATIO) else null
}

/** Whether Coil on this platform can decode a frame from a video clip. */
internal expect val platformDecodesVideoFrames: Boolean

private const val DEFAULT_MEDIA_RATIO = 4f / 3f
private const val MIN_MEDIA_RATIO = 3f / 4f
private const val MAX_MEDIA_RATIO = 2f

@Composable
private fun NoteMediaUnavailable(
    kind: NoteMediaKind,
    compact: Boolean,
) {
    val message =
        stringResource(
            if (kind == NoteMediaKind.Video) Res.string.note_block_video_unavailable else Res.string.note_block_photo_unavailable,
        )
    val icon = if (kind == NoteMediaKind.Video) Icons.Outlined.VideocamOff else Icons.Outlined.BrokenImage
    if (compact) {
        Icon(icon, contentDescription = message, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Column(
        Modifier.padding(Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun VideoPlayMark(compact: Boolean) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.84f)) {
        Icon(
            Icons.Default.PlayArrow,
            contentDescription = null,
            modifier = Modifier.padding(if (compact) 2.dp else Spacing.sm).size(if (compact) 16.dp else 28.dp),
        )
    }
}
