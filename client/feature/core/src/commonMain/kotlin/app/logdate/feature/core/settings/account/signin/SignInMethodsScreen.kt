@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.account.signin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.logdate.client.repository.account.LinkedSignInProvider
import app.logdate.feature.core.settings.ui.dialogs.DangerConfirmationDialog
import app.logdate.ui.common.SettingsRow
import app.logdate.ui.common.SettingsScaffold
import app.logdate.ui.common.SettingsSection
import app.logdate.ui.common.SimpleSettingsItem
import app.logdate.ui.common.formatting.LocalToday
import app.logdate.ui.common.formatting.asRelativeDate
import app.logdate.ui.theme.Spacing
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.sign_in_methods_add_failed_already_here
import logdate.client.feature.core.generated.resources.sign_in_methods_add_failed_offline
import logdate.client.feature.core.generated.resources.sign_in_methods_add_failed_platform
import logdate.client.feature.core.generated.resources.sign_in_methods_add_failed_server
import logdate.client.feature.core.generated.resources.sign_in_methods_add_failed_signed_out
import logdate.client.feature.core.generated.resources.sign_in_methods_add_passkey
import logdate.client.feature.core.generated.resources.sign_in_methods_add_passkey_description
import logdate.client.feature.core.generated.resources.sign_in_methods_added_on
import logdate.client.feature.core.generated.resources.sign_in_methods_added_today
import logdate.client.feature.core.generated.resources.sign_in_methods_added_yesterday
import logdate.client.feature.core.generated.resources.sign_in_methods_adding_passkey
import logdate.client.feature.core.generated.resources.sign_in_methods_detail_pair
import logdate.client.feature.core.generated.resources.sign_in_methods_google
import logdate.client.feature.core.generated.resources.sign_in_methods_intro
import logdate.client.feature.core.generated.resources.sign_in_methods_linked_section
import logdate.client.feature.core.generated.resources.sign_in_methods_load_again
import logdate.client.feature.core.generated.resources.sign_in_methods_load_failed_offline
import logdate.client.feature.core.generated.resources.sign_in_methods_load_failed_server
import logdate.client.feature.core.generated.resources.sign_in_methods_load_failed_signed_out
import logdate.client.feature.core.generated.resources.sign_in_methods_load_failed_title
import logdate.client.feature.core.generated.resources.sign_in_methods_only_method
import logdate.client.feature.core.generated.resources.sign_in_methods_other_provider
import logdate.client.feature.core.generated.resources.sign_in_methods_passkey_added
import logdate.client.feature.core.generated.resources.sign_in_methods_passkey_added_for
import logdate.client.feature.core.generated.resources.sign_in_methods_passkey_removed
import logdate.client.feature.core.generated.resources.sign_in_methods_passkeys_section
import logdate.client.feature.core.generated.resources.sign_in_methods_refreshing
import logdate.client.feature.core.generated.resources.sign_in_methods_remove_failed_last
import logdate.client.feature.core.generated.resources.sign_in_methods_remove_failed_offline
import logdate.client.feature.core.generated.resources.sign_in_methods_remove_failed_server
import logdate.client.feature.core.generated.resources.sign_in_methods_remove_message
import logdate.client.feature.core.generated.resources.sign_in_methods_remove_named_title
import logdate.client.feature.core.generated.resources.sign_in_methods_remove_passkey_action
import logdate.client.feature.core.generated.resources.sign_in_methods_remove_title
import logdate.client.feature.core.generated.resources.sign_in_methods_signed_in_on
import logdate.client.feature.core.generated.resources.sign_in_methods_signed_in_today
import logdate.client.feature.core.generated.resources.sign_in_methods_signed_in_yesterday
import logdate.client.feature.core.generated.resources.sign_in_methods_title
import logdate.client.feature.core.generated.resources.sign_in_methods_unnamed_passkey
import logdate.client.feature.core.generated.resources.sign_in_methods_used_on
import logdate.client.feature.core.generated.resources.sign_in_methods_used_today
import logdate.client.feature.core.generated.resources.sign_in_methods_used_yesterday
import logdate.client.ui.generated.resources.common_remove
import logdate.client.ui.generated.resources.common_retry
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import logdate.client.ui.generated.resources.Res as UiRes

