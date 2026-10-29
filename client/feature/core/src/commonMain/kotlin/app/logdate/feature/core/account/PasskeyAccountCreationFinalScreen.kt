@file:Suppress("ktlint:standard:function-naming")
@file:OptIn(ExperimentalLayoutApi::class)

package app.logdate.feature.core.account

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.logdate.ui.common.AlertDialog
import app.logdate.ui.step.StepBusyButton
import app.logdate.ui.step.StepProgress
import app.logdate.ui.step.StepScaffold
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.about_passkeys
import logdate.client.feature.core.generated.resources.account_confirm_subtitle
import logdate.client.feature.core.generated.resources.account_confirm_title
import logdate.client.feature.core.generated.resources.account_create_cta
import logdate.client.feature.core.generated.resources.account_edit_name
import logdate.client.feature.core.generated.resources.account_edit_username
import logdate.client.feature.core.generated.resources.account_name_label
import logdate.client.feature.core.generated.resources.account_passkey_not_supported_description
import logdate.client.feature.core.generated.resources.account_passkey_point_device_auth
import logdate.client.feature.core.generated.resources.account_passkey_point_multi_device
import logdate.client.feature.core.generated.resources.account_passkey_server_line
import logdate.client.feature.core.generated.resources.passkeys_not_supported
import logdate.client.ui.generated.resources.common_cancel
import logdate.client.ui.generated.resources.common_dismiss
import logdate.client.ui.generated.resources.common_save
import logdate.client.ui.generated.resources.common_try_again
import org.jetbrains.compose.resources.stringResource
import logdate.client.ui.generated.resources.Res as UiRes

/** Final step of cloud account creation, where the passkey ceremony starts. */
const val CLOUD_ACCOUNT_PASSKEY_ROOT_TAG = "cloud_account_passkey_root"
const val CLOUD_ACCOUNT_PASSKEY_CREATE_TAG = "cloud_account_passkey_create"

/**
 * Final step of LogDate Cloud account setup: confirm who the account will belong to, then create it.
 *
 * The person is the subject of the screen: their initial as a die-cut sticker, their name and
 * username on one card, each with its own edit action. Passkey sign-in and the server collapse into
 * one line above the button, with the passkey explanation behind an info toggle, because someone who
 * reached the last step has already opted in.
 *
 * @param onEditUsername returns to the username step.
 * @param onDisplayNameChange saves a new display name; the name is edited in place because the name
 * step is skipped when onboarding already captured one.
 */
@Composable
fun PasskeyAccountCreationFinalContent(
    displayName: String,
    username: String,
    onCreateAccount: () -> Unit,
    onBack: () -> Unit,
    isCreatingAccount: Boolean,
    errorMessage: String?,
    onClearError: () -> Unit,
    isPasskeySupported: Boolean,
    serverDisplayName: String,
    stepNumber: Int,
    stepCount: Int,
    modifier: Modifier = Modifier,
    onEditUsername: () -> Unit = onBack,
    onDisplayNameChange: (String) -> Unit = {},
    detailsEditable: Boolean = true,
) {
    var editingName by remember { mutableStateOf(false) }

    StepScaffold(
        title = stringResource(Res.string.account_confirm_title),
        supportingText = stringResource(Res.string.account_confirm_subtitle),
        // Leaving mid-ceremony would abandon a passkey the platform is already creating.
        onBack = if (isCreatingAccount) null else onBack,
        modifier = modifier.testTag(CLOUD_ACCOUNT_PASSKEY_ROOT_TAG),
        progress = if (stepCount > 0) StepProgress(current = stepNumber, total = stepCount) else null,
        headerAlignment = Alignment.CenterHorizontally,
        actions = {
            PasskeyLine(serverDisplayName = serverDisplayName)
            StepBusyButton(
                text =
                    when {
                        errorMessage != null -> stringResource(UiRes.string.common_try_again)
                        else -> stringResource(Res.string.account_create_cta)
                    },
                onClick = onCreateAccount,
                busy = isCreatingAccount,
                enabled = isPasskeySupported,
                modifier = Modifier.testTag(CLOUD_ACCOUNT_PASSKEY_CREATE_TAG),
            )
        },
    ) {
        AccountIdentityCard(
            displayName = displayName,
            username = username,
            onEditName = { editingName = true },
            onEditUsername = onEditUsername,
            editEnabled = !isCreatingAccount && detailsEditable,
        )

        if (!isPasskeySupported) {
            NoticeCard(
                icon = Icons.Default.Warning,
                title = stringResource(Res.string.passkeys_not_supported),
                body = stringResource(Res.string.account_passkey_not_supported_description),
            )
        }

        errorMessage?.let { error ->
            ErrorBanner(message = error, onDismiss = onClearError)
        }
    }

    if (editingName) {
        EditNameDialog(
            initialName = displayName,
            onSave = { name ->
                onDisplayNameChange(name)
                editingName = false
            },
            onDismiss = { editingName = false },
        )
    }
}

