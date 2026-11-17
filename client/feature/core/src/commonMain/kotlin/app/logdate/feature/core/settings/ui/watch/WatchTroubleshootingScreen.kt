@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui.watch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.logdate.ui.adaptive.FoldableBookLayout
import app.logdate.ui.common.SettingsScaffold
import app.logdate.ui.common.SettingsSection
import app.logdate.ui.common.SimpleSettingsItem
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.watch_troubleshooting
import logdate.client.feature.core.generated.resources.watch_troubleshooting_common_issues
import logdate.client.feature.core.generated.resources.watch_troubleshooting_install
import logdate.client.feature.core.generated.resources.watch_troubleshooting_open
import logdate.client.feature.core.generated.resources.watch_troubleshooting_pair
import logdate.client.feature.core.generated.resources.watch_troubleshooting_tips
import logdate.client.feature.core.generated.resources.watch_troubleshooting_watch_app
import org.jetbrains.compose.resources.stringResource

/**
 * Troubleshooting screen for watch connection and sync issues.
 *
 * Receives a shared [WatchSettingsViewModel] from the parent navigation graph
 * to avoid creating a duplicate ViewModel with its own Wearable API listeners.
 */
@Composable
fun WatchTroubleshootingScreen(
    onBack: () -> Unit,
    viewModel: WatchSettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val requestFailure by viewModel.requestFailure.collectAsStateWithLifecycle()

    WatchTroubleshootingContent(
        connectionState = connectionState,
        requestFailure = requestFailure,
        onBack = onBack,
        onBeginAssociation = viewModel::beginAssociation,
        onInstallOnWatch = viewModel::installAppOnWatch,
        onOpenOnWatch = viewModel::openAppOnWatch,
        modifier = modifier,
    )
}

@Composable
fun WatchTroubleshootingContent(
    connectionState: WatchConnectionState,
    requestFailure: WatchRequestFailure?,
    onBack: () -> Unit,
    onBeginAssociation: () -> Unit,
    onInstallOnWatch: () -> Unit,
    onOpenOnWatch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FoldableBookLayout(
        modifier = modifier.fillMaxSize(),
        minPaneWidth = 320.dp,
        startPane = {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = Spacing.lg),
            ) {
                WatchTroubleshootingActions(
                    connectionState = connectionState,
                    requestFailure = requestFailure,
                    onBeginAssociation = onBeginAssociation,
                    onInstallOnWatch = onInstallOnWatch,
                    onOpenOnWatch = onOpenOnWatch,
                )
            }
        },
        endPane = {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = Spacing.lg),
            ) {
                TroubleshootingTips()
            }
        },
        standardContent = {
            SettingsScaffold(
                title = stringResource(Res.string.watch_troubleshooting),
                onBack = onBack,
                modifier = modifier,
            ) {
                item {
                    WatchTroubleshootingActions(
                        connectionState = connectionState,
                        requestFailure = requestFailure,
                        onBeginAssociation = onBeginAssociation,
                        onInstallOnWatch = onInstallOnWatch,
                        onOpenOnWatch = onOpenOnWatch,
                    )
                }

                item {
                    TroubleshootingTips()
                }
            }
        },
    )
}

/**
 * The watch app actions as rows of one settings section. A failed request is explained below the
 * section rather than inside it, where the container's rounded corners would cut into the text.
 */
@Composable
private fun WatchTroubleshootingActions(
    connectionState: WatchConnectionState,
    requestFailure: WatchRequestFailure?,
    onBeginAssociation: () -> Unit,
    onInstallOnWatch: () -> Unit,
    onOpenOnWatch: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        SettingsSection(title = stringResource(Res.string.watch_troubleshooting_watch_app)) {
            SimpleSettingsItem(
                title = stringResource(Res.string.watch_troubleshooting_pair),
                description = null,
                onClick = onBeginAssociation,
                enabled =
                    connectionState is WatchConnectionState.NeedsAssociation ||
                        connectionState is WatchConnectionState.NoPairedWatch,
                leadingContent = { Icon(Icons.Default.Info, contentDescription = null) },
                action = {},
            )
            if (connectionState is WatchConnectionState.AppNotInstalled) {
                SimpleSettingsItem(
                    title = stringResource(Res.string.watch_troubleshooting_install),
                    description = null,
                    onClick = onInstallOnWatch,
                    leadingContent = { Icon(Icons.Default.Download, contentDescription = null) },
                    action = {},
                )
            }
            SimpleSettingsItem(
                title = stringResource(Res.string.watch_troubleshooting_open),
                description = null,
                onClick = onOpenOnWatch,
                enabled = connectionState is WatchConnectionState.Connected || connectionState is WatchConnectionState.AppUnknown,
                leadingContent = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
                action = {},
            )
        }
        requestFailure?.let { WatchRequestFailureMessage(it) }
    }
}

@Composable
private fun TroubleshootingTips() {
    Surface {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(Res.string.watch_troubleshooting_common_issues),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Text(
                text = stringResource(Res.string.watch_troubleshooting_tips),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
