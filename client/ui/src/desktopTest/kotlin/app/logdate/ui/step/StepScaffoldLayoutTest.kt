@file:Suppress("ktlint:standard:function-naming")

package app.logdate.ui.step

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import app.logdate.ui.foldable.FoldableHingeBounds
import app.logdate.ui.foldable.FoldableHingeInfo
import app.logdate.ui.foldable.FoldableHingeOrientation
import app.logdate.ui.foldable.FoldableHingeState
import app.logdate.ui.foldable.FoldableLayoutInfo
import app.logdate.ui.foldable.FoldableOcclusionType
import app.logdate.ui.foldable.FoldablePosture
import app.logdate.ui.foldable.provideFoldableLayoutInfo
import app.logdate.ui.theme.LogDateTheme
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val ROOT_TAG = "step_root"
private const val HERO_TAG = "step_hero"
private const val LAST_CONTENT_TAG = "step_last_content"
private const val PRIMARY_ACTION_TAG = "step_primary_action"
private const val TITLE = "Choose your handle"
private const val LONG_SUPPORTING_TEXT =
    "This is how other people find you. You can change it later, but people who already know it " +
        "will need the new one. Pick something you are happy to be known by in every journal you " +
        "share, since it shows up next to everything you add."

/**
 * Geometry guarantees [StepScaffold] makes in every posture. Each onboarding step once hand-rolled
 * these, and each got at least one of them wrong: content scrolling underneath the buttons,
 * buttons stretched across a whole foldable pane, and a title that sat at a different height on
 * every step.
 */
@OptIn(ExperimentalTestApi::class)
class StepScaffoldLayoutTest {
    @Test
    fun `the main action sits in the same place with one action, two, or a footer`() {
        fun mainAction(
            second: Boolean,
            footer: Boolean,
        ): DpRect {
            lateinit var bounds: DpRect
            runDesktopComposeUiTest(width = 411, height = 891) {
                setContent {
                    LogDateTheme {
                        StepScaffold(
                            title = TITLE,
                            onBack = null,
                            footer = if (footer) ({ Text("You can change this later.") }) else null,
                            actions = {
                                Button(onClick = {}, modifier = Modifier.fillMaxWidth().testTag(PRIMARY_ACTION_TAG)) { Text("Continue") }
                                if (second) TextButton(onClick = {}) { Text("Not now") }
                            },
                        )
                    }
                }
                bounds = onNodeWithTag(PRIMARY_ACTION_TAG).getBoundsInRoot()
            }
            return bounds
        }

        val alone = mainAction(second = false, footer = false)
        assertEquals(alone, mainAction(second = true, footer = false))
        assertEquals(alone, mainAction(second = true, footer = true))
        assertEquals(alone, mainAction(second = false, footer = true))
    }

    @Test
    fun `scrolled-to-end content stays above the actions on a phone`() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            setStep(contentItems = 30)

            onNodeWithTag(LAST_CONTENT_TAG).performScrollTo()

