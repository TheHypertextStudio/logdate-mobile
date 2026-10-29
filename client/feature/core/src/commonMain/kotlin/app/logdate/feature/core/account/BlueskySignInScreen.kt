@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.account

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.logdate.client.sync.SyncManager
import app.logdate.ui.common.PlatformBackHandler
import kotlinx.coroutines.CancellationException
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun BlueskySignInScreen(
    attemptId: String?,
    onAccountCreated: () -> Unit,
    onBack: () -> Unit,
) {
    val viewModel: BlueskySignInViewModel = koinViewModel()
    val sync: SyncManager = koinInject()
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val stage by viewModel.stage.collectAsStateWithLifecycle()
    val syncStatus by sync.syncStatusFlow.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(attemptId) { attemptId?.let(viewModel::resume) }
    LaunchedEffect(ui.browserUrl) {
        ui.browserUrl?.let { url ->
            try {
                uriHandler.openUri(url)
                viewModel.browserOpened()
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (_: Exception) {
                viewModel.browserLaunchFailed()
            }
        }
    }
    LaunchedEffect(ui.finished) { if (ui.finished) viewModel.leaveCompletedFlow(onAccountCreated) }
    val cancel = { viewModel.cancel(onBack) }
    PlatformBackHandler(enabled = true, onBack = cancel)
    BlueskySignInContent(
        ui = ui,
        stage = stage,
        accountSetupContent = { BlueskyAccountSetupFlow(viewModel, onAccountCreated, onBack) },
        onHandleChange = viewModel::updateHandle,
        onStart = viewModel::start,
        onConfirm = { viewModel.confirmPasskey() },
        onAdopt = { viewModel.confirmPasskey(adoptLocalData = true) },
        onRestart = viewModel::restart,
        onCancel = cancel,
        syncStatus = syncStatus,
    )
}
