@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.rewind.ui.detail

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.logdate.ui.common.parseMarkdownDocument
import coil3.compose.AsyncImage
import logdate.client.feature.rewind.generated.resources.Res
import logdate.client.feature.rewind.generated.resources.cd_rewind_journal_photo
import logdate.client.feature.rewind.generated.resources.cd_rewind_video_frame
import logdate.client.feature.rewind.generated.resources.rewind_video_frame_label
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * Panel for displaying an image note within a rewind story.
 *
 * This panel showcases a captured image from the user's journal with
 * optional caption and date context. Designed for visual memories and moments.
 *
 * ## Visual Design:
 * - **Image Focus**: Bounded media preserves its proportions inside the full scene
 * - **Caption**: Leading text beneath the media shares its width
 * - **Date Context**: Clear indication of when the image was captured
 *
 * @param imageUri URI of the image to display
 * @param caption Optional text caption for the image
 * @param dateFormatted Formatted date string (e.g., "Tuesday, Nov 19")
 * @param modifier Modifier for customizing the panel container
 */
@Composable
fun ImageNotePanel(
    imageUri: String,
    caption: String?,
    dateFormatted: String,
    isVideoFrame: Boolean = false,
    modifier: Modifier = Modifier,
    memoryId: String = "image-memory",
) {
    var loadState by remember(imageUri) { mutableStateOf<RewindMemoryLoadState>(RewindMemoryLoadState.Loading) }
    val previewPainter = if (LocalInspectionMode.current) LocalRewindPreviewPainters.current[imageUri] else null
    val displayedLoadState = if (previewPainter != null) RewindMemoryLoadState.Ready else loadState
    // Stored Rewind media has no dimensions. Keep its 3:2 viewport stable while Fit preserves the decoded bitmap's aspect.
    val preferredAspectRatio = 1.5f
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val captionStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 23.sp)
    val plainCaption = remember(caption) { caption?.let { parseMarkdownDocument(it).plainText }.orEmpty() }
    val headerStyle = MaterialTheme.typography.labelLarge
    val headerHeight = with(density) { textMeasurer.measure(dateFormatted, headerStyle, maxLines = 1).size.height + 12.dp.toPx() }
    RewindMemoryStage(modifier) { availableHeight ->
        BoxWithConstraints {
            val layout =
                with(density) {
                    resolveRewindMediaLayout(
                        minOf(maxWidth, RewindMediaMaxWidth).toPx(),
                        availableHeight.toPx(),
                        preferredAspectRatio,
                        headerHeight,
                        RewindMinimumReadableWidth.toPx(),
                        !caption.isNullOrBlank(),
                    ) { width ->
                        textMeasurer
                            .measure(
                                plainCaption,
                                captionStyle,
                                constraints = Constraints(maxWidth = (width - 48.dp.toPx()).roundToInt().coerceAtLeast(1)),
                            ).size.height +
                            24.dp.toPx()
                    }
                }
            RewindMemoryFrame(
                memoryId,
                displayedLoadState,
                with(density) { Modifier.size(layout.width.toDp(), layout.height.toDp()) },
                shape = RoundedCornerShape(if (caption.isNullOrBlank()) 24.dp else 8.dp),
            ) {
                Column(Modifier.fillMaxWidth().testTag("rewind-media-content")) {
                    Row(
                        with(density) { Modifier.fillMaxWidth().height(layout.headerHeight.toDp()) }.padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            dateFormatted,
                            Modifier.weight(1f),
                            style = headerStyle,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (isVideoFrame) {
                            Text(
                                stringResource(Res.string.rewind_video_frame_label),
                                style = headerStyle,
                                color = Color.White,
                                maxLines = 1,
                            )
                        }
                    }
                    val description =
                        if (isVideoFrame) {
                            stringResource(Res.string.cd_rewind_video_frame, dateFormatted)
                        } else {
                            caption?.takeIf { it.isNotBlank() }
                                ?: stringResource(Res.string.cd_rewind_journal_photo, dateFormatted)
                        }
                    val imageModifier =
                        with(density) { Modifier.fillMaxWidth().height(layout.mediaHeight.toDp()).testTag("rewind_media_$memoryId") }
                    if (previewPainter != null) {
                        Image(
                            painter = previewPainter,
                            contentDescription = description,
                            modifier = imageModifier,
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        AsyncImage(
                            model = imageUri,
                            contentDescription = description,
                            modifier = imageModifier,
                            contentScale = ContentScale.Fit,
                            onLoading = { loadState = RewindMemoryLoadState.Loading },
                            onError = { loadState = RewindMemoryLoadState.Unavailable },
                            onSuccess = { loadState = RewindMemoryLoadState.Ready },
                        )
                    }
                    if (!caption.isNullOrBlank()) {
                        Column(
                            with(density) {
                                Modifier.fillMaxWidth().height(layout.captionHeight.toDp())
                            }.verticalScroll(rememberScrollState()).padding(
                                horizontal = RewindMemoryContentPadding,
                                vertical = minOf(12.dp, with(density) { layout.captionHeight.toDp() } / 6),
                            ),
                        ) {
                            RewindRichMemoryText(content = caption, textStyle = captionStyle)
                        }
                    }
                }
            }
        }
    }
}
