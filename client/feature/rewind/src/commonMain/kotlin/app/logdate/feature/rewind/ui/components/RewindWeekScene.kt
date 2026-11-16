@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.rewind.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.logdate.feature.rewind.ui.RewindOpenCallback
import app.logdate.feature.rewind.ui.overview.RewindPreviewUiState
import app.logdate.feature.rewind.ui.overview.RewindSceneMemory
import app.logdate.ui.common.formatting.LocalToday
import app.logdate.ui.platform.PlatformIcons
import app.logdate.ui.platform.rememberSystemReduceMotion
import app.logdate.util.formatDateLocalized
import app.logdate.util.toReadableDateShort
import coil3.compose.AsyncImage
import logdate.client.feature.rewind.generated.resources.Res
import logdate.client.feature.rewind.generated.resources.rewind_open
import logdate.client.feature.rewind.generated.resources.rewind_state_count_entries
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** A weekly entry card: real memory objects, with just enough context to choose a story. */
@Composable
internal fun RewindWeekScene(
    rewind: RewindPreviewUiState,
    onOpenRewind: RewindOpenCallback,
    modifier: Modifier = Modifier,
) {
    val memories =
        remember(rewind) {
            rewind.memories
                .ifEmpty {
                    listOfNotNull(
                        rewind.heroImageUri?.let { RewindSceneMemory.Visual(it) },
                        rewind.highlightedQuote?.takeIf(String::isNotBlank)?.let { RewindSceneMemory.Writing(it) },
                    )
                }.take(3)
        }
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val playLabel = stringResource(Res.string.rewind_open)
    val showYear = rewind.start.year != LocalToday.current.year || rewind.end.year != LocalToday.current.year
    val start = if (showYear) formatDateLocalized(rewind.start) else rewind.start.toReadableDateShort()
    val end = if (showYear) formatDateLocalized(rewind.end) else rewind.end.toReadableDateShort()
    val dateLabel = if (start == end) start else "$start – $end"
    BoxWithConstraints(modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        val wide = maxWidth >= 700.dp && density.fontScale < 1.5f
        val inset = if (maxWidth < 400.dp) 20.dp else 28.dp
        Box(
            Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp)).background(
                lerp(scheme.surfaceContainerLow, scheme.secondaryContainer, .35f),
            ),
        ) {
            RewindSceneAtmosphere(rewind, Modifier.fillMaxSize())
            Column(Modifier.fillMaxSize().padding(inset), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(dateLabel, style = MaterialTheme.typography.labelLarge, color = scheme.onSurface, maxLines = 2)
                    if (rewind.entryCount > 0) {
                        Text(
                            pluralStringResource(Res.plurals.rewind_state_count_entries, rewind.entryCount, rewind.entryCount),
                            style = MaterialTheme.typography.labelLarge,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
                RewindMemoryComposition(
                    memories,
                    rewind,
                    dateLabel,
                    onOpenRewind,
                    Modifier.weight(1f).fillMaxWidth(),
                )
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            rewind.title,
                            style = MaterialTheme.typography.headlineMedium,
                            color = scheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        RewindPlayButton(playLabel) { onOpenRewind(rewind.rewindId) }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            rewind.title,
                            style = MaterialTheme.typography.headlineSmall,
                            color = scheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        RewindPlayButton(playLabel) { onOpenRewind(rewind.rewindId) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RewindPlayButton(
    label: String,
    onPlay: () -> Unit,
) {
    FilledTonalButton(onClick = onPlay) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(PlatformIcons.play(), contentDescription = null, modifier = Modifier.size(18.dp))
            Text(label)
        }
    }
}

@Composable
private fun RewindMemoryComposition(
    memories: List<RewindSceneMemory>,
    rewind: RewindPreviewUiState,
    dateLabel: String,
    onOpenRewind: RewindOpenCallback,
    modifier: Modifier = Modifier,
) {
    val primary = memories.firstOrNull()
    var imageAspect by remember((primary as? RewindSceneMemory.Visual)?.uri) { mutableFloatStateOf(4f / 3f) }
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val writingStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 21.sp, lineHeight = 31.sp, fontWeight = FontWeight.Normal)
    val playLabel = stringResource(Res.string.rewind_open)
    BoxWithConstraints(modifier) {
        val availableHeight = maxHeight.coerceAtLeast(1.dp)
        val availableWidth = (maxWidth * if (maxWidth >= 700.dp) .72f else .9f).coerceAtMost(740.dp)
        val focusWidth =
            when (primary) {
                is RewindSceneMemory.Visual -> availableWidth.coerceAtMost(availableHeight * imageAspect)
                is RewindSceneMemory.Recording -> availableWidth.coerceAtMost(400.dp)
                else -> availableWidth.coerceAtMost(560.dp)
            }
        val focusHeight =
            when (primary) {
                is RewindSceneMemory.Visual -> focusWidth / imageAspect
                is RewindSceneMemory.Recording -> availableHeight.coerceAtMost(180.dp)
                is RewindSceneMemory.Writing -> {
                    val width = with(density) { (focusWidth - 56.dp).toPx().toInt().coerceAtLeast(1) }
                    val height =
                        textMeasurer
                            .measure(
                                primary.text,
                                style = writingStyle,
                                maxLines = 10,
                                constraints = Constraints(maxWidth = width),
                            ).size.height
                    (with(density) { height.toDp() } + 56.dp).coerceAtLeast(160.dp).coerceAtMost(availableHeight)
                }
                else -> availableHeight.coerceAtMost(220.dp)
            }
        val direction = if (rewind.rewindId.hashCode() and 1 == 0) 1f else -1f
        memories.indices.reversed().forEach { depth ->
            val memory = memories[depth]
            val front = depth == 0
            val objectWidth = if (front) focusWidth else (focusWidth * .82f).coerceAtMost(430.dp)
            val objectHeight =
                if (front) {
                    focusHeight
                } else {
                    when (memory) {
                        is RewindSceneMemory.Recording -> 132.dp
                        is RewindSceneMemory.Visual -> objectWidth * .75f
                        is RewindSceneMemory.Writing -> (objectWidth * .6f).coerceAtMost(focusHeight * .9f)
                    }.coerceAtMost(availableHeight * .8f)
                }
            val opposite = if (depth == 1) -direction else direction
            val horizontalRoom = ((maxWidth - objectWidth) / 2 - 8.dp).coerceAtLeast(0.dp)
            val verticalRoom = ((availableHeight - objectHeight) / 2 - 8.dp).coerceAtLeast(0.dp)
            val x = if (front) 0.dp else (focusWidth * (.18f + depth * .04f)).coerceAtMost(horizontalRoom) * opposite
            val y = if (front) 0.dp else (focusHeight * (if (depth == 1) .12f else -.18f)).coerceIn(-verticalRoom, verticalRoom)
            val shape = RoundedCornerShape(if (memory is RewindSceneMemory.Writing) 6.dp else 12.dp)
            RewindSceneObject(
                memory = memory,
                foreground = front,
                dateLabel = dateLabel,
                writingStyle = writingStyle,
                onImageAspect = { if (front && it.isFinite() && it > 0f) imageAspect = it },
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .size(objectWidth, objectHeight)
                        .graphicsLayer {
                            translationX = with(density) { x.toPx() }
                            translationY = with(density) { y.toPx() }
                            rotationZ = if (front) 0f else opposite * 1.5f
                            alpha =
                                if (front) {
                                    1f
                                } else if (depth == 1) {
                                    .55f
                                } else {
                                    .4f
                                }
                            shadowElevation = with(density) { (if (front) 18.dp else 6.dp).toPx() }
                            this.shape = shape
                            clip = true
                        }.then(
                            if (front) {
                                Modifier.clickable(onClickLabel = playLabel) { onOpenRewind(rewind.rewindId) }
                            } else {
                                Modifier.clearAndSetSemantics {}
                            },
                        ),
            )
        }
        if (primary == null) {
            Text(
                rewind.message,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        }
    }
}

/** The card atmosphere comes from its real media and respects the active color scheme. */
@Composable
internal fun RewindSceneAtmosphere(
    rewind: RewindPreviewUiState?,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val reduceMotion by rememberSystemReduceMotion()
    val uri =
        rewind
            ?.memories
            ?.filterIsInstance<RewindSceneMemory.Visual>()
            ?.firstOrNull()
            ?.uri
            ?: rewind?.takeIf { it.memories.isEmpty() }?.heroImageUri
    Box(modifier.background(Brush.verticalGradient(listOf(scheme.secondaryContainer.copy(alpha = .4f), scheme.surface)))) {
        Crossfade(targetState = uri, animationSpec = tween(if (reduceMotion) 0 else 280), label = "WeekAtmosphere") { image ->
            if (image != null) {
                AsyncImage(
                    model = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier.fillMaxSize().blur(64.dp).graphicsLayer {
                            alpha = if (scheme.surface.luminance() < .3f) .08f else .22f
                        },
                )
            }
        }
    }
}