/** Lists the ways a person signs in to their account and lets them add or remove passkeys. */
@Composable
fun SignInMethodsScreen(
    onBack: () -> Unit,
    viewModel: SignInMethodsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, snackbarHostState, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.notices.collect { notices ->
                val notice = notices.firstOrNull() ?: return@collect
                snackbarHostState.showSnackbar(notice.event.message())
                currentCoroutineContext().ensureActive()
                viewModel.acknowledgeNotice(notice.id)
            }
        }
    }

    SignInMethodsContent(
        state = state,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onRetry = viewModel::refresh,
        onAddPasskey = viewModel::addPasskey,
        onRemovePasskey = viewModel::removePasskey,
    )
}

@Composable
fun SignInMethodsContent(
    state: SignInMethodsUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onAddPasskey: () -> Unit,
    onRemovePasskey: (credentialId: String) -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    var pendingRemoval by remember { mutableStateOf<PasskeyRow?>(null) }
    LaunchedEffect(state) {
        val loaded = state as? SignInMethodsUiState.Loaded
        if (loaded == null || loaded.isBusy || loaded.passkeys.none { it.credentialId == pendingRemoval?.credentialId && it.canRemove }) {
            pendingRemoval = null
        }
    }

    SettingsScaffold(
        title = stringResource(Res.string.sign_in_methods_title),
        onBack = onBack,
        snackbarHostState = snackbarHostState,
    ) {
        when (state) {
            SignInMethodsUiState.Loading ->
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(Spacing.xl), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

            SignInMethodsUiState.NotLoaded ->
                item {
                    FilledTonalButton(onClick = onRetry, modifier = Modifier.padding(horizontal = Spacing.lg)) {
                        Text(stringResource(Res.string.sign_in_methods_load_again))
                    }
                }

            is SignInMethodsUiState.Failed ->
                item { LoadFailed(reason = state.reason, onRetry = onRetry) }

            is SignInMethodsUiState.Loaded -> {
                when (val refresh = state.refresh) {
                    SignInMethodsRefreshState.Running ->
                        item {
                            Row(
                                modifier = Modifier.padding(horizontal = Spacing.lg),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text(stringResource(Res.string.sign_in_methods_refreshing))
                            }
                        }
                    is SignInMethodsRefreshState.Failed -> item { LoadFailed(refresh.reason, onRetry) }
                    SignInMethodsRefreshState.Idle -> Unit
                }
                item {
                    Text(
                        text = stringResource(Res.string.sign_in_methods_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.lg),
                    )
                }
                item {
                    SettingsSection(
                        title = stringResource(Res.string.sign_in_methods_passkeys_section),
                        modifier = Modifier.padding(horizontal = Spacing.lg),
                    ) {
                        state.passkeys.forEach { passkey ->
                            PasskeyItem(
                                passkey = passkey,
                                isRemoving = state.removingCredentialId == passkey.credentialId,
                                enabled = !state.isBusy,
                                failure =
                                    (state.removal as? PasskeyRemovalState.Failed)
                                        ?.takeIf {
                                            it.credentialId == passkey.credentialId
                                        }?.reason,
                                onRemove = { pendingRemoval = passkey },
                            )
                        }
                        if (state.canAddPasskey) {
                            AddPasskeyItem(
                                isAdding = state.isAddingPasskey,
                                enabled = !state.isBusy,
                                failure = (state.add as? AddPasskeyState.Failed)?.reason,
                                onClick = onAddPasskey,
                            )
                        }
                    }
                }
                if (state.linkedProviders.isNotEmpty()) {
                    item {
                        SettingsSection(
                            title = stringResource(Res.string.sign_in_methods_linked_section),
                            modifier = Modifier.padding(horizontal = Spacing.lg),
                        ) {
                            state.linkedProviders.forEach { provider -> LinkedProviderItem(provider) }
                        }
                    }
                }
            }
        }
    }

    pendingRemoval?.let { passkey ->
        DangerConfirmationDialog(
            onDismissRequest = { pendingRemoval = null },
            onConfirmation = {
                pendingRemoval = null
                onRemovePasskey(passkey.credentialId)
            },
            title =
                passkey.deviceName?.let { stringResource(Res.string.sign_in_methods_remove_named_title, it) }
                    ?: stringResource(Res.string.sign_in_methods_remove_title),
            message = stringResource(Res.string.sign_in_methods_remove_message),
            confirmButtonText = stringResource(UiRes.string.common_remove),
        )
    }
}

