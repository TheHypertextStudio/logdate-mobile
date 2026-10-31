package app.logdate.feature.core.main

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.ui.graphics.vector.ImageVector
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.home_destination_journals
import logdate.client.feature.core.generated.resources.home_destination_library
import logdate.client.feature.core.generated.resources.home_destination_places
import logdate.client.feature.core.generated.resources.home_destination_rewind
import logdate.client.feature.core.generated.resources.home_destination_timeline
import org.jetbrains.compose.resources.StringResource

/**
 * Tabs in the home navigation shell.
 *
 * Matches Android's [HomeTab] enum: same order, icons, and labels so the desktop
 * and Android experiences stay consistent.
 */
enum class HomeRouteDestination(
    val label: StringResource,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    Timeline(
        label = Res.string.home_destination_timeline,
        selectedIcon = Icons.Filled.History,
        unselectedIcon = Icons.Outlined.History,
    ),
    LocationHistory(
        label = Res.string.home_destination_places,
        selectedIcon = Icons.Filled.LocationOn,
        unselectedIcon = Icons.Outlined.LocationOn,
    ),
    Journals(
        label = Res.string.home_destination_journals,
        selectedIcon = Icons.Filled.Book,
        unselectedIcon = Icons.Outlined.Book,
    ),
    Library(
        label = Res.string.home_destination_library,
        selectedIcon = Icons.Filled.PhotoLibrary,
        unselectedIcon = Icons.Outlined.PhotoLibrary,
    ),
    Rewind(
        label = Res.string.home_destination_rewind,
        selectedIcon = Icons.Filled.DateRange,
        unselectedIcon = Icons.Outlined.DateRange,
    ),
    ;

    companion object {
        /**
         * Tabs shown in the home navigation shell.
         *
         * The Library tab is gated behind the `library_enabled` user setting, so it only appears
         * when [isLibraryEnabled] is true. Places requires its independent beta opt-in.
         */
        fun visibleEntries(
            isLibraryEnabled: Boolean,
            isPlacesEnabled: Boolean = false,
        ): List<HomeRouteDestination> = entries.filter { (it != Library || isLibraryEnabled) && (it != LocationHistory || isPlacesEnabled) }

        val ALL = entries.toTypedArray()
    }
}
