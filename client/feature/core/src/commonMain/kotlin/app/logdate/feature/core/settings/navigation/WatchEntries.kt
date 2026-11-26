package app.logdate.feature.core.settings.navigation

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import app.logdate.feature.core.settings.ui.SettingsCategoryNavigationScreen
import app.logdate.feature.core.settings.ui.watch.WatchNotificationSettingsScreen
import app.logdate.feature.core.settings.ui.watch.WatchSettingsScreen
import app.logdate.feature.core.settings.ui.watch.WatchSettingsViewModel
import app.logdate.feature.core.settings.ui.watch.WatchTroubleshootingScreen
import app.logdate.feature.core.settings.ui.watch.v2.WatchCheckConnectionScreen
import app.logdate.feature.core.settings.ui.watch.v2.WatchLibraryScreen
import app.logdate.feature.core.settings.ui.watch.v2.WatchMicrophoneScreen
import app.logdate.feature.core.settings.ui.watch.v2.WatchTilesScreen
import app.logdate.ui.navigation.ViewModelsPerVisit
import app.logdate.ui.navigation.taggedEntry
import org.koin.compose.viewmodel.koinViewModel

/**
 * Registers the Watch Settings overview and Troubleshooting entries. Only call this on
 * platforms where the watch DI module ([WatchSettingsViewModel] et al) is included — on iOS
 * and desktop the underlying `WatchSettingsViewModel` Koin binding is absent and resolving
 * it would crash.
 */
fun EntryProviderScope<NavKey>.watchEntries(
    onBack: () -> Unit,
    onNavigateToNotifications: () -> Unit,
    onNavigateToTroubleshooting: () -> Unit,
    onNavigateToCategory: (NavKey) -> Unit = {},
    onNavigateToProfile: () -> Unit = {},
    onNavigateToDetail: (NavKey) -> Unit = {},
) {
    val categories =
        SettingsActivityCategories { section ->
            SettingsCategoryNavigationScreen(
                selectedSection = section,
                onNavigateTo = onNavigateToCategory,
                onBack = onBack,
                onNavigateToProfile = onNavigateToProfile,
                onNavigateToWatch = { onNavigateToCategory(WatchSettingsRoute) },
            )
        }
    taggedEntry<WatchSettingsRoute>(metadata = settingsDetailMetadata(SettingsCategory.Watch, categories)) {
        WatchSettingsScreen(
            onBack = onBack,
            onNavigateToNotifications = onNavigateToNotifications,
            onNavigateToTroubleshooting = onNavigateToTroubleshooting,
            onNavigateToReminders = { onNavigateToDetail(RecommendationSettingsRoute) },
            onNavigateToTiles = { onNavigateToDetail(WatchTilesRoute) },
            onNavigateToMicrophone = { onNavigateToDetail(WatchMicrophoneRoute) },
            onNavigateToLibrary = { onNavigateToDetail(WatchLibraryRoute) },
            onNavigateToConnectionCheck = { onNavigateToDetail(WatchCheckConnectionRoute) },
            onNavigateToRemove = { onNavigateToDetail(WatchRemovalRoute) },
        )
    }
    watchCompanionDetailEntries(categories = categories, onBack = onBack)
}

private fun EntryProviderScope<NavKey>.watchCompanionDetailEntries(
    categories: SettingsActivityCategories,
    onBack: () -> Unit,
) {
    taggedEntry<WatchLibraryRoute>(metadata = settingsDetailMetadata(SettingsCategory.Watch, categories)) {
        WatchLibraryScreen(onBack)
    }
    taggedEntry<WatchNotificationSettingsRoute>(metadata = settingsDetailMetadata(SettingsCategory.Watch, categories)) {
        val viewModel: WatchSettingsViewModel = koinViewModel()
        WatchNotificationSettingsScreen(
            onBack = onBack,
            viewModel = viewModel,
        )
    }
    taggedEntry<WatchTroubleshootingRoute>(metadata = settingsDetailMetadata(SettingsCategory.Watch, categories)) {
        val viewModel: WatchSettingsViewModel = koinViewModel()
        WatchTroubleshootingScreen(
            onBack = onBack,
            viewModel = viewModel,
        )
    }
    taggedEntry<WatchRemovalRoute>(metadata = settingsDetailMetadata(SettingsCategory.Watch, categories) + ViewModelsPerVisit) {
        app.logdate.feature.core.settings.ui.watch.v2
            .WatchRemovalScreen(onBack)
    }
    taggedEntry<WatchTilesRoute>(metadata = settingsDetailMetadata(SettingsCategory.Watch, categories)) {
        WatchTilesScreen(onBack)
    }
    taggedEntry<WatchCheckConnectionRoute>(metadata = settingsDetailMetadata(SettingsCategory.Watch, categories) + ViewModelsPerVisit) {
        WatchCheckConnectionScreen(onBack)
    }
    taggedEntry<WatchMicrophoneRoute>(metadata = settingsDetailMetadata(SettingsCategory.Watch, categories)) {
        val viewModel: WatchSettingsViewModel = koinViewModel()
        val failure by viewModel.requestFailure.collectAsStateWithLifecycle()
        val sendingRequest by viewModel.sendingRequest.collectAsStateWithLifecycle()
        DisposableEffect(viewModel) {
            viewModel.clearRequestFailure()
            onDispose { viewModel.clearRequestFailure() }
        }
        WatchMicrophoneScreen(
            onBack = onBack,
            onOpenWatch = viewModel::openAppOnWatch,
            requestFailure = failure,
            sendingRequest = sendingRequest,
        )
    }
}
