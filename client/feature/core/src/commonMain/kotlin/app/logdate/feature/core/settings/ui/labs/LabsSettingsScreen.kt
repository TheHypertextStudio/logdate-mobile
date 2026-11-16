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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.feature.core.activity.PhoneActivityLink
import app.logdate.feature.core.activity.PhoneActivityLinkState
import app.logdate.feature.core.activity.busy
import app.logdate.feature.core.activity.detail
import app.logdate.feature.core.activity.rememberPhoneActivityLink
import app.logdate.ui.adaptive.FoldableBookLayout
import app.logdate.ui.common.SettingsScaffold
import app.logdate.ui.common.SettingsSection
import app.logdate.ui.common.SimpleSettingsItem
import app.logdate.ui.common.ToggleSettingsItem
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.labs_activity_awareness_description
import logdate.client.feature.core.generated.resources.labs_activity_awareness_title
import logdate.client.feature.core.generated.resources.labs_empty
import logdate.client.feature.core.generated.resources.labs_intro
import logdate.client.feature.core.generated.resources.labs_section_unreleased
import logdate.client.feature.core.generated.resources.labs_settings_title
import logdate.client.feature.core.generated.resources.phone_activity_link
import logdate.client.feature.core.generated.resources.phone_activity_open_settings
import logdate.client.feature.core.generated.resources.phone_activity_unlink
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * Labs: switches for features that have not launched, for testing them in a release build.
 *
 * The Settings overview has no row for it. Tapping the version on the About page seven times turns
 * on developer mode, and the About page then shows a Labs row that opens it.
 */
@Composable
fun LabsSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LabsSettingsViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LabsSettingsContent(
        uiState = uiState,
        onBack = onBack,
        onSetEnabled = viewModel::setEnabled,
        activityLink = rememberPhoneActivityLink(),
        modifier = modifier,
    )
}

@Composable
fun LabsSettingsContent(
    uiState: LabsUiState,
    onBack: () -> Unit,
    onSetEnabled: (FeatureFlag, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    activityLink: PhoneActivityLink = PhoneActivityLink(PhoneActivityLinkState.Unavailable),
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
                    onSetEnabled = onSetEnabled,
                    activityLink = activityLink,
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
                        onSetEnabled = onSetEnabled,
                        activityLink = activityLink,
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
    activityLink: PhoneActivityLink,
    modifier: Modifier = Modifier,
) {
    if (uiState !is LabsUiState.Ready) return
    if (uiState.flags.isEmpty()) {
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
        uiState.flags.forEach { row ->
            if (row.flag == FeatureFlag.ACTIVITY_AWARENESS) {
                PhoneActivitySettingsRows(activityLink)
                return@forEach
            }
            val copy = row.flag.labsCopy() ?: return@forEach
            ToggleSettingsItem(
                title = stringResource(copy.title),
                description = stringResource(copy.description),
                checked = row.enabled,
                onCheckedChange = { enabled -> onSetEnabled(row.flag, enabled) },
            )
        }
    }
}

@Composable
private fun PhoneActivitySettingsRows(link: PhoneActivityLink) {
    val unlink = link.state == PhoneActivityLinkState.Linked || link.state == PhoneActivityLinkState.UnlinkWriteFailed
    val (title, action) =
        when {
            unlink -> Res.string.phone_activity_unlink to link.onUnlink
            link.state == PhoneActivityLinkState.Denied -> Res.string.phone_activity_open_settings to link.onOpenSettings
            link.state == PhoneActivityLinkState.Unavailable -> Res.string.labs_activity_awareness_title to null
            else -> Res.string.phone_activity_link to link.onLink
        }
    SimpleSettingsItem(
        title = stringResource(title),
        description = stringResource(link.state.detail() ?: Res.string.labs_activity_awareness_description),
        enabled = !link.state.busy,
        onClick = action,
        action = {},
    )
    if (link.optedIn && !unlink && !link.state.busy) {
        SimpleSettingsItem(
            title = stringResource(Res.string.phone_activity_unlink),
            description = null,
            onClick = link.onUnlink,
            action = {},
        )
    }
}
