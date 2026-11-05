@file:Suppress("ktlint:standard:function-naming", "ktlint:standard:max-line-length")

package app.logdate.feature.core.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.logdate.client.text.asString
import app.logdate.feature.core.settings.updates.AppUpdateFlowType
import app.logdate.feature.core.settings.updates.AppUpdateStatus
import app.logdate.feature.core.settings.updates.AppUpdateUiState
import app.logdate.ui.common.MaterialContainer
import app.logdate.ui.common.SettingsNavigationItem
import app.logdate.ui.common.SettingsRow
import app.logdate.ui.common.SettingsScaffold
import app.logdate.ui.common.SettingsSection
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.about_logdate
import logdate.client.feature.core.generated.resources.app_update_available
import logdate.client.feature.core.generated.resources.app_update_check_failed
import logdate.client.feature.core.generated.resources.app_update_checking
import logdate.client.feature.core.generated.resources.app_update_downloaded
import logdate.client.feature.core.generated.resources.app_update_downloading
import logdate.client.feature.core.generated.resources.app_update_immediate_required
import logdate.client.feature.core.generated.resources.app_update_restart_action
import logdate.client.feature.core.generated.resources.app_update_unsupported
import logdate.client.feature.core.generated.resources.app_update_up_to_date
import logdate.client.feature.core.generated.resources.app_updates
import logdate.client.feature.core.generated.resources.app_updates_description
import logdate.client.feature.core.generated.resources.app_version_label
import logdate.client.feature.core.generated.resources.app_version_with_build_label
import logdate.client.feature.core.generated.resources.check_for_updates
import logdate.client.feature.core.generated.resources.developer_mode_save_failed
import logdate.client.feature.core.generated.resources.developer_tools
import logdate.client.feature.core.generated.resources.developer_tools_description
import logdate.client.feature.core.generated.resources.hide_developer_mode
import logdate.client.feature.core.generated.resources.labs_settings_description
import logdate.client.feature.core.generated.resources.labs_settings_title
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/** App information and updates, with deliberate access to developer tools and Labs. */
@Composable
fun AdvancedSettingsScreen(
    onBack: () -> Unit,
    onNavigateToDeveloperTools: () -> Unit = {},
    onNavigateToLabs: () -> Unit = {},
    viewModel: AdvancedSettingsViewModel = koinViewModel(),
) {
    val appUpdateUiState by viewModel.appUpdateUiState.collectAsStateWithLifecycle()
    val developerModeEnabled by viewModel.developerModeEnabled.collectAsStateWithLifecycle()
    val developerModeSaveFailed by viewModel.developerModeSaveFailed.collectAsStateWithLifecycle()

    AdvancedSettingsContent(
        onBack = onBack,
        onNavigateToDeveloperTools = onNavigateToDeveloperTools,
        onNavigateToLabs = onNavigateToLabs,
        appUpdateUiState = appUpdateUiState,
        onCheckForAppUpdates = viewModel::checkForAppUpdates,
        onCompleteAppUpdate = viewModel::completeAppUpdate,
        developerModeEnabled = developerModeEnabled,
        onSetDeveloperModeEnabled = viewModel::setDeveloperModeEnabled,
        developerModeSaveFailed = developerModeSaveFailed,
    )
}

