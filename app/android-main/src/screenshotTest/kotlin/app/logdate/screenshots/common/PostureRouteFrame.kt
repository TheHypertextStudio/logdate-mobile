package app.logdate.screenshots.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.DpOffset
import app.logdate.ui.foldable.FoldableLayoutInfo
import app.logdate.ui.foldable.LocalWindowOriginOverride
import app.logdate.ui.foldable.provideFoldableLayoutInfo
import app.logdate.ui.workspace.PanelConstraints
import app.logdate.ui.workspace.WorkspaceRouteFrame

/**
 * Renders [content] the way a direct link opens it on a folded device: the workspace composition
 * places the panel around the fold, so the feature only responds to its panel's bounds.
 */
@Composable
fun PostureRouteFrame(
    foldableLayoutInfo: FoldableLayoutInfo,
    focusConstraints: PanelConstraints = PanelConstraints.Reading,
    content: @Composable () -> Unit,
) {
    provideFoldableLayoutInfo(foldableLayoutInfo) {
        // One rendered frame cannot measure where the route sits; it fills the window from its origin.
        CompositionLocalProvider(LocalWindowOriginOverride provides DpOffset.Zero) {
            ScreenshotTheme {
                WorkspaceRouteFrame(focusConstraints = focusConstraints, content = content)
            }
        }
    }
}
