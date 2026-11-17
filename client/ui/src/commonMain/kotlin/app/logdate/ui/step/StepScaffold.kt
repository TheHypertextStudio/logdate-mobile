@file:Suppress("ktlint:standard:function-naming")

package app.logdate.ui.step

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.logdate.ui.adaptive.FoldableBookLayout
import app.logdate.ui.adaptive.FoldableTabletopLayout
import app.logdate.ui.foldable.FoldableLayoutInfo
import app.logdate.ui.foldable.rememberFoldableLayoutInfo
import app.logdate.ui.theme.Spacing

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
 * Callers supply only content. Posture handling is internal to the scaffold, so that no individual
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
 * @param scene Optional layer drawn in front of the step and across the whole window, for a step
 *   that is about a picture more than a form. It is told where the words and the actions lie, so
 *   it can fill the room around them. A touch that lands on nothing in the scene reaches the step
 *   underneath, so the actions stay usable.
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
    scene: (@Composable BoxScope.(StepSceneBounds) -> Unit)? = null,
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
            scene = scene,
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
                hostsScene = true,
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
                        hostsScene = true,
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

internal class StepSlots(
    val title: String,
    val onBack: (() -> Unit)?,
    val actions: @Composable ColumnScope.() -> Unit,
    val supportingText: String?,
    val progress: StepProgress?,
    val footer: (@Composable () -> Unit)?,
    val hero: (@Composable () -> Unit)?,
    val illustration: (@Composable () -> Unit)?,
    val scene: (@Composable BoxScope.(StepSceneBounds) -> Unit)?,
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
                    hostsScene = true,
                )
                StepDetailPane(
                    slots = slots,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
            return@BoxWithConstraints
        }

        if (maxWidth < defaults.PanelMinWindowSize || short) {
            StepWithScene(scene = slots.scene, reachesActions = true, fillsWindow = true, modifier = Modifier.fillMaxSize()) {
                StackedStep(slots = slots, modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing))
            }
            return@BoxWithConstraints
        }

        // A step that paints its own backdrop keeps it: the panel is then only where the step
        // sits, not a surface of its own.
        val painted = slots.containerColor != Color.Transparent
        StepWithScene(scene = slots.scene, reachesActions = true, fillsWindow = true, modifier = Modifier.fillMaxSize()) {
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
