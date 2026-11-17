@file:Suppress("ktlint:standard:function-naming")

package app.logdate.ui.step

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.logdate.ui.theme.Spacing
import logdate.client.ui.generated.resources.Res
import logdate.client.ui.generated.resources.common_back
import org.jetbrains.compose.resources.stringResource

/**
 * The back row pinned at the top, then the header -- and, where there is no separate detail pane,
 * the content -- starting at the top of the remaining space and scrolling when it doesn't fit.
 */
@Composable
internal fun StepHeaderPane(
    slots: StepSlots,
    includeContent: Boolean,
    modifier: Modifier = Modifier,
    hostsScene: Boolean = false,
) {
    val scrollState = rememberScrollState()
    if (hostsScene && slots.scene != null) {
        // The step is split in two here, so the scene has this side of it to itself.
        StepWithScene(scene = slots.scene, reachesActions = false, fillsWindow = false, modifier = modifier) {
            StepHeaderPane(slots = slots, includeContent = includeContent, modifier = Modifier.fillMaxSize())
        }
        return
    }
    Column(modifier = modifier) {
        StepTopBar(onBack = slots.onBack, progress = slots.progress, contentMaxWidth = slots.contentMaxWidth)
        TopScrollColumn(
            contentMaxWidth = slots.contentMaxWidth,
            scrollState = scrollState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { roomHeight ->
            StepBody(slots = slots, includeContent = includeContent, roomHeight = roomHeight)
        }
    }
}

/**
 * The header, the illustration and the content, one under the other.
 *
 * The illustration is measured after the other two and offered what is left of [roomHeight], so
 * it takes up the slack in a tall step and gives way in a short one.
 */
@Composable
private fun StepBody(
    slots: StepSlots,
    includeContent: Boolean,
    roomHeight: Dp,
) {
    val content = slots.content.takeIf { includeContent }
    Layout(
        modifier = Modifier.fillMaxWidth(),
        content = {
            StepHeader(slots)
            Box { slots.illustration?.invoke() }
            Box { if (content != null) StepContent(content) }
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        val gap = Spacing.xl.roundToPx()
        val header = measurables[0].measure(loose)
        val details = measurables[2].measure(loose)
        val detailsBlock = if (details.height > 0) details.height + gap else 0
        val left = roomHeight.roundToPx() - header.height - detailsBlock - gap
        val picture =
            measurables[1].measure(
                loose.copy(maxHeight = left.coerceAtLeast(StepScaffoldDefaults.IllustrationMinHeight.roundToPx())),
            )
        val pictureBlock = if (picture.height > 0) picture.height + gap else 0
        layout(constraints.maxWidth, header.height + pictureBlock + detailsBlock) {
            header.placeRelative(0, 0)
            if (picture.height > 0) picture.placeRelative((constraints.maxWidth - picture.width) / 2, header.height + gap)
            if (details.height > 0) details.placeRelative(0, header.height + pictureBlock + gap)
        }
    }
}

/**
 * The content and actions together, centered; the content scrolls and the actions stay put. A pane
 * wide enough for two columns -- the lower half of a tabletop-posture foldable -- puts them side by
 * side instead, since stacking them there wastes the width and forces the content to scroll.
 */
@Composable
internal fun StepDetailPane(
    slots: StepSlots,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.padding(Spacing.lg), contentAlignment = Alignment.Center) {
        val content = slots.content
        val twoColumns = content != null && maxWidth >= slots.contentMaxWidth * 2 + Spacing.xxl
        if (twoColumns) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xxl, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ScrollingStepContent(content = content, modifier = Modifier.width(slots.contentMaxWidth))
                Box(modifier = Modifier.width(slots.contentMaxWidth)) {
                    StepActionColumn(slots)
                }
            }
            return@BoxWithConstraints
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (content != null) {
                ScrollingStepContent(
                    content = content,
                    modifier = Modifier.weight(1f, fill = false).widthIn(max = slots.contentMaxWidth),
                )
                Spacer(modifier = Modifier.height(Spacing.xl))
            }
            StepActionColumn(slots)
        }
    }
}

@Composable
private fun ScrollingStepContent(
    content: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .fadingEdges(scrollState)
                .verticalScroll(scrollState),
    ) {
        StepContent(content)
    }
}

/**
 * Back affordance at the leading edge, and progress capped to the content column and centered on it,
 * so on a wide window the bar lines up with the step instead of spanning the whole screen. The row
 * keeps its height on a step with neither, so the title does not move up on that step.
 */
