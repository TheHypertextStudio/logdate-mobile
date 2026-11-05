@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.account.hosting

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.logdate.feature.core.settings.account.ConnectedServerInfo
import app.logdate.feature.core.settings.account.ServerHealth
import app.logdate.ui.common.MaterialContainer
import app.logdate.ui.common.SettingsNavigationItem
import app.logdate.ui.common.SettingsRow
import app.logdate.ui.common.SettingsScaffold
import app.logdate.ui.common.SettingsSection
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.hosting_address
import logdate.client.feature.core.generated.resources.hosting_check_again
import logdate.client.feature.core.generated.resources.hosting_check_failed
import logdate.client.feature.core.generated.resources.hosting_checking
import logdate.client.feature.core.generated.resources.hosting_connected
import logdate.client.feature.core.generated.resources.hosting_connected_version
import logdate.client.feature.core.generated.resources.hosting_intro
import logdate.client.feature.core.generated.resources.hosting_not_checked
import logdate.client.feature.core.generated.resources.hosting_status
import logdate.client.feature.core.generated.resources.hosting_title
import logdate.client.feature.core.generated.resources.hosting_unreachable
import logdate.client.feature.core.generated.resources.move_hosting_row_description
import logdate.client.feature.core.generated.resources.move_title
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** Shows the server the account lives on and whether it is answering. */
@Composable
fun HostingScreen(
    onBack: () -> Unit,
    onMoveAccount: () -> Unit,
    viewModel: HostingViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    HostingContent(state = state, onBack = onBack, onCheckAgain = viewModel::checkAgain, onMoveAccount = onMoveAccount)
}

@Composable
fun HostingContent(
    state: HostingUiState,
    onBack: () -> Unit,
    onCheckAgain: () -> Unit,
    onMoveAccount: () -> Unit = {},
) {
    SettingsScaffold(title = stringResource(Res.string.hosting_title), onBack = onBack) {
        item {
            Text(
                text = stringResource(Res.string.hosting_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.lg),
            )
        }
        val server = state.server
        if (server == null) {
            item { StatusItem(health = null, check = state.check, onCheckAgain = onCheckAgain) }
            return@SettingsScaffold
        }
        item {
            SettingsSection(
                title = server.displayName ?: server.host,
                modifier = Modifier.padding(horizontal = Spacing.lg),
            ) {
                SettingsRow(
                    leadingContent = { Icon(Icons.Outlined.Language, contentDescription = null) },
                    overlineContent = { Text(stringResource(Res.string.hosting_address)) },
                    headlineContent = { Text(server.host) },
                )
                StatusItem(health = state.health, check = state.check, onCheckAgain = onCheckAgain)
            }
        }
        if (state.canMoveAccount) {
            item {
                MaterialContainer(modifier = Modifier.padding(horizontal = Spacing.lg)) {
                    SettingsNavigationItem(
                        title = stringResource(Res.string.move_title),
                        description = stringResource(Res.string.move_hosting_row_description),
                        icon = { Icon(Icons.Outlined.SwapHoriz, contentDescription = null) },
                        onClick = onMoveAccount,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusItem(
    health: ServerHealth?,
    check: ServerCheckState,
    onCheckAgain: () -> Unit,
) {
    val displayedHealth = if (check == ServerCheckState.Checking) null else health
    SettingsRow(
        leadingContent = {
            if (check == ServerCheckState.Failed) {
                Icon(Icons.Outlined.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            } else {
                when (displayedHealth) {
                    null ->
                        if (check == ServerCheckState.Checking) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Outlined.Language, contentDescription = null)
                        }
                    is ServerHealth.Reachable ->
                        Icon(
                            Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    ServerHealth.Unreachable ->
                        Icon(
                            Icons.Outlined.CloudOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                }
            }
        },
        overlineContent = { Text(stringResource(Res.string.hosting_status)) },
        headlineContent = {
            Text(
                when {
                    check == ServerCheckState.Failed -> stringResource(Res.string.hosting_check_failed)
                    displayedHealth == null ->
                        stringResource(
                            if (check == ServerCheckState.Checking) Res.string.hosting_checking else Res.string.hosting_not_checked,
                        )
                    displayedHealth is ServerHealth.Reachable ->
                        displayedHealth.version?.let { stringResource(Res.string.hosting_connected_version, it) }
                            ?: stringResource(Res.string.hosting_connected)
                    else -> stringResource(Res.string.hosting_unreachable)
                },
            )
        },
        trailingContent = {
            if (check != ServerCheckState.Checking &&
                (check == ServerCheckState.Failed || displayedHealth == null || displayedHealth == ServerHealth.Unreachable)
            ) {
                TextButton(onClick = onCheckAgain) { Text(stringResource(Res.string.hosting_check_again)) }
            }
        },
    )
}

@Preview
@Composable
private fun HostingPreview() {
    HostingContent(
        state =
            HostingUiState(
                server =
                    ConnectedServerInfo(
                        origin = "https://cloud.logdate.app",
                        displayName = "LogDate Cloud",
                        isLogDateCloud = true,
                        publishesIdentityChanges = false,
                    ),
                health = ServerHealth.Reachable("1.4.0"),
            ),
        onBack = {},
        onCheckAgain = {},
    )
}