            val last = bounds(LAST_CONTENT_TAG)
            val action = bounds(PRIMARY_ACTION_TAG)
            assertTrue(last.bottom <= action.top, "last content (bottom ${last.bottom}) is under the actions (top ${action.top})")
        }

    @Test
    fun `the title sits in the same place whatever else the step holds`() {
        fun title(
            contentItems: Int,
            onBack: (() -> Unit)?,
            withHero: Boolean,
            supportingText: String = "This is how other people find you.",
        ): DpRect {
            lateinit var bounds: DpRect
            runDesktopComposeUiTest(width = 411, height = 891) {
                setContent { LogDateTheme { SampleStep(contentItems, onBack, withHero, supportingText = supportingText) } }
                bounds = onNodeWithTag(STEP_TITLE_TAG).getBoundsInRoot()
            }
            return bounds
        }

        val bare = title(contentItems = 0, onBack = null, withHero = false)
        assertEquals(bare, title(contentItems = 1, onBack = {}, withHero = false))
        assertEquals(bare, title(contentItems = 1, onBack = {}, withHero = true))
        assertEquals(bare, title(contentItems = 30, onBack = null, withHero = true))
        assertEquals(bare, title(contentItems = 3, onBack = {}, withHero = false, supportingText = LONG_SUPPORTING_TEXT))
    }

    @Test
    fun `hero sits above the title`() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            setStep(contentItems = 1, withHero = true)

            val hero = bounds(HERO_TAG)
            val title = onNodeWithText(TITLE).getBoundsInRoot()
            assertTrue(hero.bottom <= title.top, "hero (bottom ${hero.bottom}) overlaps title (top ${title.top})")
        }

    @Test
    fun `a tablet shows the whole step in one panel in the middle of the window`() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            setStep(contentItems = 1)

            val title = onNodeWithTag(STEP_TITLE_TAG).getBoundsInRoot()
            val action = bounds(PRIMARY_ACTION_TAG)
            assertTrue(action.top >= title.bottom, "actions (top ${action.top}) are not under the title (bottom ${title.bottom})")
            val center = (action.left + action.right) / 2
            assertTrue(abs((center - 640.dp).value) <= 1f, "the step is not in the middle of the window (centered on $center)")
            assertTrue(
                action.left >= title.left && action.right <= title.right,
                "the title and the actions are not in one column",
            )
            assertTrue(action.bottom <= 800.dp - StepScaffoldDefaults.PanelWindowMargin, "actions run into the window margin")
            assertCapped(action)
        }

    @Test
    fun `the title and the main action stay put on a tablet whatever the step holds`() {
        fun step(
            contentItems: Int,
            withHero: Boolean,
        ): Pair<DpRect, DpRect> {
            lateinit var bounds: Pair<DpRect, DpRect>
            runDesktopComposeUiTest(width = 1280, height = 800) {
                setStep(contentItems = contentItems, withHero = withHero)
                bounds = onNodeWithTag(STEP_TITLE_TAG).getBoundsInRoot() to bounds(PRIMARY_ACTION_TAG)
            }
            return bounds
        }

        val bare = step(contentItems = 0, withHero = false)
        assertEquals(bare, step(contentItems = 1, withHero = true))
        assertEquals(bare, step(contentItems = 30, withHero = false))
    }

    @Test
    fun `wide window with no content keeps the actions under the header`() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            setContent { LogDateTheme { SampleStep(contentItems = 0, onBack = {}, withHero = false) } }

            val title = onNodeWithText(TITLE).getBoundsInRoot()
            val action = bounds(PRIMARY_ACTION_TAG)
            assertTrue(action.top >= title.bottom, "actions (top ${action.top}) are not under the header (bottom ${title.bottom})")
            assertTrue(action.left < title.right && title.left < action.right, "actions are not in the header's column")
        }

    @Test
    fun `a short window with no content keeps the actions on screen`() =
        runDesktopComposeUiTest(width = 891, height = 411) {
            setContent {
                LogDateTheme {
                    SampleStep(contentItems = 0, onBack = {}, withHero = true, supportingText = LONG_SUPPORTING_TEXT)
                }
            }

            val bottom = unclippedBottom(PRIMARY_ACTION_TAG)
            assertTrue(bottom <= 411.dp, "actions (bottom $bottom) are pushed off the screen")
        }

    @Test
    fun `landscape phone places actions beside the body`() =
        runDesktopComposeUiTest(width = 891, height = 411) {
            setStep(contentItems = 1)

            val title = onNodeWithText(TITLE).getBoundsInRoot()
            val action = bounds(PRIMARY_ACTION_TAG)
            assertTrue(action.left >= title.right, "actions (left ${action.left}) are not beside the body (right ${title.right})")
        }

    @Test
    fun `book posture keeps body and actions on opposite sides of the hinge`() =
        runDesktopComposeUiTest(width = 1440, height = 900) {
            setStep(contentItems = 1, foldable = bookPosture)

            val title = onNodeWithText(TITLE).getBoundsInRoot()
            val action = bounds(PRIMARY_ACTION_TAG)
            assertTrue(title.right <= 708.dp, "title crosses the hinge (right ${title.right})")
            assertTrue(action.left >= 732.dp, "actions cross the hinge (left ${action.left})")
            assertCapped(action)
        }

    @Test
    fun `tabletop posture keeps body above and actions below the hinge`() =
        runDesktopComposeUiTest(width = 1440, height = 900) {
            setStep(contentItems = 1, foldable = tabletopPosture)

            val title = onNodeWithText(TITLE).getBoundsInRoot()
            val action = bounds(PRIMARY_ACTION_TAG)
            assertTrue(title.bottom <= 438.dp, "title crosses the hinge (bottom ${title.bottom})")
            assertTrue(action.top >= 462.dp, "actions cross the hinge (top ${action.top})")
            assertCapped(action)
        }

    @Test
    fun `tabletop posture puts content below the hinge, not cut off above it`() =
        runDesktopComposeUiTest(width = 1440, height = 900) {
            setStep(contentItems = 3, foldable = tabletopPosture)

            val first = bounds("step_content_0")
            val last = bounds(LAST_CONTENT_TAG)
            assertTrue(first.top >= 462.dp, "content (top ${first.top}) sits above the hinge")
            assertClear(last, bounds(PRIMARY_ACTION_TAG))
        }

    @Test
    fun `overflowing tabletop content scrolls between the hinge and the actions`() =
        runDesktopComposeUiTest(width = 1440, height = 900) {
            setStep(contentItems = 30, foldable = tabletopPosture)

            onNodeWithTag(LAST_CONTENT_TAG).performScrollTo()

            val last = bounds(LAST_CONTENT_TAG)
            assertTrue(last.top >= 462.dp, "content (top ${last.top}) scrolled across the hinge")
            assertTrue(last.bottom <= 900.dp, "content (bottom ${last.bottom}) runs off the screen")
            assertClear(last, bounds(PRIMARY_ACTION_TAG))
        }

    @Test
    fun `a wide tabletop pane puts content beside the actions instead of scrolling it`() =
        runDesktopComposeUiTest(width = 1440, height = 900) {
            setStep(contentItems = 6, foldable = tabletopPosture)

            val last = bounds(LAST_CONTENT_TAG)
            val action = bounds(PRIMARY_ACTION_TAG)
            assertTrue(action.left >= last.right, "actions (left ${action.left}) are not beside the content (right ${last.right})")
            val lastBottom = unclippedBottom(LAST_CONTENT_TAG)
            assertTrue(lastBottom <= 900.dp, "content (bottom $lastBottom) needs scrolling in a two-column pane")
        }

    @Test
    fun `progress stays within the content column on a wide window`() =
        runDesktopComposeUiTest(width = 1440, height = 900) {
            setContent { LogDateTheme { SampleStep(contentItems = 1, onBack = {}, withHero = false, progress = StepProgress(1, 2)) } }

            val bar = onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(0.5f, 0f..1f))).getBoundsInRoot()
            assertTrue(bar.right - bar.left <= StepScaffoldDefaults.ContentMaxWidth, "progress spans ${bar.right - bar.left}")
        }

    @Test
    fun `the step is opaque so transitions never show another step through it`() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            // No LogDateTheme here: its root Surface would paint an opaque background of its own.
            setContent { MaterialTheme { SampleStep(contentItems = 1, onBack = {}, withHero = false) } }

            val pixels = onNodeWithTag(ROOT_TAG).captureToImage().toPixelMap()
            val corner = pixels[pixels.width - 1, pixels.height / 2]
            assertEquals(1f, corner.alpha, "step background is not opaque")
        }

    @Test
    fun `every posture carries exactly one root tag`() {
        listOf(null, bookPosture, tabletopPosture).forEach { posture ->
            runDesktopComposeUiTest(width = 1440, height = 900) {
                setStep(contentItems = 1, foldable = posture)
                onAllNodesWithTag(ROOT_TAG).assertCountEquals(1)
            }
        }
    }

    private fun DesktopComposeUiTest.bounds(tag: String): DpRect = onNodeWithTag(tag).getBoundsInRoot()

    /** Bounds in root are clipped to the enclosing viewport, which hides anything scrolled out of it. */
    private fun DesktopComposeUiTest.unclippedBottom(tag: String): Dp {
        val node = onNodeWithTag(tag).fetchSemanticsNode()
        return with(density) { (node.positionInRoot.y + node.size.height).toDp() }
    }

    private fun assertClear(
        content: DpRect,
        action: DpRect,
    ) {
        val overlaps =
            content.left < action.right &&
                action.left < content.right &&
                content.top < action.bottom &&
                action.top < content.bottom
        assertTrue(!overlaps, "content $content overlaps the actions $action")
    }

    private fun assertCapped(action: DpRect) {
        val width = action.right - action.left
        assertTrue(width <= StepScaffoldDefaults.ContentMaxWidth, "action is $width wide, past the content cap")
    }

    private fun DesktopComposeUiTest.setStep(
        contentItems: Int,
        onBack: (() -> Unit)? = {},
        withHero: Boolean = false,
        foldable: FoldableLayoutInfo? = null,
    ) {
        setContent {
            LogDateTheme {
                if (foldable != null) {
                    provideFoldableLayoutInfo(foldable) { SampleStep(contentItems, onBack, withHero) }
                } else {
                    SampleStep(contentItems, onBack, withHero)
                }
            }
        }
    }
}

