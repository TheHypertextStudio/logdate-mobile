@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.search.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import app.logdate.ui.foldable.rememberFoldableLayoutInfo
import app.logdate.ui.foldable.rememberWindowOrigin
import app.logdate.ui.workspace.AdaptiveWorkspaceLayout
import app.logdate.ui.workspace.PanelContainment
import app.logdate.ui.workspace.WorkspacePanel

/**
 * Places search in the workspace's panels for the space it has and the fold it sits across.
 *
 * With room for three panels the narrowing options, the results and the preview sit side by side.
 * With room for two, the results and the preview do, and the narrowing options become a row above
 * the results. Folded, the results take one page, or the lower half, and the preview the other.
 * Otherwise only the results show, under the narrowing row.
 *
 * Content responds only to its panel: the layout decides which panels exist and where.
 *
 * @param narrowing The narrowing options, given the arrangement that suits where they are placed.
 * @param results The results, told which panels are showing so choosing a result can open the
 *   preview beside them or the result itself full width.
 * @param preview What the chosen result looks like. Only called when a preview panel is showing.
 */
@Composable
fun SearchWorkspaceLayout(
    narrowing: @Composable (SearchNarrowingLayout) -> Unit,
    results: @Composable (SearchPanels) -> Unit,
    preview: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val direction = LocalLayoutDirection.current
    val foldable = rememberFoldableLayoutInfo()
    val (origin, originModifier) = rememberWindowOrigin()
    BoxWithConstraints(modifier.then(originModifier)) {
        val panels = planSearchPanels(maxWidth, maxHeight, foldable, origin, direction)
        when (panels) {
            SearchPanels.ResultsOnly ->
                Column(Modifier.fillMaxSize()) {
                    narrowing(SearchNarrowingLayout.Row)
                    results(panels)
                }
            SearchPanels.ResultsAndPreview ->
                AdaptiveWorkspaceLayout(
                    modifier = Modifier.fillMaxSize(),
                    focusConstraints = SearchPanelConstraints.PreviewFocus,
                    browseConstraints = SearchPanelConstraints.ResultsBeside,
                    browseOnStart = true,
                    browse = {
                        CollectionPanel {
                            narrowing(SearchNarrowingLayout.Row)
                            results(panels)
                        }
                    },
                    focus = { WorkspacePanel(Modifier.fillMaxSize()) { preview() } },
                )
            SearchPanels.NarrowingResultsAndPreview ->
                AdaptiveWorkspaceLayout(
                    modifier = Modifier.fillMaxSize(),
                    focusConstraints = SearchPanelConstraints.ResultsBetween,
                    browseConstraints = SearchPanelConstraints.Narrowing,
                    inspectorConstraints = SearchPanelConstraints.PreviewTrailing,
                    browseOnStart = true,
                    browse = { CollectionPanel { narrowing(SearchNarrowingLayout.Panel) } },
                    focus = { CollectionPanel { results(panels) } },
                    inspector = { WorkspacePanel(Modifier.fillMaxSize()) { preview() } },
                )
        }
    }
}

/** A panel whose content is already in tonal groups, so the panel adds no surface behind them. */
@Composable
private fun CollectionPanel(content: @Composable () -> Unit) {
    WorkspacePanel(Modifier.fillMaxSize(), containment = PanelContainment.Collection) {
        Column(Modifier.fillMaxSize()) { content() }
    }
}
