@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.library.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.logdate.feature.library.ui.components.MediaThumbnailGrid
import app.logdate.ui.platform.PlatformIcons
import app.logdate.ui.workspace.PanelContainment
import app.logdate.ui.workspace.WorkspacePanel
import logdate.client.feature.library.generated.resources.Res
import logdate.client.feature.library.generated.resources.library_empty_body
import logdate.client.feature.library.generated.resources.library_empty_headline
import logdate.client.feature.library.generated.resources.library_error_body
import logdate.client.feature.library.generated.resources.library_error_headline
import logdate.client.feature.library.generated.resources.library_permission_required_body
import logdate.client.feature.library.generated.resources.library_permission_required_headline
import logdate.client.ui.generated.resources.common_try_again
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid
import logdate.client.ui.generated.resources.Res as UiRes

/**
 * The main content surface for the library.
 *
 * Uses `surface` color so the panel sits visually above the shell background.
 * The same panel renders for all states (loading, empty, content) so layout is
 * consistent regardless of data availability.
 */
@Composable
fun LibraryPanel(
    state: LibraryUiState,
    onItemClick: (Uuid) -> Unit,
    onRetry: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        WorkspacePanel(Modifier.fillMaxSize(), containment = PanelContainment.Collection) {
            val showLoading = state is LibraryUiState.Loading
            LibraryLoadingPlaceholder(isVisible = showLoading)
            AnimatedVisibility(
                visible = !showLoading,
                enter = fadeIn(animationSpec = tween(200)),
                exit = fadeOut(animationSpec = tween(200)),
            ) {
                Column(Modifier.fillMaxSize()) {
                    when (state) {
                        is LibraryUiState.Loading -> {
                            // Handled by placeholder above
                        }

                        is LibraryUiState.PermissionRequired -> {
                            LibraryEmptyState(
                                headline = stringResource(Res.string.library_permission_required_headline),
                                body = stringResource(Res.string.library_permission_required_body),
                            )
                        }

                        is LibraryUiState.Empty -> {
                            LibraryEmptyState(
                                headline = stringResource(Res.string.library_empty_headline),
                                body = stringResource(Res.string.library_empty_body),
                            )
                        }

                        is LibraryUiState.Error -> {
                            LibraryEmptyState(
                                headline = stringResource(Res.string.library_error_headline),
                                body = stringResource(Res.string.library_error_body),
                                onRetry = onRetry,
                            )
                        }

                        is LibraryUiState.Content -> {
                            MediaThumbnailGrid(
                                groups = state.groups,
                                onItemClick = onItemClick,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Placeholder shimmer shown while the library is loading.
 */
@Composable
private fun LibraryLoadingPlaceholder(
    isVisible: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        // Empty for now — can be replaced with a shimmer grid
    }
}

/**
 * Empty state with a friendly illustration and message, displayed inside the library panel.
 */
@Composable
private fun LibraryEmptyState(
    headline: String,
    body: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(80.dp),
        ) {
            Icon(
                painter = PlatformIcons.camera(),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier =
                    Modifier
                        .padding(20.dp)
                        .size(40.dp),
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = headline,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (onRetry != null) {
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onRetry) { Text(stringResource(UiRes.string.common_try_again)) }
        }
    }
}
