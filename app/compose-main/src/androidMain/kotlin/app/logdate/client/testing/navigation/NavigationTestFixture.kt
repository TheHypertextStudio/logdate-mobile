package app.logdate.client.testing.navigation

import android.content.Intent
import androidx.navigation3.runtime.NavKey
import app.logdate.client.testing.launch.ActivityLaunchTestOverrides
import app.logdate.feature.core.settings.navigation.DevicesRoute
import app.logdate.feature.core.settings.navigation.SettingsRoute
import app.logdate.feature.core.settings.navigation.WatchSettingsRoute
import app.logdate.feature.core.settings.navigation.WatchTroubleshootingRoute
import app.logdate.feature.editor.navigation.EntryEditorRoute

/**
 * Optional test-only starting destination for instrumented flows that need to
 * exercise a specific app screen without replaying the entire manual path.
 */
enum class NavigationTestDestination {
    EntryEditor,
    SettingsOverview,
    Devices,
    WatchSettings,
    WatchTroubleshooting,
}

private const val NAVIGATION_TEST_DESTINATION_EXTRA = "app.logdate.client.testing.navigation.TEST_DESTINATION"

/** Maps the test-only destination extra to the matching navigation key. */
fun Intent.readNavigationTestDestination(): NavKey? =
    when (getStringExtra(NAVIGATION_TEST_DESTINATION_EXTRA) ?: ActivityLaunchTestOverrides.navigationDestination?.name) {
        NavigationTestDestination.EntryEditor.name -> EntryEditorRoute()
        NavigationTestDestination.SettingsOverview.name -> SettingsRoute()
        NavigationTestDestination.Devices.name -> DevicesRoute()
        NavigationTestDestination.WatchSettings.name -> WatchSettingsRoute
        NavigationTestDestination.WatchTroubleshooting.name -> WatchTroubleshootingRoute
        else -> null
    }