@Composable
private fun StepTopBar(
    onBack: (() -> Unit)?,
    progress: StepProgress?,
    contentMaxWidth: Dp,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.xs, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(TopBarSlotSize), contentAlignment = Alignment.Center) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(Res.string.common_back),
                    )
                }
            }
        }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (progress != null) {
                Row(
                    modifier = Modifier.widthIn(max = contentMaxWidth).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    LinearProgressIndicator(
                        progress = { progress.fraction },
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${progress.current}/${progress.total}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // Mirrors the back slot so the progress stays centered on the content column.
        Spacer(modifier = Modifier.size(TopBarSlotSize))
    }
}

private val TopBarSlotSize = 48.dp

@Composable
private fun StepHeader(slots: StepSlots) {
    val textAlign = if (slots.headerAlignment == Alignment.CenterHorizontally) TextAlign.Center else TextAlign.Start
    val motion = MaterialTheme.motionScheme
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = slots.headerAlignment,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().height(StepScaffoldDefaults.HeroSlotHeight),
            contentAlignment = if (slots.headerAlignment == Alignment.CenterHorizontally) Alignment.BottomCenter else Alignment.BottomStart,
        ) {
            slots.hero?.invoke()
        }
        Spacer(modifier = Modifier.height(Spacing.xl))
        // A step that changes its words in place fades from one set to the next, and the space
        // they need changes with them, so nothing under the header jumps.
        AnimatedContent(
            targetState = slots.title to slots.supportingText,
            modifier = Modifier.stepWords(),
            transitionSpec = {
                (fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()))
                    .using(SizeTransform(clip = false) { _, _ -> motion.defaultSpatialSpec() })
            },
            label = "Step header words",
        ) { (title, supportingText) ->
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = slots.headerAlignment) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineLarge.copy(lineBreak = LineBreak.Heading),
                    textAlign = textAlign,
                    modifier = Modifier.fillMaxWidth().testTag(STEP_TITLE_TAG),
                )
                if (supportingText != null) {
                    Spacer(modifier = Modifier.height(Spacing.md))
                    Text(
                        text = supportingText,
                        style = MaterialTheme.typography.bodyLarge.copy(lineBreak = LineBreak.Paragraph),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = textAlign,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun StepContent(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
        content = content,
    )
}

@Composable
internal fun StepActionColumn(slots: StepSlots) {
    Column(
        modifier = Modifier.widthIn(max = slots.contentMaxWidth).fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        slots.footer?.invoke()
        // The main action is the first thing in an area that always has room for a second one, so
        // it is the same distance from the bottom on a step with one action, two, or a footer.
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = StepScaffoldDefaults.ActionAreaMinHeight)
                    .stepActions(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            slots.actions(this)
        }
    }
}

/**
 * A column capped at [contentMaxWidth] whose content starts at the top and scrolls when it doesn't
 * fit. Starting at the top is what keeps the title in one place: a centered column would put it
 * higher or lower on every step, depending on how much that step holds. The content is told how
 * much height there is before it has to scroll.
 */
@Composable
private fun TopScrollColumn(
    contentMaxWidth: Dp,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable (roomHeight: Dp) -> Unit,
) {
    BoxWithConstraints(modifier = modifier) {
        val roomHeight = maxHeight - Spacing.lg
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .fadingEdges(scrollState)
                    .verticalScroll(scrollState)
                    .heightIn(min = maxHeight)
                    .padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(modifier = Modifier.widthIn(max = contentMaxWidth).fillMaxWidth()) {
                content(roomHeight)
            }
        }
    }
}

private val FadingEdgeLength = 32.dp

/**
 * Fades content out toward whichever edge it can still scroll past. Drawn over the viewport, before
 * [verticalScroll] in the chain, so the fade stays put while the content moves under it.
 */
private fun Modifier.fadingEdges(scrollState: ScrollState): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val length = FadingEdgeLength.toPx().coerceAtMost(size.height / 2)
            if (scrollState.canScrollBackward) {
                drawRect(
                    brush = Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, startY = 0f, endY = length),
                    size = Size(size.width, length),
                    blendMode = BlendMode.DstIn,
                )
            }
            if (scrollState.canScrollForward) {
                val top = size.height - length
                drawRect(
                    brush = Brush.verticalGradient(0f to Color.Black, 1f to Color.Transparent, startY = top, endY = size.height),
                    topLeft = Offset(0f, top),
                    size = Size(size.width, length),
                    blendMode = BlendMode.DstIn,
                )
            }
        }
