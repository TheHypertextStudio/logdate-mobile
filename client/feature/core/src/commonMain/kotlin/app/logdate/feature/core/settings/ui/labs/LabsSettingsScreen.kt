@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui.labs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.logdate.client.datastore.LogdatePreferencesDataSource
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.feature.core.settings.ui.PlacesBetaSettingsItem
import app.logdate.feature.core.settings.ui.PlacesBetaSettingsViewModel
import app.logdate.feature.core.settings.ui.rememberReminderSettingsAccess
import app.logdate.ui.adaptive.FoldableBookLayout
import app.logdate.ui.common.SettingsScaffold
import app.logdate.ui.common.SettingsSection
import app.logdate.ui.common.SimpleSettingsItem
import app.logdate.ui.common.ToggleSettingsItem
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.labs_activity_permission_action
import logdate.client.feature.core.generated.resources.labs_activity_permission_description
import logdate.client.feature.core.generated.resources.labs_activity_permission_title
import logdate.client.feature.core.generated.resources.labs_empty
import logdate.client.feature.core.generated.resources.labs_intro
import logdate.client.feature.core.generated.resources.labs_section_unreleased
import logdate.client.feature.core.generated.resources.labs_settings_title
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * Labs: switches for features that have not launched, for testing them in a release build.
 *
 * Public betas are available from About; internal previews retain developer-mode access.
 */
@Composable
fun LabsSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LabsSettingsViewModel = koinViewModel(),
    placesBetaViewModel: PlacesBetaSettingsViewModel = koinViewModel(),
    preferences: LogdatePreferencesDataSource = koinInject(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val access = rememberReminderSettingsAccess()
    val placesBetaState by placesBetaViewModel.state.collectAsStateWithLifecycle()
    val developerMode by remember(preferences) { preferences.observeDeveloperModeEnabled() }.collectAsStateWithLifecycle(false)

    LabsSettingsContent(
        uiState = uiState,
        developerModeEnabled = developerMode,
        betaSettingsContent = { PlacesBetaSettingsItem(placesBetaState, placesBetaViewModel::setEnabled) },
        onBack = onBack,
        onSetEnabled = { flag, enabled ->
            viewModel.setEnabled(flag, enabled)
            if (flag == FeatureFlag.ACTIVITY_AWARENESS && enabled && !access.activityAllowed) access.resolveActivity()
        },
        activityAllowed = access.activityAllowed,
        onAllowActivity = access.resolveActivity,
        modifier = modifier,
    )
}

@Composable
fun LabsSettingsContent(
    uiState: LabsUiState,
    onBack: () -> Unit,
    onSetEnabled: (FeatureFlag, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    activityAllowed: Boolean = true,
    onAllowActivity: () -> Unit = {},
    developerModeEnabled: Boolean = false,
    betaSettingsContent: (@Composable () -> Unit)? = null,
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
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text(
                    text = stringResource(Res.string.labs_settings_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = Spacing.lg),
                )
                LabsIntro(modifier = Modifier.padding(horizontal = Spacing.lg))
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
                LabsFlagsSection(
                    uiState = uiState,
                    developerModeEnabled = developerModeEnabled,
                    betaSettingsContent = betaSettingsContent,
                    onSetEnabled = onSetEnabled,
                    activityAllowed = activityAllowed,
                    onAllowActivity = onAllowActivity,
                    modifier = Modifier.padding(horizontal = Spacing.lg),
                )
            }
        },
        standardContent = {
            SettingsScaffold(
                title = stringResource(Res.string.labs_settings_title),
                onBack = onBack,
            ) {
                item {
                    LabsIntro(modifier = Modifier.padding(horizontal = Spacing.lg))
                }
                item {
                    LabsFlagsSection(
                        uiState = uiState,
                        developerModeEnabled = developerModeEnabled,
                        betaSettingsContent = betaSettingsContent,
                        onSetEnabled = onSetEnabled,
                        activityAllowed = activityAllowed,
                        onAllowActivity = onAllowActivity,
                        modifier = Modifier.padding(horizontal = Spacing.lg),
                    )
                }
            }
        },
    )
}

@Composable
private fun LabsIntro(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(Res.string.labs_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
private fun LabsFlagsSection(
    uiState: LabsUiState,
    onSetEnabled: (FeatureFlag, Boolean) -> Unit,
    activityAllowed: Boolean,
    onAllowActivity: () -> Unit,
    developerModeEnabled: Boolean,
    betaSettingsContent: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val flags = if (developerModeEnabled) (uiState as? LabsUiState.Ready)?.flags.orEmpty() else emptyList()
    if (betaSettingsContent == null && uiState !is LabsUiState.Ready) return
    if (betaSettingsContent == null && flags.isEmpty()) {
        Text(
            text = stringResource(Res.string.labs_empty),
            style = MaterialTheme.typography.bodyMedium,
            modifier = modifier,
        )
        return
    }
    SettingsSection(
        title = stringResource(Res.string.labs_section_unreleased),
        modifier = modifier,
    ) {
        betaSettingsContent?.invoke()
        flags.forEach { row ->
            val copy = row.flag.labsCopy() ?: return@forEach
            ToggleSettingsItem(
                title = stringResource(copy.title),
                description = stringResource(copy.description),
                checked = row.enabled,
                onCheckedChange = { enabled -> onSetEnabled(row.flag, enabled) },
            )
            if (row.flag == FeatureFlag.ACTIVITY_AWARENESS && row.enabled && !activityAllowed) {
                SimpleSettingsItem(
                    title = stringResource(Res.string.labs_activity_permission_title),
                    description = stringResource(Res.string.labs_activity_permission_description),
                ) {
                    TextButton(onClick = onAllowActivity) { Text(stringResource(Res.string.labs_activity_permission_action)) }
                }
            }
        }
    }
}
