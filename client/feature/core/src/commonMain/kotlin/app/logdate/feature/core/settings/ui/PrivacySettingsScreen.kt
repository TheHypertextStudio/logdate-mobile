@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import app.logdate.ui.common.AlertDialog
import app.logdate.ui.common.SettingsNavigationItem
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.logdate.ui.common.SettingsScaffold
import app.logdate.ui.common.SettingsSection
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.app_security
import logdate.client.feature.core.generated.resources.disable_biometric_lock_message
import logdate.client.feature.core.generated.resources.disable_biometric_lock_title
import logdate.client.feature.core.generated.resources.location_privacy
import logdate.client.feature.core.generated.resources.location_settings
import logdate.client.feature.core.generated.resources.manage_location_tracking_and_privacy_preferences
import logdate.client.feature.core.generated.resources.navigate_to_location_settings
import logdate.client.feature.core.generated.resources.privacy_and_security
import logdate.client.feature.core.generated.resources.privacy_auth_description
import logdate.client.feature.core.generated.resources.privacy_auth_subtitle
import logdate.client.feature.core.generated.resources.privacy_auth_title
import logdate.client.feature.core.generated.resources.settings_biometric_description
import logdate.client.feature.core.generated.resources.settings_biometric_label
import logdate.client.feature.core.generated.resources.system_search_visibility_description
import logdate.client.feature.core.generated.resources.system_search_visibility_label
import logdate.client.feature.core.generated.resources.system_search_visibility_section
import logdate.client.ui.generated.resources.common_cancel
import logdate.client.ui.generated.resources.common_confirm
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import logdate.client.ui.generated.resources.Res as UiRes

/**
 * Privacy and security settings screen.
 *
 * This screen automatically adapts to different screen sizes:
 * - Large screens: Acts as a detail pane with minimal header (when in two-pane layout)
 * - Small screens: Standard screen with back navigation
 *
 * @param onBack Callback for when the user presses the back button
 * @param onNavigateToLocationSettings Callback for navigating to location settings
 * @param viewModel ViewModel for the settings
 */
@Composable
fun PrivacySettingsScreen(
    onBack: () -> Unit,
    onNavigateToLocationSettings: () -> Unit = {},
    viewModel: PrivacySettingsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val prompt =
        PrivacyAuthenticationPrompt(
            title = stringResource(Res.string.privacy_auth_title),
            subtitle = stringResource(Res.string.privacy_auth_subtitle),
            cancelLabel = stringResource(UiRes.string.common_cancel),
            description = stringResource(Res.string.privacy_auth_description),
        )

    DisposableEffect(viewModel) {
        onDispose { viewModel.cancelAuthentication() }
    }

    PrivacySettingsContent(
        state = state,
        onBack = onBack,
        onSetBiometricsEnabled = { viewModel.setBiometricEnabled(it, prompt) },
        onSetSystemSearchVisibilityEnabled = viewModel::setSystemSearchVisibilityEnabled,
        onRetryBiometrics = { viewModel.retryBiometrics(prompt) },
        onRetrySystemSearchVisibility = viewModel::retrySystemSearchVisibility,
        onNavigateToLocationSettings = onNavigateToLocationSettings,
    )
}

@Composable
fun PrivacySettingsContent(
    onBack: () -> Unit,
    onSetBiometricsEnabled: (enabled: Boolean) -> Unit,
    onSetSystemSearchVisibilityEnabled: (enabled: Boolean) -> Unit = {},
    isBiometricsEnabled: Boolean,
    isSystemSearchVisibilityEnabled: Boolean = false,
    showSystemSearchVisibilityToggle: Boolean = false,
    onNavigateToLocationSettings: () -> Unit = {},
) {
    PrivacySettingsContent(
        state =
            PrivacySettingsState(
                biometrics = PrivacyPreferenceState.Available(isBiometricsEnabled),
                systemSearchVisibility = PrivacyPreferenceState.Available(isSystemSearchVisibilityEnabled),
                showSystemSearchVisibilityToggle = showSystemSearchVisibilityToggle,
            ),
        onBack = onBack,
        onSetBiometricsEnabled = onSetBiometricsEnabled,
        onSetSystemSearchVisibilityEnabled = onSetSystemSearchVisibilityEnabled,
        onNavigateToLocationSettings = onNavigateToLocationSettings,
    )
}

@Composable
fun PrivacySettingsContent(
    state: PrivacySettingsState,
    onBack: () -> Unit,
    onSetBiometricsEnabled: (Boolean) -> Unit,
    onSetSystemSearchVisibilityEnabled: (Boolean) -> Unit = {},
    onRetryBiometrics: () -> Unit = {},
    onRetrySystemSearchVisibility: () -> Unit = {},
    onNavigateToLocationSettings: () -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var showDisableBiometricsDialog by remember { mutableStateOf(false) }

    SettingsScaffold(
        title = stringResource(Res.string.privacy_and_security),
        onBack = onBack,
        snackbarHostState = snackbarHostState,
    ) {
        item {
            SettingsSection(
                title = stringResource(Res.string.app_security),
                modifier = Modifier.padding(horizontal = Spacing.lg),
            ) {
                PrivacyPreferenceItem(
                    title = stringResource(Res.string.settings_biometric_label),
                    description = stringResource(Res.string.settings_biometric_description),
                    state = state.biometrics,
                    onRetry = onRetryBiometrics,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            onSetBiometricsEnabled(true)
                        } else {
                            showDisableBiometricsDialog = true
                        }
                    },
                )
            }
        }

        if (state.showSystemSearchVisibilityToggle) {
            item {
                SettingsSection(
                    title = stringResource(Res.string.system_search_visibility_section),
                    modifier = Modifier.padding(horizontal = Spacing.lg),
                ) {
                    PrivacyPreferenceItem(
                        title = stringResource(Res.string.system_search_visibility_label),
                        description = stringResource(Res.string.system_search_visibility_description),
                        state = state.systemSearchVisibility,
                        onRetry = onRetrySystemSearchVisibility,
                        onCheckedChange = onSetSystemSearchVisibilityEnabled,
                    )
                }
            }
        }

        item {
            SettingsSection(
                title = stringResource(Res.string.location_privacy),
                modifier = Modifier.padding(horizontal = Spacing.lg),
            ) {
                SettingsNavigationItem(
                    title = stringResource(Res.string.location_settings),
                    description = stringResource(Res.string.manage_location_tracking_and_privacy_preferences),
                    icon = { Icon(Icons.Default.LocationOn, contentDescription = null) },
                    onClick = onNavigateToLocationSettings,
                    onClickLabel = stringResource(Res.string.navigate_to_location_settings),
                )
            }
        }
    }

    if (showDisableBiometricsDialog) {
        AlertDialog(
            onDismissRequest = { showDisableBiometricsDialog = false },
            title = { Text(stringResource(Res.string.disable_biometric_lock_title)) },
            text = { Text(stringResource(Res.string.disable_biometric_lock_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSetBiometricsEnabled(false)
                        showDisableBiometricsDialog = false
                    },
                ) {
                    Text(stringResource(UiRes.string.common_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisableBiometricsDialog = false }) {
                    Text(stringResource(UiRes.string.common_cancel))
                }
            },
        )
    }
}

@Preview
@Composable
private fun PrivacySettingsScreenPreview() {
    PrivacySettingsContent(
        onBack = {},
        onSetBiometricsEnabled = {},
        onSetSystemSearchVisibilityEnabled = {},
        isBiometricsEnabled = true,
        isSystemSearchVisibilityEnabled = true,
        showSystemSearchVisibilityToggle = true,
    )
}
