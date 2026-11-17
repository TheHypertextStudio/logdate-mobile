@file:Suppress("ktlint:standard:function-naming", "ktlint:standard:no-wildcard-imports")

package app.logdate.feature.rewind.ui.past

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import app.logdate.feature.rewind.ui.RewindOpenCallback
import app.logdate.feature.rewind.ui.overview.PastRewindCard
import app.logdate.feature.rewind.ui.overview.RewindHistoryUiState
import app.logdate.ui.platform.PlatformIcons
import app.logdate.ui.theme.Spacing
import logdate.client.feature.rewind.generated.resources.*
import logdate.client.feature.rewind.generated.resources.Res
import logdate.client.feature.rewind.generated.resources.past_rewinds_empty
import logdate.client.ui.generated.resources.common_back
import org.jetbrains.compose.resources.stringResource
import logdate.client.ui.generated.resources.Res as UiRes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PastRewindsScreen(
    onGoBack: () -> Unit,
    rewinds: List<RewindHistoryUiState> = emptyList(),
    onOpenRewind: RewindOpenCallback = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.past_rewind)) },
                navigationIcon = {
                    IconButton(onClick = { onGoBack() }) {
                        Icon(
                            painter = PlatformIcons.back(),
                            contentDescription = stringResource(UiRes.string.common_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (rewinds.isEmpty()) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = Spacing.lg),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(Res.string.past_rewinds_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                contentPadding =
                    PaddingValues(
                        horizontal = Spacing.lg,
                        vertical = Spacing.sm,
                    ),
                verticalArrangement =
                    androidx.compose.foundation.layout.Arrangement
                        .spacedBy(Spacing.sm),
                modifier = Modifier.padding(padding),
            ) {
                items(rewinds, key = { it.uid }) { history ->
                    PastRewindCard(history = history, onOpenRewind = onOpenRewind)
                }
                item {
                    Spacer(
                        Modifier.windowInsetsBottomHeight(
                            WindowInsets.systemBars,
                        ),
                    )
                }
            }
        }
    }
}
