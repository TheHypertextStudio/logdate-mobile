package app.logdate.feature.search.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.logdate.ui.foldable.FoldableLayoutInfo
import app.logdate.ui.workspace.PanelConstraints
import app.logdate.ui.workspace.resolveWorkspaceComposition

/** Which panels search shows. */
enum class SearchPanels {
    /** Only the results, with the narrowing options above them. Choosing a result opens it full width. */
    ResultsOnly,

    /** The results beside a preview of the chosen result, with the narrowing options above the results. */
    ResultsAndPreview,
}

/**
 * The panel sizes search asks the workspace layout for. The plan and the layout both read them, so
 * the panels the plan promises are the panels the layout places.
 */
internal object SearchPanelConstraints {
    /** The results beside a preview, as for other collections with a detail. */
    val ResultsBeside = PanelConstraints(320.dp, 400.dp, 440.dp)

    /** The preview of a result, as for other details. */
    val PreviewFocus = PanelConstraints(480.dp, 720.dp, 960.dp)
}

/**
 * Chooses the panels search shows for a window of [width] by [height] with the given fold.
 *
 * The workspace layout decides what fits, and this asks it with the same constraints the layout
 * will use. Search never shows more than two panels: the results and a preview of the chosen result,
 * side by side, or on either side of a fold. Room beyond that is left empty.
 */
fun planSearchPanels(
    width: Dp,
    height: Dp,
    foldable: FoldableLayoutInfo,
    origin: DpOffset,
    layoutDirection: LayoutDirection,
): SearchPanels {
    val composition =
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
    return if (composition.browse != null) SearchPanels.ResultsAndPreview else SearchPanels.ResultsOnly
}
