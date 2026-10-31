@file:Suppress("ktlint:standard:function-naming")

package app.logdate.ui.workspace

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.logdate.ui.theme.Spacing
import app.logdate.ui.theme.WorkspaceSurface
import app.logdate.ui.theme.workspaceContainer
import logdate.client.ui.generated.resources.Res
import logdate.client.ui.generated.resources.app_name
import org.jetbrains.compose.resources.stringResource

/** Frames direct links and nested details; a composed Home panel already has a host. */
@Composable
fun WorkspaceRouteFrame(
    composePanels: Boolean = true,
    focusConstraints: PanelConstraints = PanelConstraints.Reading,
    content: @Composable () -> Unit,
) {
    if (LocalWorkspaceHosted.current) {
        content()
        return
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.workspaceContainer(WorkspaceSurface.Canvas),
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { insets ->
        CompositionLocalProvider(LocalWorkspaceHosted provides true) {
            WorkspaceSearchHost {
                Column(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)) {
                    LocalWorkspaceSearchAction.current?.let { onSearch ->
                        if (focusConstraints.maximumWidth != null) {
                            BoxWithConstraints {
                                WorkspaceAppBar(
                                    stringResource(Res.string.app_name),
                                    onSearch,
                                    Modifier
                                        .padding(start = if (maxWidth >= 600.dp) Spacing.lg else 0.dp)
                                        .widthIn(max = focusConstraints.maximumWidth),
                                )
                            }
                        } else {
                            WorkspaceAppBar(stringResource(Res.string.app_name), onSearch)
                        }
                    }
                    if (composePanels) {
                        AdaptiveWorkspaceLayout(Modifier.weight(1f), focusConstraints = focusConstraints, focus = content)
                    } else {
                        androidx.compose.foundation.layout
                            .Box(Modifier.weight(1f)) { content() }
                    }
                }
            }
        }
    }
}
