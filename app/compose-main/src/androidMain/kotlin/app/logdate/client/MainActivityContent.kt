package app.logdate.client

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation3.runtime.NavKey
import app.logdate.client.repository.search.SearchResult
import app.logdate.client.updates.PlayInAppUpdateController
import app.logdate.feature.core.GlobalAppUiLoadedState
import app.logdate.feature.core.settings.updates.AppUpdatePrompt
import app.logdate.navigation.LogDateNavDisplay

/**
 * The root Compose tree for [MainActivity]: the shared navigation graph with the in-app update
 * prompt layered over it.
 */
@Composable
@Suppress("ktlint:standard:function-naming")
internal fun MainActivityContent(
    state: GlobalAppUiLoadedState,
    pendingNavKey: NavKey?,
    onPendingNavKeyConsumed: () -> Unit,
    onCurrentNavKeyChanged: (NavKey?) -> Unit,
    onShowUnlockPrompt: () -> Unit,
    onCloudSignIn: () -> Unit,
    onShareSearchResult: (SearchResult) -> Unit,
    updateController: PlayInAppUpdateController,
    onLaunchUpdate: () -> Unit,
    onCompleteUpdate: () -> Unit,
    onAutomaticUnlockPrompt: () -> Unit = onShowUnlockPrompt,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        LogDateNavDisplay(
            appUiState = state,
            onShowUnlockPrompt = onShowUnlockPrompt,
            onAutomaticUnlockPrompt = onAutomaticUnlockPrompt,
            onCloudSignIn = onCloudSignIn,
            pendingNavKey = pendingNavKey,
            onPendingNavKeyConsumed = onPendingNavKeyConsumed,
            onCurrentNavKeyChanged = onCurrentNavKeyChanged,
            onShareSearchResult = onShareSearchResult,
        )
        val updateState by updateController.uiState.collectAsState()
        AppUpdatePrompt(
            uiState = updateState,
            onLaunchUpdate = onLaunchUpdate,
            onCompleteUpdate = onCompleteUpdate,
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.statusBars),
        )
    }
}

@Preview
@Suppress("ktlint:standard:function-naming")
@Composable
fun AppAndroidPreview() {
    LogDateNavDisplay(
        appUiState = GlobalAppUiLoadedState(),
        onShowUnlockPrompt = { },
    )
}
