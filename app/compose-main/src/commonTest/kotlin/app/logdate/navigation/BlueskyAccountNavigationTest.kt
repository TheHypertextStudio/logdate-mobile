package app.logdate.navigation

import androidx.navigation3.runtime.NavKey
import androidx.savedstate.serialization.decodeFromSavedState
import androidx.savedstate.serialization.encodeToSavedState
import app.logdate.feature.core.account.navigation.BlueskySignInRoute
import app.logdate.feature.core.account.navigation.CloudAccountSetupRoute
import app.logdate.feature.core.main.HomeRoute
import app.logdate.feature.onboarding.navigation.OnboardingStart
import app.logdate.feature.onboarding.navigation.WelcomeBack
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals

class BlueskyAccountNavigationTest {
    @Test
    fun `the real app saved state configuration restores only the opaque return destination`() {
        val stack: List<NavKey> = listOf(HomeRoute, BlueskySignInRoute("a".repeat(43)))
        val serializer = ListSerializer(PolymorphicSerializer(NavKey::class))
        val saved = encodeToSavedState(serializer, stack, appNavSavedStateConfiguration)
        assertEquals(stack, decodeFromSavedState(serializer, saved, appNavSavedStateConfiguration))
    }

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
