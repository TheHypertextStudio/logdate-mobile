package app.logdate.navigation

import androidx.navigation3.runtime.NavKey
import app.logdate.feature.core.account.navigation.BlueskySignInRoute
import app.logdate.feature.core.account.navigation.CloudAccountSetupRoute
import app.logdate.feature.core.main.HomeRoute
import app.logdate.feature.onboarding.navigation.OnboardingStart
import app.logdate.feature.onboarding.navigation.WelcomeBack
import kotlin.test.Test
import kotlin.test.assertEquals

class BlueskyAccountNavigationTest {
    @Test
    fun `completion removes account setup and repeated browser return entries`() {
        val stack = mutableListOf<NavKey>(HomeRoute, CloudAccountSetupRoute(true), BlueskySignInRoute(), BlueskySignInRoute("a".repeat(43)))
        completeBlueskyAccountSetup(stack, isOnboarded = true)
        assertEquals(listOf<NavKey>(HomeRoute), stack)
    }

    @Test
    fun `cold return during onboarding rejoins the existing welcome back acceptance flow`() {
        val stack = mutableListOf<NavKey>(OnboardingStart, BlueskySignInRoute("a".repeat(43)))
        completeBlueskyAccountSetup(stack, isOnboarded = false)
        assertEquals(listOf<NavKey>(OnboardingStart, WelcomeBack), stack)
    }
}
