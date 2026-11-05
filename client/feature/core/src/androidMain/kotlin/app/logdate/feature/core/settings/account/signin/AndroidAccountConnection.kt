@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.account.signin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel

/** Returning null omits the entire lazy item when this account/server cannot use connection controls. */
@Composable
fun AndroidAccountConnection(viewModel: AtprotoConnectionViewModel = koinViewModel()): AccountConnectionSection? {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val browser = LocalUriHandler.current
    if (!state.available || state.snapshot?.eligible == false) return null
    return {
        AtprotoConnectionSection(
            state,
            onRequestDisconnect = viewModel::requestDisconnect,
            onConfirmDisconnect = viewModel::confirmDisconnect,
            onCancelConfirmation = viewModel::cancelConfirmation,
            onRefresh = viewModel::refresh,
            onCancelPending = viewModel::cancelPending,
            onReconnect = {
                viewModel.reconnect { url ->
                    try {
                        browser.openUri(url)
                        true
                    } catch (_: Exception) {
                        false
                    }
                }
            },
        )
    }
}
