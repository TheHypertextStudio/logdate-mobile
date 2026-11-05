@file:Suppress("ktlint:standard:function-naming")

package app.logdate.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.logdate.ui.theme.Spacing
import logdate.client.ui.generated.resources.Res
import logdate.client.ui.generated.resources.common_back
import org.jetbrains.compose.resources.stringResource

/**
 * Shared scaffold for settings screens.
 *
 * Encapsulates the common boilerplate: collapsing [LargeTopAppBar] with a back button,
 * [DefaultSettingsContentContainer] width constraint, navigation-bar insets, and a
 * [LazyColumn] with standard spacing. Screens supply only their title and list items.
 *
 * @param title The screen title shown in the collapsing top bar.
 * @param onBack Callback when the user taps the back arrow.
 * @param modifier Modifier applied to the root scaffold.
 * @param snackbarHostState Optional snackbar host; when non-null, a [SnackbarHost] is shown.
 * @param topBarColors Optional custom colors for the [LargeTopAppBar].
 * @param content Items to display inside the [LazyColumn].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState? = null,
    topBarColors: TopAppBarColors? = null,
    maxContentWidth: Dp = 600.dp,
    actions: @Composable RowScope.() -> Unit = {},
    compactHeader: Boolean = false,
    content: LazyListScope.() -> Unit,
) {
    val layoutDirection = LocalLayoutDirection.current
    val scrollBehavior = if (compactHeader) {
        TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    } else {
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    }
    val navigationIcon: @Composable () -> Unit = {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(Res.string.common_back))
        }
    }

    Scaffold(
        modifier =
            modifier
                .applyScreenStyles()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val sideInset = if (maxWidth > maxContentWidth) (maxWidth - maxContentWidth) / 2 else 0.dp
                if (compactHeader) {
                    TopAppBar(
                        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        navigationIcon = navigationIcon,
                        modifier = Modifier.padding(horizontal = sideInset),
                        scrollBehavior = scrollBehavior,
                        actions = actions,
                        colors = topBarColors ?: TopAppBarDefaults.topAppBarColors(),
                    )
                } else {
                    LargeTopAppBar(
                        title = { Text(title) },
                        navigationIcon = navigationIcon,
                        modifier = Modifier.padding(horizontal = sideInset),
                        scrollBehavior = scrollBehavior,
                        actions = actions,
                        colors = topBarColors ?: TopAppBarDefaults.topAppBarColors(),
                    )
                }
            }
        },
        snackbarHost = {
            if (snackbarHostState != null) {
                SnackbarHost(snackbarHostState)
            }
        },
    ) { paddingValues ->
        DefaultSettingsContentContainer(maxWidth = maxContentWidth) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().consumeWindowInsets(paddingValues),
                // Add a margin on top of the scaffold inset so the first item keeps breathing
                // room below the top bar — including when it is fully collapsed and would
                // otherwise sit flush against the content.
                contentPadding =
                    PaddingValues(
                        start = paddingValues.calculateStartPadding(layoutDirection),
                        end = paddingValues.calculateEndPadding(layoutDirection),
                        top = paddingValues.calculateTopPadding() + Spacing.md,
                        bottom = paddingValues.calculateBottomPadding(),
                    ),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                content()
            }
        }
    }
}
