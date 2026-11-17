@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.account

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.logdate.feature.core.settings.ui.ServerPreset
import app.logdate.feature.core.settings.ui.ServerSelectionState
import app.logdate.ui.step.StepPrimaryButton
import app.logdate.ui.step.StepScaffold
import app.logdate.ui.step.StepTextField
import app.logdate.ui.theme.FirstLaunchTheme
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.cloud_hosting_address_help
import logdate.client.feature.core.generated.resources.cloud_hosting_address_label
import logdate.client.feature.core.generated.resources.cloud_hosting_body
import logdate.client.feature.core.generated.resources.cloud_hosting_learn_more
import logdate.client.feature.core.generated.resources.cloud_hosting_logdate_body
import logdate.client.feature.core.generated.resources.cloud_hosting_own_body
import logdate.client.feature.core.generated.resources.cloud_hosting_own_title
import logdate.client.feature.core.generated.resources.cloud_hosting_title
import logdate.client.feature.core.generated.resources.cloud_hosting_use_logdate
import logdate.client.feature.core.generated.resources.cloud_hosting_use_own
import logdate.client.feature.core.generated.resources.logdate_cloud
import org.jetbrains.compose.resources.stringResource

/** Root of the step where a new account's host is chosen. */
const val CLOUD_ACCOUNT_HOSTING_ROOT_TAG = "cloud_account_hosting_root"
const val CLOUD_ACCOUNT_HOSTING_DONE_TAG = "cloud_account_hosting_done"
const val CLOUD_ACCOUNT_HOSTING_LOGDATE_TAG = "cloud_account_hosting_logdate"
const val CLOUD_ACCOUNT_HOSTING_OWN_TAG = "cloud_account_hosting_own"

/**
 * Where a new account is hosted: LogDate Cloud, or a LogDate server the person runs.
 *
 * It is reached only while making an account, from the line on the username step that names the
 * host, because the host is part of the name being chosen there. Signing in never comes here: a
 * sign-in name already says where its account lives.
 *
 * LogDate Cloud comes first and is already chosen. Choosing your own server opens that card to
 * show the one thing it needs, the server's address, with an example under it and a button that
 * explains the rest. The words are for the people who would choose it, who know what a server
 * address is.
 */
@Composable
fun CloudAccountHostingContent(
    serverSelectionState: ServerSelectionState,
    onSelectServerPreset: (ServerPreset) -> Unit,
    onCustomServerUrlChange: (String) -> Unit,
    onShowCustomServerInfo: () -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    errorMessage: String? = null,
    isChecking: Boolean = false,
) {
    val own = serverSelectionState.selectedPreset == ServerPreset.CUSTOM
    FirstLaunchTheme {
        StepScaffold(
            title = stringResource(Res.string.cloud_hosting_title),
            onBack = onBack,
            modifier = modifier.testTag(CLOUD_ACCOUNT_HOSTING_ROOT_TAG),
            supportingText = stringResource(Res.string.cloud_hosting_body),
            actions = {
                StepPrimaryButton(
                    text = stringResource(if (own) Res.string.cloud_hosting_use_own else Res.string.cloud_hosting_use_logdate),
                    onClick = onDone,
                    modifier = Modifier.testTag(CLOUD_ACCOUNT_HOSTING_DONE_TAG),
                    // A server with no address cannot be used, so there is nothing to confirm yet.
                    enabled = !own || serverSelectionState.customServerUrl.isNotBlank(),
                    busy = isChecking,
                )
            },
        ) {
            if (errorMessage != null) StepProblem(errorMessage)
            Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                HostOption(
                    selected = !own,
                    onClick = { onSelectServerPreset(ServerPreset.PRODUCTION) },
                    icon = Icons.Rounded.Cloud,
                    iconBackground = MaterialTheme.colorScheme.primary,
                    iconTint = MaterialTheme.colorScheme.onPrimary,
                    title = stringResource(Res.string.logdate_cloud),
                    body = stringResource(Res.string.cloud_hosting_logdate_body),
                    modifier = Modifier.testTag(CLOUD_ACCOUNT_HOSTING_LOGDATE_TAG),
                )
                HostOption(
                    selected = own,
                    onClick = { onSelectServerPreset(ServerPreset.CUSTOM) },
                    icon = Icons.Rounded.Home,
                    iconBackground = MaterialTheme.colorScheme.surfaceContainerHighest,
                    iconTint = MaterialTheme.colorScheme.onSurface,
                    title = stringResource(Res.string.cloud_hosting_own_title),
                    body = stringResource(Res.string.cloud_hosting_own_body),
                    modifier = Modifier.testTag(CLOUD_ACCOUNT_HOSTING_OWN_TAG),
                ) {
                    ServerAddressField(
                        address = serverSelectionState.customServerUrl,
                        onAddressChange = onCustomServerUrlChange,
                        onExplain = onShowCustomServerInfo,
                    )
                }
            }
        }
    }
}

/**
 * One place an account can be hosted: a mark, its name, one line about it, and whether it is
 * chosen.
 *
 * The chosen place is filled with the selection color; nothing is outlined. [details] is whatever
 * the place needs once it is chosen. It opens inside the card, so it plainly belongs to that
 * choice. The mark and the gaps beside it are kept small: the name and its line get only the width
 * left between the mark and the radio button, and that line has to fit on one line on a phone.
 */
@Composable
private fun HostOption(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    iconBackground: Color,
    iconTint: Color,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    details: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.extraLarge)
                .background(if (selected) colors.secondaryContainer else colors.surfaceContainerLowest)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Box(
                Modifier.size(40.dp).background(iconBackground, MaterialTheme.shapes.large),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(24.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (selected) colors.onSecondaryContainer else colors.onSurface,
                )
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
                )
            }
            RadioButton(selected = selected, onClick = null)
        }
        if (details != null) {
            AnimatedVisibility(visible = selected) { details() }
        }
    }
}

/**
 * The address of the person's own server.
 *
 * The line under the field shows what an address looks like, and the button at its end opens the
 * full explanation of hosting an account yourself.
 */
@Composable
private fun ServerAddressField(
    address: String,
    onAddressChange: (String) -> Unit,
    onExplain: () -> Unit,
) {
    StepTextField(
        value = address,
        onValueChange = onAddressChange,
        label = { Text(stringResource(Res.string.cloud_hosting_address_label)) },
        supportingText = { Text(stringResource(Res.string.cloud_hosting_address_help)) },
        leadingIcon = { Icon(Icons.Rounded.Language, contentDescription = null) },
        trailingIcon = {
            IconButton(onClick = onExplain) {
                Icon(Icons.Outlined.Info, contentDescription = stringResource(Res.string.cloud_hosting_learn_more))
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.fillMaxWidth(),
    )
}