@Composable
private fun PasskeyItem(
    passkey: PasskeyRow,
    isRemoving: Boolean,
    enabled: Boolean,
    failure: RemovePasskeyFailure?,
    onRemove: () -> Unit,
) {
    val name = passkey.deviceName ?: stringResource(Res.string.sign_in_methods_unnamed_passkey)
    SettingsRow(
        leadingContent = { Icon(Icons.Outlined.Key, contentDescription = null) },
        headlineContent = { Text(name) },
        supportingContent = {
            val dates = passkeyDates(passkey)
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                failure?.let { Text(stringResource(it.message), color = MaterialTheme.colorScheme.error) }
                Text(
                    text =
                        if (passkey.canRemove) {
                            dates
                        } else {
                            stringResource(
                                Res.string.sign_in_methods_detail_pair,
                                dates,
                                stringResource(Res.string.sign_in_methods_only_method),
                            )
                        },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = {
            when {
                isRemoving -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                passkey.canRemove ->
                    IconButton(onClick = onRemove, enabled = enabled) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = stringResource(Res.string.sign_in_methods_remove_passkey_action, name),
                        )
                    }
            }
        },
    )
}

@Composable
private fun passkeyDates(passkey: PasskeyRow): String {
    val today = LocalToday.current
    val added =
        passkey.addedOn?.let {
            relativeDay(
                it,
                today,
                Res.string.sign_in_methods_added_today,
                Res.string.sign_in_methods_added_yesterday,
                Res.string.sign_in_methods_added_on,
            )
        }
    val used =
        passkey.lastUsedOn?.let {
            relativeDay(
                it,
                today,
                Res.string.sign_in_methods_used_today,
                Res.string.sign_in_methods_used_yesterday,
                Res.string.sign_in_methods_used_on,
            )
        }
    return when {
        added != null && used != null -> stringResource(Res.string.sign_in_methods_detail_pair, added, used)
        else -> added ?: used.orEmpty()
    }
}

@Composable
private fun AddPasskeyItem(
    isAdding: Boolean,
    enabled: Boolean,
    failure: AddPasskeyFailure?,
    onClick: () -> Unit,
) {
    SimpleSettingsItem(
        title = stringResource(if (isAdding) Res.string.sign_in_methods_adding_passkey else Res.string.sign_in_methods_add_passkey),
        description = stringResource(failure?.message ?: Res.string.sign_in_methods_add_passkey_description),
        leadingContent = {
            if (isAdding) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Outlined.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        },
        onClick = onClick,
        enabled = enabled,
        action = {},
    )
}

