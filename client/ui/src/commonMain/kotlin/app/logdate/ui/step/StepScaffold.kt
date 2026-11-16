@file:Suppress("ktlint:standard:function-naming")

package app.logdate.ui.step

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.runtime.Immutable
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
import app.logdate.ui.adaptive.FoldableBookLayout
import app.logdate.ui.adaptive.FoldableTabletopLayout
import app.logdate.ui.foldable.FoldableLayoutInfo
import app.logdate.ui.foldable.rememberFoldableLayoutInfo
import app.logdate.ui.theme.Spacing
import logdate.client.ui.generated.resources.Res
import logdate.client.ui.generated.resources.common_back
import org.jetbrains.compose.resources.stringResource

/**
 * Position of a step within a multi-step flow, rendered as a progress bar and a "2 of 5" label.
 *
 * @param current 1-based index of the step being shown.
 * @param total Total number of steps in the flow.
 */
@Immutable
data class StepProgress(
    val current: Int,
    val total: Int,
) {
    init {
        require(total > 0) { "total must be positive, was $total" }
        require(current in 1..total) { "current must be within 1..$total, was $current" }
    }

    val fraction: Float
        get() = current.toFloat() / total.toFloat()
}

object StepScaffoldDefaults {
    /**
     * Widest the body and actions are allowed to grow. Keeps line lengths readable and the primary
     * action within thumb reach on large screens instead of stretching edge to edge.
     */
    val ContentMaxWidth: Dp = 444.dp

    /** Minimum height of the action pane when a tabletop-posture foldable splits the screen. */
    val MinActionPaneHeight: Dp = 220.dp

    /** Minimum width of each pane when a book-posture foldable splits the screen. */
    val MinBookPaneWidth: Dp = 320.dp

    /**
     * A window at least this wide and this tall -- a tablet, an unfolded foldable, a desktop
     * window -- shows the step in a panel in the middle of the window instead of stretching it.
     */
    val PanelMinWindowSize: Dp = 600.dp

    /** The widest the panel grows: the content column and a comfortable margin either side. */
    val PanelMaxWidth: Dp = 560.dp

    /**
     * The tallest the panel grows. Past this, the gap between a step's content and its actions
     * reads as a hole in the middle of the step.
     */
    val PanelMaxHeight: Dp = 840.dp

    /** Space kept between the panel and the edges of the window. */
    val PanelWindowMargin: Dp = 24.dp

    /**
     * A window at least [PanelMinWindowSize] wide but shorter than it -- a phone in landscape --
     * puts the details beside the header, because stacking would leave the body a sliver above
     * the actions.
     */
    val ShortWindowSideBySideMinWidth: Dp = 600.dp

    /**
     * The least height an illustration is given. A step with more above and below it than fits
     * scrolls instead of squeezing its picture below this.
     */
    val IllustrationMinHeight: Dp = 120.dp

    /**
     * Room for a [StepPrimaryButton] and a [StepSecondaryButton] beneath it: 56dp, the gap, and a
     * 48dp touch target. Every step keeps this much, whether or not it has a second action, so the
     * main action never moves from one step to the next.
     */
    val ActionAreaMinHeight: Dp = 112.dp

    /**
     * Room above the title for a [StepHero]. Every step keeps it, with or without a hero, so the
     * title is the same distance from the top on every step.
     */
    val HeroSlotHeight: Dp = 88.dp
}

/** The title of a step, for tests that compare where it sits from one step to the next. */
const val STEP_TITLE_TAG = "step_title"

