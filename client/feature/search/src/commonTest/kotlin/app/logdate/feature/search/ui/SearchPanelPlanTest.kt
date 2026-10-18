package app.logdate.feature.search.ui

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.logdate.ui.foldable.FoldableHingeBounds
import app.logdate.ui.foldable.FoldableHingeInfo
import app.logdate.ui.foldable.FoldableHingeOrientation
import app.logdate.ui.foldable.FoldableHingeState
import app.logdate.ui.foldable.FoldableLayoutInfo
import app.logdate.ui.foldable.FoldableOcclusionType
import app.logdate.ui.foldable.FoldablePosture
import kotlin.test.Test
import kotlin.test.assertEquals

class SearchPanelPlanTest {
    private fun plan(
        width: Int,
        height: Int,
        foldable: FoldableLayoutInfo = FoldableLayoutInfo(),
        direction: LayoutDirection = LayoutDirection.Ltr,
    ) = planSearchPanels(width.dp, height.dp, foldable, DpOffset.Zero, direction)

    private fun verticalHinge(
        left: Int,
        right: Int,
        height: Int,
    ) = FoldableLayoutInfo(
        isFoldable = true,
        posture = FoldablePosture.Book,
        hinge =
            FoldableHingeInfo(
                orientation = FoldableHingeOrientation.Vertical,
                state = FoldableHingeState.HalfOpened,
                occlusionType = FoldableOcclusionType.Full,
                bounds = FoldableHingeBounds(left.dp, 0.dp, right.dp, height.dp, (right - left).dp, height.dp),
                isSeparating = true,
            ),
    )

    private fun horizontalHinge(
        top: Int,
        bottom: Int,
        width: Int,
    ) = FoldableLayoutInfo(
        isFoldable = true,
        posture = FoldablePosture.Tabletop,
        hinge =
            FoldableHingeInfo(
                orientation = FoldableHingeOrientation.Horizontal,
                state = FoldableHingeState.HalfOpened,
                occlusionType = FoldableOcclusionType.Full,
                bounds = FoldableHingeBounds(0.dp, top.dp, width.dp, bottom.dp, width.dp, (bottom - top).dp),
                isSeparating = true,
            ),
    )

    @Test
    fun `a phone shows only the results`() {
        assertEquals(SearchPanels.ResultsOnly, plan(400, 800))
    }

    @Test
    fun `a wide window shows two panels and never three`() {
        assertEquals(SearchPanels.ResultsAndPreview, plan(1180, 740))
        assertEquals(SearchPanels.ResultsAndPreview, plan(1920, 1080))
    }

    @Test
    fun `a medium window shows results beside a preview and leaves narrowing above the results`() {
        assertEquals(SearchPanels.ResultsAndPreview, plan(900, 620))
    }

    @Test
    fun `a window too narrow for two panels shows only the results`() {
        assertEquals(SearchPanels.ResultsOnly, plan(700, 800))
    }

    @Test
    fun `a window too short for panels shows only the results`() {
        assertEquals(SearchPanels.ResultsOnly, plan(1180, 200))
    }

    @Test
    fun `held like a book the results and the preview take a page each`() {
        assertEquals(SearchPanels.ResultsAndPreview, plan(1440, 900, verticalHinge(left = 708, right = 732, height = 900)))
    }

    @Test
    fun `flat with a horizontal fold the preview is above and the results below`() {
        assertEquals(SearchPanels.ResultsAndPreview, plan(560, 820, horizontalHinge(top = 400, bottom = 420, width = 560)))
    }

    @Test
    fun `a fold that leaves pages too narrow shows only the results`() {
        assertEquals(SearchPanels.ResultsOnly, plan(800, 700, verticalHinge(left = 388, right = 412, height = 700)))
    }

    @Test
    fun `right to left windows plan the same panels`() {
        assertEquals(SearchPanels.ResultsAndPreview, plan(1180, 740, direction = LayoutDirection.Rtl))
    }
}
