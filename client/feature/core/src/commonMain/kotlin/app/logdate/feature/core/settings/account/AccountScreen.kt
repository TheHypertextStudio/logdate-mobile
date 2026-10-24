@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.account

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.logdate.client.repository.account.LinkedSignInProvider
import app.logdate.feature.core.settings.ui.EmailVerificationBottomSheet
import app.logdate.ui.adaptive.FoldableBookLayout
import app.logdate.ui.common.AlertDialog
import app.logdate.ui.common.MaterialContainer
import app.logdate.ui.common.SettingsNavigationItem
import app.logdate.ui.common.SettingsScaffold
import app.logdate.ui.common.SettingsSection
import app.logdate.ui.common.SimpleSettingsItem
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.account_delete_row_description
import logdate.client.feature.core.generated.resources.account_delete_title
import logdate.client.feature.core.generated.resources.account_email_not_verified
import logdate.client.feature.core.generated.resources.account_email_title
import logdate.client.feature.core.generated.resources.account_email_verified
import logdate.client.feature.core.generated.resources.account_email_verify
import logdate.client.feature.core.generated.resources.account_hosting_description
import logdate.client.feature.core.generated.resources.account_profile_edit_label
import logdate.client.feature.core.generated.resources.account_recovery_phrase_checking
import logdate.client.feature.core.generated.resources.account_recovery_phrase_finish_setup
import logdate.client.feature.core.generated.resources.account_recovery_phrase_missing
import logdate.client.feature.core.generated.resources.account_recovery_phrase_saved
import logdate.client.feature.core.generated.resources.account_recovery_phrase_title
import logdate.client.feature.core.generated.resources.account_section_hosting
import logdate.client.feature.core.generated.resources.account_section_sign_in_recovery
import logdate.client.feature.core.generated.resources.account_sign_in_passkeys
import logdate.client.feature.core.generated.resources.account_sign_in_passkeys_none
import logdate.client.feature.core.generated.resources.account_sign_out_action
import logdate.client.feature.core.generated.resources.account_sign_out_confirm_message
import logdate.client.feature.core.generated.resources.account_sign_out_dialog_title
import logdate.client.feature.core.generated.resources.account_sign_out_failed
import logdate.client.feature.core.generated.resources.account_sign_out_row_description
import logdate.client.feature.core.generated.resources.account_signed_out_body
import logdate.client.feature.core.generated.resources.account_signed_out_create
import logdate.client.feature.core.generated.resources.account_signed_out_sign_in
import logdate.client.feature.core.generated.resources.account_signed_out_title
import logdate.client.feature.core.generated.resources.account_title
import logdate.client.feature.core.generated.resources.sign_in_methods_detail_pair
import logdate.client.feature.core.generated.resources.sign_in_methods_google
import logdate.client.feature.core.generated.resources.sign_in_methods_other_provider
import logdate.client.feature.core.generated.resources.sign_in_methods_row_description
import logdate.client.feature.core.generated.resources.sign_in_methods_title
import logdate.client.feature.core.generated.resources.username_handle
import logdate.client.ui.generated.resources.common_cancel
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import logdate.client.ui.generated.resources.Res as UiRes

/** Where each row on the Account screen leads. */
data class AccountDestinations(
    val onBack: () -> Unit,
    val onProfile: () -> Unit,
    val onSignInMethods: () -> Unit,
    val onRecoveryPhrase: () -> Unit,
    val onHosting: () -> Unit,
    val onDeleteAccount: () -> Unit,
    val onSignIn: () -> Unit,
    val onCreateAccount: () -> Unit,
)

@Composable
fun AccountScreen(
    destinations: AccountDestinations,
    viewModel: AccountViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val emailVerification by viewModel.emailVerification.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showEmailVerification by remember { mutableStateOf(false) }

    // Coming back from a subpage, such as after adding a passkey, reloads what may have changed there.
    LaunchedEffect(Unit) { viewModel.refresh() }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                AccountEvent.SignedOut -> destinations.onBack()
                AccountEvent.SignOutFailed -> snackbarHostState.showSnackbar(getString(Res.string.account_sign_out_failed))
            }
        }
    }

    AccountContent(
        state = state,
        destinations = destinations,
        onOpenEmailVerification = { showEmailVerification = true },
        onSignOut = viewModel::signOut,
        snackbarHostState = snackbarHostState,
    )

    if (showEmailVerification) {
        EmailVerificationBottomSheet(
            isVerifying = emailVerification.isVerifying,
            outcome = emailVerification.outcome,
            onVerifyClick = viewModel::verifyEmail,
            onDismiss = {
                showEmailVerification = false
                viewModel.dismissEmailVerification()
            },
        )
    }
}

