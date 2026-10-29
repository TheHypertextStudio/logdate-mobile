package app.logdate.navigation

import androidx.navigation3.runtime.NavKey
import app.logdate.feature.core.account.navigation.BlueskySignInRoute
import app.logdate.feature.core.main.HomeRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlueskyPendingDestinationTest {
    @Test
    fun `browser return replaces the live provider entry and duplicates do not create another owner`() {
        val target = BlueskySignInRoute("a".repeat(43))
        val stack = mutableListOf<NavKey>(HomeRoute, BlueskySignInRoute())
        applyPendingDestination(stack, target)
        assertEquals(listOf<NavKey>(HomeRoute, target), stack)
        applyPendingDestination(stack, target)
        assertEquals(listOf<NavKey>(HomeRoute, target), stack)
    }

    @Test
    fun `a cold browser return appends to the existing safe launch destination`() {
        val target = BlueskySignInRoute("a".repeat(43))
        val stack = mutableListOf<NavKey>(HomeRoute)
        applyPendingDestination(stack, target)
        assertEquals(listOf<NavKey>(HomeRoute, target), stack)
    }

    @Test
    fun `restored provider signup survives onboarding bootstrap`() {
        assertEquals(
            NavBootstrapAction.None,
            resolveNavBootstrapAction(listOf(BlueskySignInRoute()), isOnboarded = false, requiresUnlock = false),
        )
    }

    @Test
    fun `a valid return can resume unfinished onboarding but never bypasses the app lock`() {
        val route = BlueskySignInRoute("a".repeat(43))
        assertTrue(canOpenPendingDestination(route, isOnboarded = false, requiresUnlock = false))
        assertFalse(canOpenPendingDestination(route, isOnboarded = false, requiresUnlock = true))
        assertFalse(canOpenPendingDestination(BlueskySignInRoute("short"), isOnboarded = false, requiresUnlock = false))
        assertFalse(canOpenPendingDestination(BlueskySignInRoute(), isOnboarded = false, requiresUnlock = false))
    }

    @Test
    fun `ordinary destinations keep their existing onboarding and unlock gates`() {
        assertFalse(canOpenPendingDestination(HomeRoute, isOnboarded = false, requiresUnlock = false))
        assertFalse(canOpenPendingDestination(HomeRoute, isOnboarded = true, requiresUnlock = true))
        assertTrue(canOpenPendingDestination(HomeRoute, isOnboarded = true, requiresUnlock = false))
    }
}
