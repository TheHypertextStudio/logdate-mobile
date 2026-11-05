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
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import app.logdate.client.domain.watch.SharedPreferenceKey
import app.logdate.feature.core.settings.ui.watch.v2.WatchCompanionPage
import app.logdate.feature.core.settings.ui.watch.v2.WatchCompanionPreferencesViewModel
import app.logdate.feature.core.settings.ui.watch.v2.WatchCompanionSections
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.logdate.ui.adaptive.FoldableBookLayout
import app.logdate.ui.common.SettingsNavigationItem
import app.logdate.ui.common.SettingsScaffold
import app.logdate.ui.common.SettingsSection
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.watch_help
import logdate.client.feature.core.generated.resources.watch_notifications
import logdate.client.feature.core.generated.resources.watch_notifications_description
import logdate.client.feature.core.generated.resources.watch_notifications_title
import logdate.client.feature.core.generated.resources.watch_privacy_description
import logdate.client.feature.core.generated.resources.watch_privacy_title
import logdate.client.feature.core.generated.resources.watch_settings
import logdate.client.feature.core.generated.resources.watch_troubleshooting
import logdate.client.feature.core.generated.resources.watch_troubleshooting_description
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun WatchSettingsScreen(
    onBack: () -> Unit,
    onNavigateToNotifications: () -> Unit,
    onNavigateToTroubleshooting: () -> Unit,
    viewModel: WatchSettingsViewModel = koinViewModel(),
    modifier: Modifier = Modifier,
    onNavigateToReminders: () -> Unit = {},
    onNavigateToTiles: () -> Unit = {},
    onNavigateToMicrophone: () -> Unit = {},
    onNavigateToRemove: () -> Unit = {},
    onNavigateToConnectionCheck: () -> Unit = onNavigateToTroubleshooting,
) {
    val flags = koinInject<FeatureFlagStore>()
    val redesign by remember(flags) { flags.observe(FeatureFlag.WATCH_EXPERIENCE_V2) }.collectAsStateWithLifecycle(false)
    val status by viewModel.status.collectAsStateWithLifecycle()
    val requestFailure by viewModel.requestFailure.collectAsStateWithLifecycle()
    val installRequestSent by viewModel.installRequestSent.collectAsStateWithLifecycle()

    if (redesign) {
        WatchCompanionPage(
            status,
            requestFailure,
            onBack,
            viewModel::beginAssociation,
            viewModel::installAppOnWatch,
            onNavigateToReminders,
            onNavigateToTiles,
            onNavigateToMicrophone,
            onNavigateToNotifications,
            onNavigateToConnectionCheck,
            onNavigateToRemove,
            modifier,
            installRequestSent,
        ) { captureActions -> WatchCompanionSettings(captureActions = captureActions) }
        return
    }
    WatchSettingsContent(
        status = status,
        requestFailure = requestFailure,
        onBack = onBack,
        onBeginAssociation = viewModel::beginAssociation,
        onRequestSync = viewModel::requestSync,
        onInstallOnWatch = viewModel::installAppOnWatch,
        onNavigateToNotifications = onNavigateToNotifications,
        onNavigateToTroubleshooting = onNavigateToTroubleshooting,
        modifier = modifier,
        companionSettings =
            if (redesign) {
                { WatchCompanionSettings() }
            } else {
                null
            },
    )
}

@Composable
private fun WatchCompanionSettings(
    captureActions: @Composable () -> Unit = {},
    viewModel: WatchCompanionPreferencesViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val saveFailed by viewModel.saveFailed.collectAsStateWithLifecycle()
    WatchCompanionSections(
        state = state,
        saving = saving,
        saveFailed = saveFailed,
        onDays = { viewModel.set(SharedPreferenceKey.WindowDays, it) },
        onThisDay = { viewModel.set(SharedPreferenceKey.WindowOnThisDay, it) },
        onRewind = { viewModel.set(SharedPreferenceKey.WindowRewind, it) },
        onPlaces = { viewModel.set(SharedPreferenceKey.CapturePlaces, it) },
        onMood = { viewModel.set(SharedPreferenceKey.MoodAfterRecording, it) },
        onAudioDownloads = { viewModel.set(SharedPreferenceKey.AudioDownloadPolicy, it.stored) },
        captureActions = captureActions,
    )
}

@Composable
fun WatchSettingsContent(
    status: WatchStatus?,
    requestFailure: WatchRequestFailure?,
    onBack: () -> Unit,
    onBeginAssociation: () -> Unit,
    onRequestSync: () -> Unit,
    onInstallOnWatch: () -> Unit,
    onNavigateToNotifications: () -> Unit,
    onNavigateToTroubleshooting: () -> Unit,
    modifier: Modifier = Modifier,
    companionSettings: (@Composable () -> Unit)? = null,
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
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                WatchStatusCard(
                    status = status,
                    requestFailure = requestFailure,
                    onBeginAssociation = onBeginAssociation,
                    onInstallOnWatch = onInstallOnWatch,
                    modifier = Modifier.padding(horizontal = Spacing.lg),
                    onRequestSync = onRequestSync,
                )
                WatchPrivacyNote()
            }
        },
        endPane = {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                if (status.hasLogDate()) companionSettings?.invoke()
                WatchConnectedSections(onNavigateToNotifications)
                WatchTroubleshootingSection(onNavigateToTroubleshooting)
            }
        },
        standardContent = {
            SettingsScaffold(
                title = stringResource(Res.string.watch_settings),
                onBack = onBack,
                modifier = modifier,
            ) {
                item {
                    WatchStatusCard(
                        status = status,
                        requestFailure = requestFailure,
                        onBeginAssociation = onBeginAssociation,
                        onInstallOnWatch = onInstallOnWatch,
                        modifier = Modifier.padding(horizontal = Spacing.lg),
                        onRequestSync = onRequestSync,
                    )
                }
                if (status.hasLogDate()) {
                    companionSettings?.let { settings -> item { settings() } }
                    item {
                        WatchConnectedSections(onNavigateToNotifications)
                    }
                }
                item {
                    WatchTroubleshootingSection(onNavigateToTroubleshooting)
                }
                item {
                    WatchPrivacyNote()
                }
            }
        },
    )
}

/** Notification settings exist only for a watch that has LogDate. */
private fun WatchStatus?.hasLogDate(): Boolean = this is WatchStatus.Ready || this is WatchStatus.NeedsAttention

@Composable
private fun WatchConnectedSections(onNavigateToNotifications: () -> Unit) {
    SettingsSection(
        title = stringResource(Res.string.watch_notifications),
        modifier = Modifier.padding(horizontal = Spacing.lg),
    ) {
        SettingsNavigationItem(
            title = stringResource(Res.string.watch_notifications_title),
            description = stringResource(Res.string.watch_notifications_description),
            onClick = onNavigateToNotifications,
        )
    }
}

@Composable
private fun WatchTroubleshootingSection(onNavigateToTroubleshooting: () -> Unit) {
    SettingsSection(
        title = stringResource(Res.string.watch_help),
        modifier = Modifier.padding(horizontal = Spacing.lg),
    ) {
        SettingsNavigationItem(
            title = stringResource(Res.string.watch_troubleshooting),
            description = stringResource(Res.string.watch_troubleshooting_description),
            onClick = onNavigateToTroubleshooting,
        )
    }
}

@Composable
private fun WatchPrivacyNote() {
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
                    Icons.Default.Watch,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(Res.string.watch_privacy_title),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Text(
                text = stringResource(Res.string.watch_privacy_description),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