@Composable
fun AccountContent(
    state: AccountUiState,
    destinations: AccountDestinations,
    onOpenEmailVerification: () -> Unit,
    onSignOut: () -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    var confirmSignOut by remember { mutableStateOf(false) }

    val title = stringResource(Res.string.account_title)
    when (state) {
        AccountUiState.Loading -> {
            SettingsScaffold(title = title, onBack = destinations.onBack, snackbarHostState = snackbarHostState) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(Spacing.xl), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        }

        AccountUiState.SignedOut -> {
            SettingsScaffold(title = title, onBack = destinations.onBack, snackbarHostState = snackbarHostState) {
                item { SignedOutPrompt(destinations) }
            }
        }

        is AccountUiState.SignedIn -> {
            FoldableBookLayout(
                modifier = Modifier.fillMaxSize(),
                minPaneWidth = 320.dp,
                startPane = {
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = Spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(horizontal = Spacing.lg),
                        )
                        AccountHeaderCard(state.header, onEditProfile = destinations.onProfile)
                        SignInAndRecoverySection(state, destinations, onOpenEmailVerification)
                    }
                },
                endPane = {
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = Spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
                    ) {
                        HostingAndSessionSections(state, destinations, onSignOut = { confirmSignOut = true })
                    }
                },
                standardContent = {
                    SettingsScaffold(title = title, onBack = destinations.onBack, snackbarHostState = snackbarHostState) {
                        item { AccountHeaderCard(state.header, onEditProfile = destinations.onProfile) }
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                                SignInAndRecoverySection(state, destinations, onOpenEmailVerification)
                                HostingAndSessionSections(state, destinations, onSignOut = { confirmSignOut = true })
                            }
                        }
                    }
                },
            )
        }
    }

    val signedIn = state as? AccountUiState.SignedIn
    if (confirmSignOut && signedIn != null) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(stringResource(Res.string.account_sign_out_dialog_title)) },
            text = { Text(stringResource(Res.string.account_sign_out_confirm_message, signedIn.server.displayName())) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmSignOut = false
                        onSignOut()
                    },
                ) { Text(stringResource(Res.string.account_sign_out_action)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOut = false }) { Text(stringResource(UiRes.string.common_cancel)) }
            },
        )
    }
}

