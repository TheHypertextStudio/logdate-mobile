package app.logdate.feature.core.settings.navigation

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
                onNavigateToWatch = {},
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
    taggedEntry<WatchRemovalRoute>(metadata = settingsDetailMetadata(SettingsCategory.Watch, categories)) {
        app.logdate.feature.core.settings.ui.watch.v2
            .WatchRemovalScreen(onBack)
    }
    taggedEntry<WatchTilesRoute>(metadata = settingsDetailMetadata(SettingsCategory.Watch, categories)) {
        WatchTilesScreen(onBack)
    }
    taggedEntry<WatchCheckConnectionRoute>(metadata = settingsDetailMetadata(SettingsCategory.Watch, categories)) {
        WatchCheckConnectionScreen(onBack)
    }
    taggedEntry<WatchMicrophoneRoute>(metadata = settingsDetailMetadata(SettingsCategory.Watch, categories)) {
        val viewModel: WatchSettingsViewModel = koinViewModel()
        val failure by viewModel.requestFailure.collectAsState()
        WatchMicrophoneScreen(onBack, viewModel::openAppOnWatch, failure)
    }
}