@Composable
private fun LinkedProviderItem(provider: LinkedProviderRow) {
    val today = LocalToday.current
    val lastSignIn =
        provider.lastSignInOn?.let {
            relativeDay(
                it,
                today,
                Res.string.sign_in_methods_signed_in_today,
                Res.string.sign_in_methods_signed_in_yesterday,
                Res.string.sign_in_methods_signed_in_on,
            )
        }
    val supporting =
        when {
            provider.email != null && lastSignIn != null ->
                stringResource(
                    Res.string.sign_in_methods_detail_pair,
                    provider.email,
                    lastSignIn,
                )
            else -> provider.email ?: lastSignIn
        }
    SettingsRow(
        leadingContent = { Icon(Icons.Outlined.AccountCircle, contentDescription = null) },
        headlineContent = {
            Text(
                stringResource(
                    when (provider.kind) {
                        LinkedSignInProvider.Kind.GOOGLE -> Res.string.sign_in_methods_google
                        LinkedSignInProvider.Kind.OTHER -> Res.string.sign_in_methods_other_provider
                    },
                ),
            )
        },
        supportingContent = supporting?.let { { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
    )
}

@Composable
private fun LoadFailed(
    reason: SignInMethodsLoadError,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text(stringResource(Res.string.sign_in_methods_load_failed_title), style = MaterialTheme.typography.titleMedium)
        Text(
            text =
                stringResource(
                    when (reason) {
                        SignInMethodsLoadError.NOT_SIGNED_IN -> Res.string.sign_in_methods_load_failed_signed_out
                        SignInMethodsLoadError.OFFLINE -> Res.string.sign_in_methods_load_failed_offline
                        SignInMethodsLoadError.SERVER -> Res.string.sign_in_methods_load_failed_server
                    },
                ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (reason != SignInMethodsLoadError.NOT_SIGNED_IN) {
            FilledTonalButton(onClick = onRetry) { Text(stringResource(UiRes.string.common_retry)) }
        }
    }
}

@Composable
private fun relativeDay(
    date: LocalDate,
    today: LocalDate,
    todayText: StringResource,
    yesterdayText: StringResource,
    onDayText: StringResource,
): String =
    when (date) {
        today -> stringResource(todayText)
        today.minus(1, DateTimeUnit.DAY) -> stringResource(yesterdayText)
        else -> stringResource(onDayText, date.asRelativeDate(today))
    }

private suspend fun SignInMethodsEvent.message(): String =
    when (this) {
        is SignInMethodsEvent.PasskeyAdded ->
            deviceName?.let { getString(Res.string.sign_in_methods_passkey_added_for, it) }
                ?: getString(Res.string.sign_in_methods_passkey_added)
        SignInMethodsEvent.PasskeyRemoved -> getString(Res.string.sign_in_methods_passkey_removed)
    }

private val AddPasskeyFailure.message: StringResource
    get() =
        when (this) {
            AddPasskeyFailure.ALREADY_ON_THIS_DEVICE -> Res.string.sign_in_methods_add_failed_already_here
            AddPasskeyFailure.NOT_SIGNED_IN -> Res.string.sign_in_methods_add_failed_signed_out
            AddPasskeyFailure.OFFLINE -> Res.string.sign_in_methods_add_failed_offline
            AddPasskeyFailure.PLATFORM -> Res.string.sign_in_methods_add_failed_platform
            AddPasskeyFailure.SERVER -> Res.string.sign_in_methods_add_failed_server
        }

private val RemovePasskeyFailure.message: StringResource
    get() =
        when (this) {
            RemovePasskeyFailure.LAST_SIGN_IN_METHOD -> Res.string.sign_in_methods_remove_failed_last
            RemovePasskeyFailure.OFFLINE -> Res.string.sign_in_methods_remove_failed_offline
            RemovePasskeyFailure.SERVER -> Res.string.sign_in_methods_remove_failed_server
        }

@Preview
@Composable
private fun SignInMethodsPreview() {
    SignInMethodsContent(
        state =
            SignInMethodsUiState.Loaded(
                passkeys =
                    listOf(
                        PasskeyRow("a", "Pixel 9", LocalDate(2026, 9, 3), LocalDate(2026, 9, 22), canRemove = true),
                        PasskeyRow("b", null, LocalDate(2026, 3, 12), null, canRemove = true),
                    ),
                linkedProviders = listOf(LinkedProviderRow(LinkedSignInProvider.Kind.GOOGLE, "alice@example.com", null)),
                canAddPasskey = true,
            ),
        onBack = {},
        onRetry = {},
        onAddPasskey = {},
        onRemovePasskey = {},
    )
}
