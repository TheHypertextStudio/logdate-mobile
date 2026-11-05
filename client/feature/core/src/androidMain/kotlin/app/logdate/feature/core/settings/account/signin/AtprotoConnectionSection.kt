@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.account.signin

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.logdate.client.feature.core.R
import app.logdate.client.repository.account.AccountConnectionProblem
import app.logdate.client.repository.account.AccountConnectionState
import app.logdate.feature.core.settings.ui.dialogs.DangerConfirmationDialog
import app.logdate.shared.model.AtprotoConnectionState
import app.logdate.ui.common.SettingsSection
import app.logdate.ui.common.SimpleSettingsItem
import app.logdate.ui.theme.Spacing

/** Account authorization belongs beside the existing sign-in methods, without owning journal access. */
@Composable
fun AtprotoConnectionSection(
    state: AccountConnectionState,
    onRequestDisconnect: () -> Unit,
    onConfirmDisconnect: () -> Unit,
    onCancelConfirmation: () -> Unit,
    onReconnect: () -> Unit,
    onRefresh: () -> Unit,
    onCancelPending: () -> Unit,
) {
    if (!state.available || state.snapshot?.eligible == false) return
    val snapshot = state.snapshot
    val status =
        when (snapshot?.state) {
            AtprotoConnectionState.CONNECTED -> stringResource(R.string.account_connection_connected)
            AtprotoConnectionState.EXPIRED -> stringResource(R.string.account_connection_expired)
            AtprotoConnectionState.REVOKED -> stringResource(R.string.account_connection_revoked)
            AtprotoConnectionState.RECONNECT_REQUIRED -> stringResource(R.string.account_connection_required)
            AtprotoConnectionState.DISCONNECTED -> stringResource(R.string.account_connection_disconnected)
            null -> stringResource(if (state.busy) R.string.account_connection_checking else R.string.account_connection_unavailable)
        }
    val statusTitle =
        if (state.observationFailed && snapshot != null) {
            stringResource(R.string.account_connection_last_known, status)
        } else {
            status
        }
    val disconnect = snapshot?.state == AtprotoConnectionState.CONNECTED
    val actionTitle =
        when {
            state.hasPendingDisconnect -> R.string.account_connection_retry
            state.waitingForBrowser -> R.string.account_connection_cancel
            disconnect -> R.string.account_connection_disconnect
            else -> R.string.account_connection_reconnect
        }
    val action =
        when {
            state.hasPendingDisconnect -> onRequestDisconnect
            state.waitingForBrowser -> onCancelPending
            disconnect -> onRequestDisconnect
            else -> onReconnect
        }
    val canAct =
        !state.busy &&
            (
                state.hasPendingDisconnect ||
                    state.waitingForBrowser ||
                    !disconnect ||
                    (snapshot?.canDisconnect == true && !state.observationFailed)
            )

    SettingsSection(title = stringResource(R.string.account_connection_title), modifier = Modifier.padding(horizontal = Spacing.lg)) {
        SimpleSettingsItem(title = statusTitle, description = connectionDescription(state), action = {})
        if (snapshot != null) {
            SimpleSettingsItem(
                title = stringResource(actionTitle),
                description =
                    if (disconnect ||
                        state.hasPendingDisconnect
                    ) {
                        stringResource(R.string.account_connection_journal_preserved)
                    } else {
                        null
                    },
                enabled = canAct,
                onClick = action,
                action = {},
            )
        }
        SimpleSettingsItem(
            title = stringResource(R.string.account_connection_check),
            description = if (state.busy) stringResource(R.string.account_connection_checking) else null,
            enabled = !state.busy,
            onClick = onRefresh,
            action = {},
        )
    }
    if (state.confirmingDisconnect) {
        DangerConfirmationDialog(
            onDismissRequest = onCancelConfirmation,
            onConfirmation = { if (!state.busy) onConfirmDisconnect() },
            title =
                stringResource(
                    if (state.hasPendingDisconnect) {
                        R.string.account_connection_retry_title
                    } else {
                        R.string.account_connection_disconnect_title
                    },
                ),
            message =
                stringResource(
                    if (state.hasPendingDisconnect) {
                        R.string.account_connection_retry_message
                    } else {
                        R.string.account_connection_disconnect_message
                    },
                ),
            confirmButtonText =
                stringResource(
                    if (state.hasPendingDisconnect) {
                        R.string.account_connection_retry_confirm
                    } else {
                        R.string.account_connection_disconnect_confirm
                    },
                ),
        )
    }
}

@Composable
private fun connectionDescription(state: AccountConnectionState): String? {
    val needsPasskey = state.snapshot?.let { it.state == AtprotoConnectionState.CONNECTED && !it.canDisconnect } == true
    val resource =
        when (state.problem) {
            AccountConnectionProblem.BROWSER_UNAVAILABLE -> R.string.account_connection_browser_unavailable
            AccountConnectionProblem.UNAVAILABLE -> R.string.account_connection_observation_failed
            AccountConnectionProblem.CHANGED -> R.string.account_connection_changed
            AccountConnectionProblem.PROOF_INVALID -> R.string.account_connection_proof_invalid
            AccountConnectionProblem.PROOF_EXPIRED -> R.string.account_connection_proof_expired
            AccountConnectionProblem.LAST_SIGN_IN_FACTOR -> R.string.account_connection_last_method
            AccountConnectionProblem.INELIGIBLE -> R.string.account_connection_ineligible
            AccountConnectionProblem.RATE_LIMITED -> R.string.account_connection_rate_limited
            null ->
                when {
                    state.newerConnectionPreserved -> R.string.account_connection_newer_preserved
                    state.remoteRevocationUnconfirmed -> R.string.account_connection_remote_unconfirmed
                    state.waitingForBrowser -> R.string.account_connection_finish_browser
                    needsPasskey -> R.string.account_connection_needs_passkey
                    else -> null
                }
        }
    return resource?.let { stringResource(it) }
}