/**
 * Full-screen layout for one step of a linear flow: a back affordance, an optional hero, a title,
 * optional supporting copy, an optional illustration and content, and a set of actions.
 *
 * Two things never move from one step to the next: the title and the main action. The title is
 * anchored below the back row and a fixed slot for the hero, and the actions are pinned to the
 * bottom. Everything whose height varies -- supporting copy, an illustration, content -- sits
 * between them and scrolls when it does not fit.
 *
 * The actions are a **required** parameter rather than an optional pane, and every posture this
 * scaffold can render routes through them. Omitting them is a compile error, not a screen the user
 * cannot leave. That distinction matters: cloud account creation and sign-in were both unreachable
 * for a period because each screen hand-rolled its own adaptive layout and the compact branch
 * silently dropped the buttons.
 *
 * Callers supply only content. Posture handling is internal to this file so that no individual
 * screen can get it wrong:
 * - **Phone:** the body starts at the top and scrolls when it doesn't fit; the actions sit below
 *   it, never on top of it.
 * - **Tablet, unfolded foldable, desktop window:** the same step, title at the top and actions at
 *   the bottom, in a panel in the middle of the window. A step is one decision; spreading it
 *   across a large window, or splitting it into two panes, scatters the title, the content and
 *   the buttons that belong together.
 * - **Book posture, or a phone in landscape:** the header on one side, the content and actions on
 *   the other, because a hinge or a very short window leaves no room to stack them.
 * - **Tabletop posture:** the header above the hinge, the content and actions below it. Half a
 *   folded screen is too short for a header and its content together, and splitting them this way
 *   matches book posture.
 *
 * Any region that can scroll further fades out at that edge, so content that continues past a pane
 * boundary reads as more to scroll to, not as text sliced off mid-line.
 *
 * Window insets are applied per pane, inside the foldable layouts, because hinge bounds are in
 * window coordinates and padding the container would shift the split off the hinge.
 *
 * @param title Headline for the step, in sentence case.
 * @param onBack Invoked by the back affordance, or `null` to omit it (for example on the first step
 *   of a flow the user cannot reverse out of).
 * @param actions Buttons for this step, stacked vertically. The primary action comes first, and it
 *   sits in the same place on every step: a step with one action leaves the space under it empty.
 * @param supportingText Optional paragraph shown beneath the title.
 * @param progress Optional position within the flow.
 * @param footer Optional low-emphasis element just above the actions, such as a server switcher or
 *   legal links. It sits above them so that having one does not move the actions.
 * @param hero Optional mark shown above the title, such as [StepHeroIcon]. It is drawn in a slot
 *   [StepScaffoldDefaults.HeroSlotHeight] tall, so it must be no taller than that.
 * @param illustration Optional picture shown under the title and supporting text. It is measured
 *   last and offered the height the step has left once everything else has its room, so it should
 *   ask for the most it wants and draw itself to fit less: a tall picture then fills a tall step
 *   and shrinks in a short one, without pushing the content out of view.
 * @param headerAlignment Alignment of the hero, title and supporting text. Content stays full width.
 * @param containerColor Fill behind the whole step. Opaque by default, like a Scaffold, so steps
 *   sliding past each other during a transition never show through one another.
 * @param content Optional body content shown between the supporting text and the actions.
 */
@Composable
fun StepScaffold(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    progress: StepProgress? = null,
    footer: (@Composable () -> Unit)? = null,
    hero: (@Composable () -> Unit)? = null,
    illustration: (@Composable () -> Unit)? = null,
    headerAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    containerColor: Color = MaterialTheme.colorScheme.background,
    contentMaxWidth: Dp = StepScaffoldDefaults.ContentMaxWidth,
    foldableLayoutInfo: FoldableLayoutInfo = rememberFoldableLayoutInfo(),
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val slots =
        StepSlots(
            title = title,
            onBack = onBack,
            actions = actions,
            supportingText = supportingText,
            progress = progress,
            footer = footer,
            hero = hero,
            illustration = illustration,
            containerColor = containerColor,
            headerAlignment = headerAlignment,
            contentMaxWidth = contentMaxWidth,
            content = content,
        )

    val safeDrawing = WindowInsets.safeDrawing
    FoldableTabletopLayout(
        modifier = modifier.fillMaxSize().background(containerColor),
        foldableLayoutInfo = foldableLayoutInfo,
        minPaneHeight = StepScaffoldDefaults.MinActionPaneHeight,
        topPane = {
            StepHeaderPane(
                slots = slots,
                includeContent = false,
                modifier = Modifier.fillMaxSize().windowInsetsPadding(safeDrawing.only(TabletopTopPaneSides)),
            )
        },
        bottomPane = {
            StepDetailPane(
                slots = slots,
                modifier = Modifier.fillMaxSize().windowInsetsPadding(safeDrawing.only(TabletopBottomPaneSides)),
            )
        },
        standardContent = {
            FoldableBookLayout(
                modifier = Modifier.fillMaxSize(),
                foldableLayoutInfo = foldableLayoutInfo,
                minPaneWidth = StepScaffoldDefaults.MinBookPaneWidth,
                startPane = {
                    StepHeaderPane(
                        slots = slots,
                        includeContent = false,
                        modifier = Modifier.fillMaxSize().windowInsetsPadding(safeDrawing.only(BookStartPaneSides)),
                    )
                },
                endPane = {
                    StepDetailPane(
                        slots = slots,
                        modifier = Modifier.fillMaxSize().windowInsetsPadding(safeDrawing.only(BookEndPaneSides)),
                    )
                },
                standardContent = {
                    StandardStep(slots = slots)
                },
            )
        },
    )
}

