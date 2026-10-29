@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.account

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import app.logdate.client.repository.account.BlueskySignInState
import app.logdate.client.sync.SyncStatus
import app.logdate.ui.step.StepBusyButton
import app.logdate.ui.step.StepHeroIcon
import app.logdate.ui.step.StepScaffold
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.bluesky_confirm_passkey
import logdate.client.feature.core.generated.resources.bluesky_consent_action
import logdate.client.feature.core.generated.resources.bluesky_continue
import logdate.client.feature.core.generated.resources.bluesky_handle
import logdate.client.feature.core.generated.resources.bluesky_handle_hint
import logdate.client.feature.core.generated.resources.bluesky_restart
import org.jetbrains.compose.resources.stringResource

@Composable
fun BlueskySignInContent(
    ui: BlueskySignInUiState,
    stage: BlueskySignInState,
    accountSetupContent: @Composable () -> Unit,
    onHandleChange: (String) -> Unit,
    onStart: () -> Unit,
    onConfirm: () -> Unit,
    onAdopt: () -> Unit,
    onRestart: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    syncStatus: SyncStatus? = null,
) {
    if (stage is BlueskySignInState.AccountDetails || ui.creatingAccount) {
        accountSetupContent()
        return
    }
    if (stage is BlueskySignInState.Complete && ui.initialSync != InitialSyncStatus.NotStarted) {
        InitialSyncProgressScreen(ui.initialSync, syncStatus, modifier)
        return
    }
    StepScaffold(
        title = stringResource(blueskyTitle(ui, stage)),
        supportingText = blueskyDescription(ui, stage)?.let { stringResource(it) },
        onBack = onCancel,
        hero = { StepHeroIcon(Icons.Rounded.Cloud) },
        modifier = modifier.testTag("bluesky_screen"),
        actions = { BlueskyActions(ui, stage, onStart, onConfirm, onAdopt, onRestart) },
    ) {
        val failure = (stage as? BlueskySignInState.Failed)?.reason ?: ui.failure
        if (failure != null) {
            Text(
                stringResource(blueskyFailure(failure, stage)),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        BlueskyFields(ui, stage, onHandleChange, onStart)
    }
}

@Composable
private fun BlueskyFields(
    ui: BlueskySignInUiState,
    stage: BlueskySignInState,
    onHandleChange: (String) -> Unit,
    onStart: () -> Unit,
) {
    if (!ui.availabilityResolved || stage is BlueskySignInState.Working || stage is BlueskySignInState.Complete) {
        CircularProgressIndicator()
        return
    }
    if (!ui.available) return
    when (stage) {
        BlueskySignInState.Idle, is BlueskySignInState.Failed ->
            OutlinedTextField(
                value = ui.handle,
                onValueChange = onHandleChange,
                singleLine = true,
                enabled = !ui.busy,
                label = { Text(stringResource(Res.string.bluesky_handle)) },
                supportingText = { Text(stringResource(Res.string.bluesky_handle_hint)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (ui.handle.isNotBlank() && !ui.busy) onStart() }),
                modifier = Modifier.fillMaxWidth().testTag("bluesky_handle"),
            )
        is BlueskySignInState.AccountDetails -> Unit
        else -> Unit
    }
}

@Composable
private fun BlueskyActions(
    ui: BlueskySignInUiState,
    stage: BlueskySignInState,
    onStart: () -> Unit,
    onConfirm: () -> Unit,
    onAdopt: () -> Unit,
    onRestart: () -> Unit,
) {
    if (!ui.availabilityResolved || !ui.available) return
    when (stage) {
        BlueskySignInState.Idle, is BlueskySignInState.Failed ->
            StepBusyButton(
                stringResource(Res.string.bluesky_continue),
                onStart,
                ui.busy,
                enabled = ui.handle.isNotBlank(),
                modifier = Modifier.testTag("bluesky_start"),
            )
        BlueskySignInState.ConfirmPasskey ->
            StepBusyButton(
                stringResource(Res.string.bluesky_confirm_passkey),
                onConfirm,
                ui.busy,
                modifier = Modifier.testTag("bluesky_confirm"),
            )
        is BlueskySignInState.LocalDataConsent ->
            if (!stage.requiresNewSignIn) {
                StepBusyButton(stringResource(Res.string.bluesky_consent_action), onAdopt, ui.busy)
            } else {
                BlueskyRestart(onRestart)
            }
        is BlueskySignInState.DeviceApproval, BlueskySignInState.AwaitingBrowser -> BlueskyRestart(onRestart)
        else -> Unit
    }
}

@Composable
private fun BlueskyRestart(onRestart: () -> Unit) {
    TextButton(onClick = onRestart, modifier = Modifier.testTag("bluesky_restart")) { Text(stringResource(Res.string.bluesky_restart)) }
}
