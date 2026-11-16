package app.logdate.navigation

import app.logdate.client.ui.navigation.LocationTimelineRoute
import app.logdate.feature.core.main.HomeRoute
import app.logdate.feature.core.settings.navigation.SettingsRoute
import app.logdate.feature.rewind.navigation.RewindDetailRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class PlacesNavigationTest {
    @Test
    fun disabledPlacesDeepLinkReturnsToHome() {
        assertNull(placesNavigationTarget(LocationTimelineRoute, enabled = null))
        assertEquals(HomeRoute, placesNavigationTarget(LocationTimelineRoute, enabled = false))
        assertEquals(LocationTimelineRoute, placesNavigationTarget(LocationTimelineRoute, enabled = true))
    }

    @Test
    fun disablingPlacesRemovesRestoredRoutesUnderSettings() {
        val settings = SettingsRoute()
        assertEquals(listOf(HomeRoute, settings), placesBackStack(listOf(HomeRoute, LocationTimelineRoute, settings), false))
        assertEquals(listOf(HomeRoute), placesBackStack(listOf(LocationTimelineRoute), false))
        assertEquals(listOf(HomeRoute, settings), placesBackStack(listOf(LocationTimelineRoute, settings), false))
    }

    @Test
    fun secondaryBrowseActionsExistOnlyAfterOptInLoads() {
        val settings = SettingsRoute()
        val stack = mutableListOf<androidx.navigation3.runtime.NavKey>(HomeRoute, settings)
        assertNull(placesLocationAction(null, stack))
        assertNull(placesLocationAction(false, stack))
        requireNotNull(placesLocationAction(true, stack)).invoke()
        assertEquals(listOf(HomeRoute, settings, LocationTimelineRoute), stack)
        assertEquals(listOf(HomeRoute, settings), placesBackStack(stack, false))
    }

    @Test
    fun rewindAndOtherDestinationsStayAvailable() {
        val rewind = RewindDetailRoute(Uuid.random())
        val stack = listOf(HomeRoute, rewind)
        assertEquals(rewind, placesNavigationTarget(rewind, false))
        assertEquals(stack, placesBackStack(stack, false))
        assertEquals(listOf(LocationTimelineRoute), placesBackStack(listOf(LocationTimelineRoute), true))
    }
}