@Composable
private fun AccountHeaderCard(
    header: AccountHeader,
    onEditProfile: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .clickable(onClick = onEditProfile)
                .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Box(
            modifier =
                Modifier
                    .size(64.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = header.displayName.firstOrNull()?.uppercase() ?: "",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                text = header.displayName,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            header.username?.let {
                Text(
                    text = stringResource(Res.string.username_handle, it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(Res.string.account_profile_edit_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun SignInAndRecoverySection(
    state: AccountUiState.SignedIn,
    destinations: AccountDestinations,
    onOpenEmailVerification: () -> Unit,
) {
    SettingsSection(
        title = stringResource(Res.string.account_section_sign_in_recovery),
        modifier = Modifier.padding(horizontal = Spacing.lg),
    ) {
        SettingsNavigationItem(
            title = stringResource(Res.string.sign_in_methods_title),
            description = signInSummary(state.signIn),
            icon = { Icon(Icons.Outlined.Key, contentDescription = null) },
            onClick = destinations.onSignInMethods,
        )
        SettingsNavigationItem(
            title = stringResource(Res.string.account_recovery_phrase_title),
            description =
                stringResource(
                    when {
                        state.hasRecoveryPhrase == true && state.accountRecoveryNeedsAttention -> {
                            Res.string.account_recovery_phrase_finish_setup
                        }

                        state.hasRecoveryPhrase == true -> {
                            Res.string.account_recovery_phrase_saved
                        }

                        state.hasRecoveryPhrase == false -> {
                            Res.string.account_recovery_phrase_missing
                        }

                        else -> {
                            Res.string.account_recovery_phrase_checking
                        }
                    },
                ),
            icon = {
                if (state.hasRecoveryPhrase == false || state.accountRecoveryNeedsAttention) {
                    Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                } else {
                    Icon(Icons.Outlined.Password, contentDescription = null)
                }
            },
            onClick = destinations.onRecoveryPhrase,
        )
        state.email?.let { email -> EmailItem(email, onOpenEmailVerification) }
    }
}

@Composable
private fun HostingAndSessionSections(
    state: AccountUiState.SignedIn,
    destinations: AccountDestinations,
    onSignOut: () -> Unit,
) {
    SettingsSection(
        title = stringResource(Res.string.account_section_hosting),
        modifier = Modifier.padding(horizontal = Spacing.lg),
    ) {
        SettingsNavigationItem(
            title = state.server.name ?: state.server.host,
            description = stringResource(Res.string.account_hosting_description, state.server.host),
            icon = { Icon(Icons.Outlined.Cloud, contentDescription = null) },
            onClick = destinations.onHosting,
        )
    }

    MaterialContainer(modifier = Modifier.padding(horizontal = Spacing.lg)) {
        SimpleSettingsItem(
            title = stringResource(Res.string.account_sign_out_action),
            description = stringResource(Res.string.account_sign_out_row_description),
            leadingContent = { Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null) },
            onClick = onSignOut,
            enabled = !state.isSigningOut,
            action = {
                if (state.isSigningOut) CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            },
        )
        SettingsNavigationItem(
            title = stringResource(Res.string.account_delete_title),
            description = stringResource(Res.string.account_delete_row_description, state.server.displayName()),
            icon = { Icon(Icons.Outlined.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            onClick = destinations.onDeleteAccount,
            destructive = true,
        )
    }
}

@Composable
private fun EmailItem(
    email: EmailRow,
    onOpenEmailVerification: () -> Unit,
) {
    val description =
        when {
            email.address == null -> stringResource(Res.string.account_email_verify)
            email.isVerified -> stringResource(Res.string.account_email_verified, email.address)
            else -> stringResource(Res.string.account_email_not_verified, email.address)
        }
    if (email.canVerify) {
        SettingsNavigationItem(
            title = stringResource(Res.string.account_email_title),
            description = description,
            icon = { Icon(Icons.Outlined.MailOutline, contentDescription = null) },
            onClick = onOpenEmailVerification,
        )
    } else {
        SimpleSettingsItem(
            title = stringResource(Res.string.account_email_title),
            description = description,
            leadingContent = { Icon(Icons.Outlined.MailOutline, contentDescription = null) },
            action = {},
        )
    }
}

@Composable
private fun signInSummary(summary: SignInSummary): String {
    if (summary !is SignInSummary.Known) return stringResource(Res.string.sign_in_methods_row_description)
    val passkeys =
        if (summary.passkeyCount == 0) {
            stringResource(Res.string.account_sign_in_passkeys_none)
        } else {
            pluralStringResource(Res.plurals.account_sign_in_passkeys, summary.passkeyCount, summary.passkeyCount)
        }
    return summary.linkedProviders.fold(passkeys) { text, kind ->
        stringResource(
            Res.string.sign_in_methods_detail_pair,
            text,
            stringResource(
                when (kind) {
                    LinkedSignInProvider.Kind.GOOGLE -> Res.string.sign_in_methods_google
                    LinkedSignInProvider.Kind.OTHER -> Res.string.sign_in_methods_other_provider
                },
            ),
        )
    }
}

@Composable
private fun SignedOutPrompt(destinations: AccountDestinations) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text(stringResource(Res.string.account_signed_out_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(Res.string.account_signed_out_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = destinations.onSignIn, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.account_signed_out_sign_in))
        }
        OutlinedButton(onClick = destinations.onCreateAccount, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.account_signed_out_create))
        }
    }
}

private fun ServerRow.displayName(): String = name ?: host

@Preview
@Composable
private fun AccountPreview() {
    AccountContent(
        state =
            AccountUiState.SignedIn(
                header = AccountHeader(displayName = "Alice Chen", username = "alice"),
                signIn = SignInSummary.Known(passkeyCount = 2, linkedProviders = listOf(LinkedSignInProvider.Kind.GOOGLE)),
                hasRecoveryPhrase = true,
                email = EmailRow(address = "alice@example.com", isVerified = true, canVerify = false),
                server = ServerRow(name = "LogDate Cloud", host = "cloud.logdate.app", isLogDateCloud = true),
                isSigningOut = false,
            ),
        destinations = AccountDestinations({}, {}, {}, {}, {}, {}, {}, {}),
        onOpenEmailVerification = {},
        onSignOut = {},
    )
}