@Composable
fun AdvancedSettingsContent(
    onBack: () -> Unit,
    appUpdateUiState: AppUpdateUiState,
    onCheckForAppUpdates: () -> Unit,
    onCompleteAppUpdate: () -> Unit,
    onNavigateToDeveloperTools: () -> Unit = {},
    onNavigateToLabs: () -> Unit = {},
    developerModeEnabled: Boolean = false,
    onSetDeveloperModeEnabled: (Boolean) -> Unit = {},
    developerModeSaveFailed: Boolean = false,
) {
    var versionTaps by rememberSaveable { mutableIntStateOf(0) }
    SettingsScaffold(title = stringResource(Res.string.about_logdate), onBack = onBack) {
        item {
            MaterialContainer(modifier = Modifier.padding(horizontal = Spacing.lg)) {
                SettingsRow(
                    headlineContent = { Text(installedVersionLabel(appUpdateUiState)) },
                    modifier =
                        Modifier.clickable {
                            if (!developerModeEnabled) {
                                versionTaps++
                                if (versionTaps >= 7) {
                                    versionTaps = 0
                                    onSetDeveloperModeEnabled(true)
                                }
                            }
                        },
                )
                if (developerModeEnabled) {
                    SettingsNavigationItem(
                        title = stringResource(Res.string.developer_tools),
                        description = stringResource(Res.string.developer_tools_description),
                        icon = { Icon(Icons.Outlined.Code, contentDescription = null) },
                        onClick = onNavigateToDeveloperTools,
                    )
                    SettingsNavigationItem(
                        title = stringResource(Res.string.labs_settings_title),
                        description = stringResource(Res.string.labs_settings_description),
                        icon = { Icon(Icons.Outlined.Science, contentDescription = null) },
                        onClick = onNavigateToLabs,
                    )
                    SettingsRow(
                        headlineContent = { Text(stringResource(Res.string.hide_developer_mode)) },
                        leadingContent = { Icon(Icons.Outlined.VisibilityOff, contentDescription = null) },
                        modifier =
                            Modifier.clickable {
                                versionTaps = 0
                                onSetDeveloperModeEnabled(false)
                            },
                    )
                }
                if (developerModeSaveFailed) {
                    Text(
                        stringResource(Res.string.developer_mode_save_failed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(Spacing.lg),
                    )
                }
            }
        }
        item {
            AppUpdateSection(
                appUpdateUiState = appUpdateUiState,
                onCheckForAppUpdates = onCheckForAppUpdates,
                onCompleteAppUpdate = onCompleteAppUpdate,
                modifier = Modifier.padding(horizontal = Spacing.lg),
            )
        }
    }
}

@Composable
private fun installedVersionLabel(appUpdateUiState: AppUpdateUiState): String {
    val versionName = appUpdateUiState.currentVersionName
    val versionCode = appUpdateUiState.currentVersionCode ?: return stringResource(Res.string.app_version_label, versionName)
    return stringResource(Res.string.app_version_with_build_label, versionName, versionCode)
}

@Composable
private fun AppUpdateSection(
    appUpdateUiState: AppUpdateUiState,
    onCheckForAppUpdates: () -> Unit,
    onCompleteAppUpdate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val actionLabel =
        when (appUpdateUiState.status) {
            AppUpdateStatus.Checking -> stringResource(Res.string.app_update_checking)
            AppUpdateStatus.Downloaded -> stringResource(Res.string.app_update_restart_action)
            else -> stringResource(Res.string.check_for_updates)
        }

    val statusMessage =
        when (appUpdateUiState.status) {
            AppUpdateStatus.Idle -> {
                null
            }

            AppUpdateStatus.Checking -> {
                stringResource(Res.string.app_update_checking)
            }

            AppUpdateStatus.UpToDate -> {
                appUpdateUiState.message?.asString() ?: stringResource(Res.string.app_update_up_to_date)
            }

            AppUpdateStatus.Available -> {
                when (appUpdateUiState.flowType) {
                    AppUpdateFlowType.Immediate -> stringResource(Res.string.app_update_immediate_required)
                    else -> stringResource(Res.string.app_update_available)
                }
            }

            AppUpdateStatus.Downloading -> {
                stringResource(Res.string.app_update_downloading)
            }

            AppUpdateStatus.Downloaded -> {
                stringResource(Res.string.app_update_downloaded)
            }

            AppUpdateStatus.Unsupported -> {
                appUpdateUiState.message?.asString() ?: stringResource(Res.string.app_update_unsupported)
            }

            AppUpdateStatus.Error -> {
                appUpdateUiState.message?.asString() ?: stringResource(Res.string.app_update_check_failed)
            }
        }

    val buttonEnabled = appUpdateUiState.status != AppUpdateStatus.Checking

    SettingsSection(title = stringResource(Res.string.app_updates), modifier = modifier) {
        SettingsNavigationItem(
            title = actionLabel,
            description = statusMessage ?: stringResource(Res.string.app_updates_description),
            icon = { Icon(Icons.Outlined.SystemUpdate, contentDescription = null) },
            onClick = {
                if (appUpdateUiState.status == AppUpdateStatus.Downloaded) onCompleteAppUpdate() else onCheckForAppUpdates()
            },
            enabled = buttonEnabled,
        )
    }
}

@Preview
@Composable
private fun AdvancedSettingsScreenPreview() {
    AdvancedSettingsContent(
        onBack = {},
        appUpdateUiState = AppUpdateUiState(currentVersionName = "0.1.0"),
        onCheckForAppUpdates = {},
        onCompleteAppUpdate = {},
    )
}
