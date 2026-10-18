package app.logdate.feature.search.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.logdate.ui.foldable.FoldableLayoutInfo
import app.logdate.ui.foldable.relativeTo
import app.logdate.ui.workspace.PanelConstraints
import app.logdate.ui.workspace.resolveWorkspaceComposition

/** Which panels search shows. */
enum class SearchPanels {
    /** Only the results, with the narrowing options above them. Choosing a result opens it full width. */
    ResultsOnly,

    /** The results beside a preview of the chosen result, with the narrowing options above the results. */
    ResultsAndPreview,

    /** The narrowing options, the results and the preview side by side. */
    NarrowingResultsAndPreview,
}

/**
 * The panel sizes search asks the workspace layout for. The plan and the layout both read them, so
 * the panels the plan promises are the panels the layout places.
 */
internal object SearchPanelConstraints {
    /** The narrowing panel, when all three panels fit. */
    val Narrowing = PanelConstraints(260.dp, 280.dp, 300.dp)

    /** The results, when they sit between the narrowing panel and the preview. */
    val ResultsBetween = PanelConstraints(360.dp, 460.dp, 560.dp)

    /** The preview beside the results, when all three panels fit. */
    val PreviewTrailing = PanelConstraints(360.dp, 440.dp, 600.dp)

    /** The results beside a preview, as for other collections with a detail. */
    val ResultsBeside = PanelConstraints(320.dp, 400.dp, 440.dp)

    /** The preview of a result, as for other details. */
    val PreviewFocus = PanelConstraints(480.dp, 720.dp, 960.dp)
}

/**
 * Chooses the panels search shows for a window of [width] by [height] with the given fold.
 *
 * The workspace layout decides what fits, and this asks it with the same constraints the layout
 * will use. Flat windows try all three panels, then results with a preview. Folded windows put the
 * results on one page, or above the fold, and the preview on the other.
 */
fun planSearchPanels(
    width: Dp,
    height: Dp,
    foldable: FoldableLayoutInfo,
    origin: DpOffset,
    layoutDirection: LayoutDirection,
): SearchPanels {
    val separated = foldable.relativeTo(origin).hinge?.isSeparating == true
    if (!separated) {
        val three =
            resolveWorkspaceComposition(
                width = width,
                height = height,
                focus = SearchPanelConstraints.ResultsBetween,
                browse = SearchPanelConstraints.Narrowing,
                inspector = SearchPanelConstraints.PreviewTrailing,
                foldable = foldable,
                origin = origin,
                browseOnStart = true,
                layoutDirection = layoutDirection,
            )
        if (three.browse != null && three.inspector != null) return SearchPanels.NarrowingResultsAndPreview
    }
    val two =
        resolveWorkspaceComposition(
            width = width,
            height = height,
            focus = SearchPanelConstraints.PreviewFocus,
            browse = SearchPanelConstraints.ResultsBeside,
            foldable = foldable,
            origin = origin,
            browseOnStart = true,
            layoutDirection = layoutDirection,
        )
    return if (two.browse != null) SearchPanels.ResultsAndPreview else SearchPanels.ResultsOnly
}