@Composable
private fun SampleStep(
    contentItems: Int,
    onBack: (() -> Unit)?,
    withHero: Boolean,
    progress: StepProgress? = null,
    supportingText: String = "This is how other people find you. You can change it later.",
) {
    StepScaffold(
        progress = progress,
        title = TITLE,
        onBack = onBack,
        modifier = Modifier.testTag(ROOT_TAG),
        supportingText = supportingText,
        hero =
            if (withHero) {
                { StepHeroIcon(icon = Icons.Rounded.Star, modifier = Modifier.testTag(HERO_TAG)) }
            } else {
                null
            },
        actions = {
            Button(onClick = {}, modifier = Modifier.fillMaxWidth().testTag(PRIMARY_ACTION_TAG)) {
                Text("Continue")
            }
            TextButton(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                Text("Not now")
            }
        },
        content =
            if (contentItems == 0) {
                null
            } else {
                {
                    repeat(contentItems) { index ->
                        val tag = if (index == contentItems - 1) LAST_CONTENT_TAG else "step_content_$index"
                        Box(modifier = Modifier.fillMaxWidth().height(40.dp).testTag(tag))
                    }
                }
            },
    )
}

private val bookPosture =
    FoldableLayoutInfo(
        isFoldable = true,
        posture = FoldablePosture.Book,
        hinge =
            FoldableHingeInfo(
                orientation = FoldableHingeOrientation.Vertical,
                state = FoldableHingeState.HalfOpened,
                occlusionType = FoldableOcclusionType.Full,
                bounds = FoldableHingeBounds(left = 708.dp, top = 0.dp, right = 732.dp, bottom = 900.dp, width = 24.dp, height = 900.dp),
                isSeparating = true,
            ),
    )

private val tabletopPosture =
    FoldableLayoutInfo(
        isFoldable = true,
        posture = FoldablePosture.Tabletop,
        hinge =
            FoldableHingeInfo(
                orientation = FoldableHingeOrientation.Horizontal,
                state = FoldableHingeState.HalfOpened,
                occlusionType = FoldableOcclusionType.Full,
                bounds = FoldableHingeBounds(left = 0.dp, top = 438.dp, right = 1440.dp, bottom = 462.dp, width = 1440.dp, height = 24.dp),
                isSeparating = true,
            ),
    )