private val TabletopTopPaneSides = WindowInsetsSides.Top + WindowInsetsSides.Horizontal
private val TabletopBottomPaneSides = WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal
private val BookStartPaneSides = WindowInsetsSides.Vertical + WindowInsetsSides.Start
private val BookEndPaneSides = WindowInsetsSides.Vertical + WindowInsetsSides.End

private class StepSlots(
    val title: String,
    val onBack: (() -> Unit)?,
    val actions: @Composable ColumnScope.() -> Unit,
    val supportingText: String?,
    val progress: StepProgress?,
    val footer: (@Composable () -> Unit)?,
    val hero: (@Composable () -> Unit)?,
    val illustration: (@Composable () -> Unit)?,
    val containerColor: Color,
    val headerAlignment: Alignment.Horizontal,
    val contentMaxWidth: Dp,
    val content: (@Composable ColumnScope.() -> Unit)?,
)

@Composable
private fun StandardStep(slots: StepSlots) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val defaults = StepScaffoldDefaults
        val short = maxHeight < defaults.PanelMinWindowSize
        if (short && maxWidth >= defaults.ShortWindowSideBySideMinWidth) {
            Row(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                StepHeaderPane(
                    slots = slots,
                    includeContent = false,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                StepDetailPane(
                    slots = slots,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
            return@BoxWithConstraints
        }

        if (maxWidth < defaults.PanelMinWindowSize || short) {
            StackedStep(slots = slots, modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing))
            return@BoxWithConstraints
        }

        // A step that paints its own backdrop keeps it: the panel is then only where the step
        // sits, not a surface of its own.
        val painted = slots.containerColor != Color.Transparent
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(if (painted) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(defaults.PanelWindowMargin),
            contentAlignment = Alignment.Center,
        ) {
            StackedStep(
                slots = slots,
                modifier =
                    Modifier
                        .widthIn(max = defaults.PanelMaxWidth)
                        .heightIn(max = defaults.PanelMaxHeight)
                        .fillMaxSize()
                        .clip(MaterialTheme.shapes.extraLarge)
                        .background(slots.containerColor),
            )
        }
    }
}

/** The step as one column: the header and content from the top, the actions at the bottom. */
@Composable
private fun StackedStep(
    slots: StepSlots,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        StepHeaderPane(
            slots = slots,
            includeContent = true,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.md, bottom = Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StepActionColumn(slots)
        }
    }
}

/**
 * The back row pinned at the top, then the header -- and, where there is no separate detail pane,
 * the content -- starting at the top of the remaining space and scrolling when it doesn't fit.
 */
@Composable
private fun StepHeaderPane(
    slots: StepSlots,
    includeContent: Boolean,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
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
private fun StepDetailPane(
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
private fun StepActionColumn(slots: StepSlots) {
    Column(
        modifier = Modifier.widthIn(max = slots.contentMaxWidth).fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        slots.footer?.invoke()
        // The main action is the first thing in an area that always has room for a second one, so
        // it is the same distance from the bottom on a step with one action, two, or a footer.
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(min = StepScaffoldDefaults.ActionAreaMinHeight),
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
