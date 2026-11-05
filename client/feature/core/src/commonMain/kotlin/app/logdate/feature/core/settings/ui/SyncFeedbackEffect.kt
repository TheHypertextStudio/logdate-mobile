@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.collectLatest
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.data_state_sync_request_failed
import logdate.client.feature.core.generated.resources.sync_feedback_needs_account
import logdate.client.feature.core.generated.resources.sync_feedback_sign_in_action
import logdate.client.feature.core.generated.resources.sync_feedback_started
import org.jetbrains.compose.resources.getString

@Composable
internal fun SyncFeedbackEffect(
    viewModel: DataSettingsViewModel,
    snackbarHostState: SnackbarHostState,
    onNavigateToSignIn: () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val navigateToSignIn by rememberUpdatedState(onNavigateToSignIn)
    LaunchedEffect(viewModel, snackbarHostState, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.syncFeedbackDelivery.collectLatest { delivery ->
                if (delivery == null) return@collectLatest
                when (delivery.feedback) {
                    SyncFeedback.NeedsAccount -> {
                        val result =
                            snackbarHostState.showSnackbar(
                                getString(Res.string.sync_feedback_needs_account),
                                getString(Res.string.sync_feedback_sign_in_action),
                            )
                        if (result == SnackbarResult.ActionPerformed) navigateToSignIn()
                    }
                    SyncFeedback.Started -> snackbarHostState.showSnackbar(getString(Res.string.sync_feedback_started))
                    SyncFeedback.Failed -> snackbarHostState.showSnackbar(getString(Res.string.data_state_sync_request_failed))
                }
                viewModel.acknowledgeSyncFeedback(delivery.id)
            }
        }
    }
}
