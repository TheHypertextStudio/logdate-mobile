@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import app.logdate.ui.common.SettingsScaffold
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.developer_tools
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun DeveloperToolsScreen(
    onBack: () -> Unit,
    diagnostics: LocalDiagnosticsViewModel = koinViewModel(),
    reporting: AutomaticDiagnosticReportingViewModel = koinViewModel(),
    location: LocationSettingsViewModel = koinViewModel(),
    featureFlags: FeatureFlagStore = koinInject(),
) {
    val diagnosticsState by diagnostics.state.collectAsStateWithLifecycle()
    val locationState by location.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(diagnostics) { diagnostics.refresh() }
    DeveloperToolsContent(
        onBack = onBack,
        state = diagnosticsState,
        onPreview = diagnostics::refresh,
        onExport = diagnostics::export,
        onClear = diagnostics::clear,
        onSetVerboseEnabled = diagnostics::setVerboseEnabled,
        reportingContent = { AutomaticDiagnosticReportingSection(reporting) },
        locationContent = {
            DeveloperLocationSection(
                state = locationState,
                onToggleServerAssist = location::toggleServerAssist,
                onSetDefaultLocation = location::setDefaultLocation,
                onRetry = location::retry,
                onReload = location::reload,
            )
        },
        featurePreviewsContent = { FeaturePreviewsSection(store = featureFlags) },
        storagePreviewsContent = { StoragePreviewsSection(store = featureFlags) },
    )
}

@Composable
fun DeveloperToolsContent(
    onBack: () -> Unit,
    state: LocalDiagnosticsState,
    onPreview: () -> Unit,
    onExport: () -> Unit,
    onClear: () -> Unit,
    onSetVerboseEnabled: (Boolean) -> Unit,
    reportingContent: @Composable () -> Unit = {},
    locationContent: @Composable () -> Unit = {},
    featurePreviewsContent: (@Composable () -> Unit)? = null,
    storagePreviewsContent: (@Composable () -> Unit)? = null,
) {
    SettingsScaffold(title = stringResource(Res.string.developer_tools), onBack = onBack) {
        if (featurePreviewsContent != null) {
            item {
                Box(Modifier.padding(horizontal = Spacing.lg)) { featurePreviewsContent() }
            }
        }
        if (storagePreviewsContent != null) {
            item {
                Box(Modifier.padding(horizontal = Spacing.lg)) { storagePreviewsContent() }
            }
        }
        item {
            LocalDiagnosticsSettingsSection(
                state = state,
                onPreview = onPreview,
                onExport = onExport,
                onClear = onClear,
                onSetVerboseEnabled = onSetVerboseEnabled,
                modifier = Modifier.padding(horizontal = Spacing.lg),
            )
        }
        item {
            Box(Modifier.padding(horizontal = Spacing.lg)) { reportingContent() }
        }
        item {
            Box(Modifier.padding(horizontal = Spacing.lg)) { locationContent() }
        }
    }
}
