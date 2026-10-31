@file:Suppress("ktlint:standard:function-naming")

package app.logdate.ui.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.logdate.ui.foldable.FoldableHingeOrientation
import app.logdate.ui.foldable.rememberFoldableLayoutInfo
import app.logdate.ui.foldable.rememberWindowOrigin
import app.logdate.ui.theme.Spacing
import app.logdate.ui.workspace.AdaptiveWorkspaceLayout
import app.logdate.ui.workspace.LocalWorkspaceHosted
import app.logdate.ui.workspace.LocalWorkspaceSearchController
import app.logdate.ui.workspace.PanelConstraints
import app.logdate.ui.workspace.WorkspaceAppBar
import app.logdate.ui.workspace.WorkspaceSearchHost
import app.logdate.ui.workspace.resolveWorkspaceComposition

val LocalActivityHosted = staticCompositionLocalOf { false }

object ActivityPanelConstraints {
    fun relatedBrowse(fontScale: Float) = PanelConstraints(320.dp * fontScale, 400.dp * fontScale, 440.dp * fontScale)

    fun relatedFocus(fontScale: Float) = PanelConstraints(480.dp * fontScale, 720.dp * fontScale, 960.dp)
}

@Composable
fun ActivityRouteFrame(
    composePanels: Boolean = true,
    content: @Composable () -> Unit,
) {
    if (LocalActivityHosted.current || LocalWorkspaceHosted.current) {
        content()
        return
    }
    WorkspaceSearchHost {
        val controller = LocalWorkspaceSearchController.current
        val query = controller?.binding
        val action = controller?.actionBinding
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                if (query != null || action != null) {
                    ActivitySearchHeader(
                        title = query?.hint ?: action?.title.orEmpty(),
                        onSearch = action?.onSearch ?: {},
                        related = !composePanels,
                    )
                }
            },
        ) { insets ->
            CompositionLocalProvider(LocalActivityHosted provides true) {
                val modifier = Modifier.fillMaxSize().padding(insets)
                if (composePanels) {
                    AdaptiveWorkspaceLayout(
                        modifier,
                        focusConstraints = PanelConstraints.Reading.copy(maximumWidth = 960.dp),
                        focus = content,
                    )
                } else {
                    Box(modifier) { content() }
                }
            }
        }
    }
}

@Composable
private fun ActivitySearchHeader(
    title: String,
    onSearch: () -> Unit,
    related: Boolean,
) {
    val foldable = rememberFoldableLayoutInfo()
    val direction = LocalLayoutDirection.current
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    Box(
        Modifier.fillMaxWidth().windowInsetsPadding(
            WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        ),
    ) {
        val (origin, trackOrigin) = rememberWindowOrigin()
        BoxWithConstraints(Modifier.fillMaxWidth().then(trackOrigin), contentAlignment = AbsoluteAlignment.TopLeft) {
            val hinge = foldable.hinge
            val placement =
                if (hinge?.isSeparating == true && hinge.orientation == FoldableHingeOrientation.Vertical) {
                    val composition =
                        resolveWorkspaceComposition(
                            width = maxWidth,
                            height = maxHeight,
                            focus =
                                if (related) {
                                    ActivityPanelConstraints.relatedFocus(
                                        fontScale,
                                    )
                                } else {
                                    PanelConstraints.Reading.copy(maximumWidth = 960.dp)
                                },
                            browse = if (related) ActivityPanelConstraints.relatedBrowse(fontScale) else null,
                            foldable = foldable,
                            origin = origin,
                            browseOnStart = related,
                            layoutDirection = direction,
                        )
                    val source = composition.browse ?: composition.focus
                    Modifier
                        .absoluteOffset { IntOffset((source.x - Spacing.lg).roundToPx(), 0) }
                        .width(source.width + Spacing.lg * 2)
                } else {
                    Modifier.fillMaxWidth()
                }
            WorkspaceAppBar(title, onSearch, placement)
        }
    }
}