/**
 * The account being created: a sticker of the person's initial, their name, and their username.
 *
 * The username is shown as `@username` only. A fediverse-style `@user@domain` address would promise
 * that other servers can find and mention the account, which nothing supports yet.
 */
@Composable
private fun AccountIdentityCard(
    displayName: String,
    username: String,
    onEditName: () -> Unit,
    onEditUsername: () -> Unit,
    editEnabled: Boolean,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MonogramSticker(
                initial =
                    displayName
                        .trim()
                        .firstOrNull()
                        ?.uppercase()
                        .orEmpty(),
            )
            Spacer(Modifier.height(Spacing.lg))
            Text(
                text = displayName,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            if (username.isNotBlank()) {
                Text(
                    text = "@$username",
                    // Usernames are Latin-script identifiers; in right-to-left layouts the "@" must stay first.
                    style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(Spacing.md))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                OutlinedButton(onClick = onEditName, enabled = editEnabled) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(Res.string.account_edit_name))
                }
                OutlinedButton(onClick = onEditUsername, enabled = editEnabled) {
                    Icon(Icons.Default.AlternateEmail, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(Res.string.account_edit_username))
                }
            }
        }
    }
}

/**
 * The person's initial as a die-cut sticker: a white border, a paper shadow, and a slight tilt,
 * matching how LogDate draws stickers. The white border stays white in dark mode, like real paper.
 */
@Composable
private fun MonogramSticker(initial: String) {
    Box(
        modifier =
            Modifier
                .rotate(-6f)
                .shadow(elevation = 6.dp, shape = CircleShape)
                .background(Color.White, CircleShape)
                .padding(5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(76.dp)
                    .background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initial,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

/** One line about how sign-in works and where the account lives, with the passkey details on demand. */
@Composable
private fun PasskeyLine(serverDisplayName: String) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Key,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = stringResource(Res.string.account_passkey_server_line, serverDisplayName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { expanded = !expanded }) {
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Outlined.Info,
                    contentDescription = stringResource(Res.string.about_passkeys),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.padding(start = Spacing.xl, bottom = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                PasskeyPoint(stringResource(Res.string.account_passkey_point_device_auth))
                PasskeyPoint(stringResource(Res.string.account_passkey_point_multi_device))
            }
        }
    }
}

@Composable
private fun EditNameDialog(
    initialName: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.account_edit_name)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(Res.string.account_name_label)) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) {
                Text(stringResource(UiRes.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(UiRes.string.common_cancel)) }
        },
    )
}

@Composable
private fun PasskeyPoint(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = Spacing.xs).size(16.dp),
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NoticeCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun ErrorBanner(
    message: String,
    onDismiss: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = Spacing.md, top = Spacing.sm, bottom = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(UiRes.string.common_dismiss),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Preview
@Composable
private fun PasskeyAccountCreationFinalScreenPreview() {
    MaterialTheme {
        Surface {
            PasskeyAccountCreationFinalContent(
                displayName = "Alex Johnson",
                username = "alex_j",
                onCreateAccount = {},
                onBack = {},
                isCreatingAccount = false,
                errorMessage = null,
                onClearError = {},
                isPasskeySupported = true,
                serverDisplayName = "LogDate Cloud",
                stepNumber = 2,
                stepCount = 2,
            )
        }
    }
}

@Preview
@Composable
private fun PasskeyAccountCreationFinalScreenErrorPreview() {
    MaterialTheme {
        Surface {
            PasskeyAccountCreationFinalContent(
                displayName = "Alex Johnson",
                username = "alex_j",
                onCreateAccount = {},
                onBack = {},
                isCreatingAccount = false,
                errorMessage = "Too many attempts. Please wait a moment before trying again.",
                onClearError = {},
                isPasskeySupported = true,
                serverDisplayName = "LogDate Cloud",
                stepNumber = 3,
                stepCount = 3,
            )
        }
    }
}
