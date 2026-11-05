@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.account

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.rounded.Cloud
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import app.logdate.ui.step.StepBusyButton
import app.logdate.ui.step.StepHeroIcon
import app.logdate.ui.step.StepScaffold
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.at
import logdate.client.feature.core.generated.resources.bluesky_continue
import logdate.client.feature.core.generated.resources.continue_with_google
import logdate.client.feature.core.generated.resources.privacy_policy
import logdate.client.feature.core.generated.resources.server_domain_sign_in_hint
import logdate.client.feature.core.generated.resources.server_sign_in_title
import logdate.client.feature.core.generated.resources.sign_in_help_action
import logdate.client.feature.core.generated.resources.sign_in_with_passkey
import logdate.client.feature.core.generated.resources.terms_of_service
import logdate.client.feature.core.generated.resources.text_4
import logdate.client.feature.core.generated.resources.username
import logdate.client.feature.core.generated.resources.your_username_2
import logdate.client.ui.generated.resources.common_try_again
import org.jetbrains.compose.resources.stringResource
import logdate.client.ui.generated.resources.Res as UiRes

@Composable
fun CloudAccountSignInScreen(
    onSignIn: (username: String) -> Unit,
    onAccountRecovery: () -> Unit,
    onPrivacyPolicy: (() -> Unit)?,
    onTermsOfService: (() -> Unit)?,
    onBack: () -> Unit,
    serverDisplayName: String,
    serverHandleDomain: String,
    isSigningIn: Boolean = false,
    errorMessage: String? = null,
    onClearError: () -> Unit = {},
    onSignInWithGoogle: (() -> Unit)? = null,
    onContinueWithBluesky: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var username by remember { mutableStateOf("") }

    CloudAccountSignInContent(
        username = username,
        onUsernameChange = { username = it },
        onSignIn = {
            onClearError()
            onSignIn(username)
        },
        onAccountRecovery = onAccountRecovery,
        onPrivacyPolicy = onPrivacyPolicy,
        onTermsOfService = onTermsOfService,
        onBack = onBack,
        serverDisplayName = serverDisplayName,
        serverHandleDomain = serverHandleDomain,
        isSigningIn = isSigningIn,
        errorMessage = errorMessage,
        onSignInWithGoogle =
            onSignInWithGoogle?.let {
                {
                    onClearError()
                    it()
                }
            },
        onContinueWithBluesky = onContinueWithBluesky,
        modifier = modifier,
    )
}

@Composable
fun CloudAccountSignInContent(
    username: String,
    onUsernameChange: (String) -> Unit,
    onSignIn: () -> Unit,
    onAccountRecovery: () -> Unit,
    onPrivacyPolicy: (() -> Unit)?,
    onTermsOfService: (() -> Unit)?,
    onBack: () -> Unit,
    serverDisplayName: String,
    serverHandleDomain: String,
    isSigningIn: Boolean = false,
    errorMessage: String? = null,
    onSignInWithGoogle: (() -> Unit)? = null,
    onContinueWithBluesky: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    StepScaffold(
        title = stringResource(Res.string.server_sign_in_title, serverDisplayName),
        onBack = onBack,
        modifier = modifier,
        supportingText = stringResource(Res.string.server_domain_sign_in_hint, serverHandleDomain),
        hero = { StepHeroIcon(Icons.Rounded.Cloud) },
        headerAlignment = Alignment.CenterHorizontally,
        actions = {
            StepBusyButton(
                text =
                    if (errorMessage != null) {
                        stringResource(UiRes.string.common_try_again)
                    } else {
                        stringResource(Res.string.sign_in_with_passkey)
                    },
                onClick = onSignIn,
                busy = isSigningIn,
            )
            if (onContinueWithBluesky != null) {
                OutlinedButton(onClick = onContinueWithBluesky, modifier = Modifier.fillMaxWidth(), enabled = !isSigningIn) {
                    Text(stringResource(Res.string.bluesky_continue))
                }
            }
            if (onSignInWithGoogle != null) {
                OutlinedButton(
                    onClick = onSignInWithGoogle,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSigningIn,
                ) {
                    Text(stringResource(Res.string.continue_with_google))
                }
            }
            TextButton(onClick = onAccountRecovery) {
                Text(stringResource(Res.string.sign_in_help_action))
            }
        },
        footer =
            if (onPrivacyPolicy != null || onTermsOfService != null) {
                { LegalLinksRow(onPrivacyPolicy = onPrivacyPolicy, onTermsOfService = onTermsOfService) }
            } else {
                null
            },
    ) {
        errorMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.large)
                        .padding(Spacing.lg),
            )
        }
        OutlinedTextField(
            value = username,
            onValueChange = onUsernameChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(Res.string.username)) },
            placeholder = { Text(stringResource(Res.string.your_username_2)) },
            prefix = { Text(stringResource(Res.string.at)) },
            suffix = { Text("@$serverHandleDomain") },
            singleLine = true,
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Done,
                ),
            keyboardActions = KeyboardActions(onDone = { if (!isSigningIn) onSignIn() }),
        )
    }
}

@Composable
private fun LegalLinksRow(
    onPrivacyPolicy: (() -> Unit)?,
    onTermsOfService: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.CenterHorizontally),
    ) {
        if (onPrivacyPolicy != null) {
            Text(
                text = stringResource(Res.string.privacy_policy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable(onClick = onPrivacyPolicy),
            )
        }
        if (onPrivacyPolicy != null && onTermsOfService != null) {
            Text(
                text = stringResource(Res.string.text_4),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onTermsOfService != null) {
            Text(
                text = stringResource(Res.string.terms_of_service),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable(onClick = onTermsOfService),
            )
        }
    }
}

@Preview
@Composable
private fun CloudAccountSignInScreenPreview() {
    MaterialTheme {
        Surface {
            CloudAccountSignInContent(
                username = "john-doe",
                onUsernameChange = {},
                onSignIn = {},
                onAccountRecovery = {},
                onPrivacyPolicy = {},
                onTermsOfService = {},
                onBack = {},
                serverDisplayName = "LogDate Cloud",
                serverHandleDomain = "logdate.app",
            )
        }
    }
}
