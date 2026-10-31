package app.logdate.screenshots.common

import androidx.compose.runtime.Composable
import app.logdate.screenshots.audit.workspace.WorkspaceScene

/** A Home destination, keyed the way the shared workspace shell names it. */
enum class RoutePreviewTab(
    val workspaceKey: String,
) {
    TIMELINE("timeline"),
    LOCATION("locations"),
    JOURNALS("journals"),
    REWIND("rewind"),
}

/** Renders [content] as [selectedTab] inside the shared Home workspace shell, with its search and account actions. */
@Composable
fun HomeTabRouteFrame(
    selectedTab: RoutePreviewTab,
    content: @Composable () -> Unit,
) {
    WorkspaceScene(selectedTab.workspaceKey, content = content)
}

/** Renders a Home destination with an opened detail, placed by the shared workspace composition. */
@Composable
fun HomeDetailRouteFrame(
    selectedTab: RoutePreviewTab,
    mainContent: @Composable () -> Unit,
    detailContent: @Composable () -> Unit,
) {
    WorkspaceScene(selectedTab.workspaceKey, detail = detailContent, content = mainContent)
}
