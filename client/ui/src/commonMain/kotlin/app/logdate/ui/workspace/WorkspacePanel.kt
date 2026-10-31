@file:Suppress("ktlint:standard:function-naming")

package app.logdate.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.logdate.ui.theme.Spacing
import app.logdate.ui.theme.WorkspaceSurface
import app.logdate.ui.theme.workspaceContainer
import app.logdate.ui.theme.workspaceContent

private val LocalPanelFramed = staticCompositionLocalOf { false }

enum class PanelContainment { Working, Collection }

val LocalWorkspaceHosted = staticCompositionLocalOf { false }
val LocalPanelLayoutInfo = staticCompositionLocalOf<PanelLayoutInfo?> { null }

/** Contained controls inherit the canvas; only an overlapping sticky bar needs a backdrop. */
@Composable
fun Modifier.workspaceControlBackdrop(raised: Boolean): Modifier =
    if (raised) background(MaterialTheme.colorScheme.workspaceContainer(WorkspaceSurface.Raised)) else this

fun panelShape(
    base: CornerBasedShape,
    placement: PanelPlacement,
): CornerBasedShape =
    when (placement) {
        PanelPlacement.Floating -> base
        PanelPlacement.EdgeAttached, PanelPlacement.Lower -> base.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp))
        PanelPlacement.Upper -> base.copy(topStart = CornerSize(0.dp), topEnd = CornerSize(0.dp))
    }

@Composable
fun WorkspacePanel(
    modifier: Modifier = Modifier,
    role: PanelRole = LocalPanelLayoutInfo.current?.role ?: PanelRole.Focus,
    placement: PanelPlacement = LocalPanelLayoutInfo.current?.placement ?: PanelPlacement.EdgeAttached,
    containment: PanelContainment = PanelContainment.Working,
    content: @Composable (PanelLayoutInfo) -> Unit,
) {
    if (LocalPanelFramed.current) {
        BoxWithConstraints(modifier.fillMaxSize()) {
            content(PanelLayoutInfo(role, placement, maxWidth, maxHeight))
        }
        return
    }
    if (containment == PanelContainment.Collection) {
        BoxWithConstraints(modifier.fillMaxSize()) {
            val info = PanelLayoutInfo(role, placement, maxWidth, maxHeight)
            CompositionLocalProvider(LocalPanelLayoutInfo provides info, LocalPanelFramed provides true) { content(info) }
        }
        return
    }
    Surface(
        modifier = modifier,
        shape = panelShape(MaterialTheme.shapes.extraLarge, placement),
        color = MaterialTheme.colorScheme.workspaceContainer(WorkspaceSurface.Panel),
        contentColor = MaterialTheme.colorScheme.workspaceContent(WorkspaceSurface.Panel),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val info = PanelLayoutInfo(role, placement, maxWidth, maxHeight)
            CompositionLocalProvider(LocalPanelLayoutInfo provides info, LocalPanelFramed provides true) { content(info) }
        }
    }
}

enum class PanelHeaderRole { Context, Destination }

@Composable
fun PanelHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    role: PanelHeaderRole = PanelHeaderRole.Context,
    actions: @Composable RowScope.() -> Unit = {},
) {
    BoxWithConstraints(modifier.padding(Spacing.lg)) {
        val stacked = maxWidth < 320.dp || LocalDensity.current.fontScale >= 1.5f
        val text: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    title,
                    style =
                        if (role ==
                            PanelHeaderRole.Destination
                        ) {
                            MaterialTheme.typography.headlineMedium
                        } else {
                            MaterialTheme.typography.titleLarge
                        },
                    modifier = Modifier.semantics { heading() },
                )
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        if (stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                text()
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), content = actions)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Column(Modifier.weight(1f)) { text() }
                actions()
            }
        }
    }
}

@Composable
fun PanelGroup(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    content: @Composable () -> Unit,
) {
    val meaning = if (selected) WorkspaceSurface.Selected else WorkspaceSurface.Group
    Surface(
        modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.workspaceContainer(meaning),
        contentColor = MaterialTheme.colorScheme.workspaceContent(meaning),
    ) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) { content() }
    }
}
