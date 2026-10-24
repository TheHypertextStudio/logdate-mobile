@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.account.recovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.logdate.client.repository.account.IdentityRecoveryFailure
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.account_recovery_check_failed
import logdate.client.feature.core.generated.resources.account_recovery_error_device
import logdate.client.feature.core.generated.resources.account_recovery_error_other
import logdate.client.feature.core.generated.resources.account_recovery_error_passkey_required
import logdate.client.feature.core.generated.resources.account_recovery_error_signed_out
import logdate.client.feature.core.generated.resources.account_recovery_error_step_up_failed
import logdate.client.feature.core.generated.resources.account_recovery_error_step_up_unavailable
import logdate.client.feature.core.generated.resources.account_recovery_error_unreachable
import logdate.client.feature.core.generated.resources.account_recovery_finish
import logdate.client.feature.core.generated.resources.account_recovery_finishing
import logdate.client.feature.core.generated.resources.account_recovery_needs_setup
import logdate.client.feature.core.generated.resources.account_recovery_ready
import logdate.client.feature.core.generated.resources.account_recovery_retrying_server
import logdate.client.feature.core.generated.resources.account_recovery_retrying_unreachable
import logdate.client.feature.core.generated.resources.account_recovery_try_again
import logdate.client.feature.core.generated.resources.account_recovery_try_now
import org.jetbrains.compose.resources.stringResource

/** Says whether the recovery phrase on this device can also move the account, and finishes setting it up. */
@Composable
internal fun AccountRecoveryNotice(
    state: AccountRecoveryUiState,
    onFinish: () -> Unit,
    onRetry: () -> Unit,
) {
    when (state) {
        AccountRecoveryUiState.Checking -> {
            Unit
        }

        AccountRecoveryUiState.Ready -> {
            DoneLine(stringResource(Res.string.account_recovery_ready))
        }

        AccountRecoveryUiState.NeedsSetup -> {
            Prompt(stringResource(Res.string.account_recovery_needs_setup), stringResource(Res.string.account_recovery_finish), onFinish)
        }

        AccountRecoveryUiState.Finishing -> {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(Res.string.account_recovery_finishing), style = MaterialTheme.typography.bodyMedium)
            }
        }

        is AccountRecoveryUiState.Retrying -> {
            Prompt(retryingMessage(state.failure), stringResource(Res.string.account_recovery_try_now), onRetry)
        }

        AccountRecoveryUiState.CheckFailed -> {
            Prompt(stringResource(Res.string.account_recovery_check_failed), stringResource(Res.string.account_recovery_try_again), onRetry)
        }

        is AccountRecoveryUiState.SetupFailed -> {
            Prompt(setupFailureMessage(state.failure), stringResource(Res.string.account_recovery_finish), onFinish)
        }
    }
}

/** A finished part of recovery setup, such as the confirmed phrase. */
@Composable
internal fun DoneLine(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.Top) {
        Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Prompt(
    message: String,
    action: String,
    onAction: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun retryingMessage(failure: IdentityRecoveryFailure?): String =
    stringResource(
        when (failure) {
            null, IdentityRecoveryFailure.UNREACHABLE -> Res.string.account_recovery_retrying_unreachable
            else -> Res.string.account_recovery_retrying_server
        },
    )

@Composable
private fun setupFailureMessage(failure: IdentityRecoveryFailure): String =
    stringResource(
        when (failure) {
            IdentityRecoveryFailure.STEP_UP_FAILED -> Res.string.account_recovery_error_step_up_failed
            IdentityRecoveryFailure.STEP_UP_UNAVAILABLE -> Res.string.account_recovery_error_step_up_unavailable
            IdentityRecoveryFailure.PASSKEY_REQUIRED -> Res.string.account_recovery_error_passkey_required
            IdentityRecoveryFailure.SIGNED_OUT -> Res.string.account_recovery_error_signed_out
            IdentityRecoveryFailure.UNREACHABLE -> Res.string.account_recovery_error_unreachable
            IdentityRecoveryFailure.DEVICE_ERROR -> Res.string.account_recovery_error_device
            else -> Res.string.account_recovery_error_other
        },
    )
