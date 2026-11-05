package app.logdate

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.window.ComposeUIViewController
import app.logdate.client.data.notes.StoredMediaReferenceMigrationLauncher
import app.logdate.client.media.storage.MediaFileResolver
import app.logdate.feature.core.AppViewModel
import app.logdate.feature.core.GlobalAppUiLoadedState
import app.logdate.feature.core.settings.account.recovery.AutomaticAccountRecovery
import app.logdate.navigation.LogDateNavDisplay
import coil3.SingletonImageLoader
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification

@Suppress("ktlint:standard:function-naming")
fun MainViewController() =
    ComposeUIViewController {
        startCrashReportingUserBridge()
        val mediaFiles = koinInject<MediaFileResolver>()
        SingletonImageLoader.setSafe { context -> buildLogDateImageLoader(context, mediaFiles) }
        val mediaReferenceMigration = koinInject<StoredMediaReferenceMigrationLauncher>()
        LaunchedEffect(mediaReferenceMigration) { mediaReferenceMigration.start() }
        val accountRecovery = koinInject<AutomaticAccountRecovery>()
        LaunchedEffect(accountRecovery) { accountRecovery.start() }
        val viewModel: AppViewModel = koinViewModel()
        DisposableEffect(viewModel) {
            val observer =
                NSNotificationCenter.defaultCenter.addObserverForName(
                    name = UIApplicationDidEnterBackgroundNotification,
                    `object` = null,
                    queue = NSOperationQueue.mainQueue,
                    usingBlock = { viewModel.onAppBackgrounded() },
                )
            val foregroundObserver =
                NSNotificationCenter.defaultCenter.addObserverForName(
                    name = UIApplicationDidBecomeActiveNotification,
                    `object` = null,
                    queue = NSOperationQueue.mainQueue,
                    usingBlock = { viewModel.onAppForegrounded() },
                )
            onDispose {
                NSNotificationCenter.defaultCenter.removeObserver(foregroundObserver)
                NSNotificationCenter.defaultCenter.removeObserver(observer)
            }
        }
        val uiState by viewModel.uiState.collectAsState()
        val loadedState = (uiState as? GlobalAppUiLoadedState) ?: GlobalAppUiLoadedState()
        LogDateNavDisplay(
            appUiState = loadedState,
            onShowUnlockPrompt = viewModel::showNativeUnlockPrompt,
            onAutomaticUnlockPrompt = viewModel::showAutomaticUnlockPrompt,
        )
    }
