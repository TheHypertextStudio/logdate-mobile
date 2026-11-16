package app.logdate.navigation

import app.logdate.feature.core.main.HomeRoute
import app.logdate.feature.core.navigation.BaseRoute
import app.logdate.feature.core.settings.navigation.DataSettingsRoute
import app.logdate.feature.core.settings.navigation.SettingsRoute
import app.logdate.feature.editor.navigation.EntryEditorRoute
import app.logdate.feature.onboarding.navigation.FirstEntryImportOffer
import app.logdate.feature.onboarding.navigation.FirstEntryMemorySelection
import app.logdate.feature.onboarding.navigation.OnboardingComplete
import app.logdate.feature.onboarding.navigation.OnboardingStart
import app.logdate.feature.onboarding.navigation.PersonalIntro
import app.logdate.feature.onboarding.navigation.WelcomeBack
import kotlin.test.Test
import kotlin.test.assertEquals

class NavBootstrapActionTest {
    @Test
    fun `cold start replaces the bootstrap placeholder with home`() {
        assertEquals(
            NavBootstrapAction.ResetTo(HomeRoute),
            resolveNavBootstrapAction(
                backStack = listOf(BaseRoute),
                isOnboarded = true,
                requiresUnlock = false,
            ),
        )
    }

    @Test
    fun `cold start places recovery options above home without an intermediate home route`() {
        assertEquals(
            NavBootstrapAction.ResetTo(HomeRoute, pendingKey = DataSettingsRoute),
            resolveNavBootstrapAction(
                backStack = listOf(BaseRoute),
                isOnboarded = true,
                requiresUnlock = false,
                pendingNavKey = DataSettingsRoute,
            ),
        )
    }

    @Test
    fun `a locked cold start keeps the recovery route pending until unlock`() {
        assertEquals(
            NavBootstrapAction.None,
            resolveNavBootstrapAction(
                backStack = listOf(BaseRoute),
                isOnboarded = true,
                requiresUnlock = true,
                pendingNavKey = DataSettingsRoute,
            ),
        )
    }

    @Test
    fun `rotating while writing an entry keeps the editor on the back stack`() {
        assertEquals(
            NavBootstrapAction.None,
            resolveNavBootstrapAction(
                backStack = listOf(HomeRoute, EntryEditorRoute(draftId = "draft-1")),
                isOnboarded = true,
                requiresUnlock = false,
            ),
        )
    }

    @Test
    fun `rotating deep in settings keeps the restored back stack`() {
        assertEquals(
            NavBootstrapAction.None,
            resolveNavBootstrapAction(
                backStack = listOf(HomeRoute, SettingsRoute(), SettingsRoute(settingId = "privacy")),
                isOnboarded = true,
                requiresUnlock = false,
            ),
        )
    }

    @Test
    fun `already on home needs no bootstrap`() {
        assertEquals(
            NavBootstrapAction.None,
            resolveNavBootstrapAction(
                backStack = listOf(HomeRoute),
                isOnboarded = true,
                requiresUnlock = false,
            ),
        )
    }

    @Test
    fun `finishing onboarding keeps the post entry offer available`() {
        assertEquals(
            NavBootstrapAction.None,
            resolveNavBootstrapAction(
                backStack = listOf(OnboardingStart, PersonalIntro, OnboardingComplete),
                isOnboarded = true,
                requiresUnlock = false,
            ),
        )
    }

    @Test
    fun `post entry import survives onboarding flag update and recreation`() {
        listOf(FirstEntryImportOffer, FirstEntryMemorySelection).forEach { route ->
            assertEquals(
                NavBootstrapAction.None,
                resolveNavBootstrapAction(
                    backStack = listOf(OnboardingStart, OnboardingComplete, route),
                    isOnboarded = true,
                    requiresUnlock = false,
                ),
            )
        }
    }

    @Test
    fun `welcome back can finish after onboarding flag is saved`() {
        assertEquals(
            NavBootstrapAction.None,
            resolveNavBootstrapAction(
                backStack = listOf(OnboardingStart, WelcomeBack),
                isOnboarded = true,
                requiresUnlock = false,
            ),
        )
    }

    @Test
    fun `cold start without onboarding starts the onboarding flow`() {
        assertEquals(
            NavBootstrapAction.ResetTo(OnboardingStart),
            resolveNavBootstrapAction(
                backStack = listOf(BaseRoute),
                isOnboarded = false,
                requiresUnlock = false,
            ),
        )
    }

    @Test
    fun `rotating mid-onboarding keeps the user on the current step`() {
        assertEquals(
            NavBootstrapAction.None,
            resolveNavBootstrapAction(
                backStack = listOf(OnboardingStart, PersonalIntro),
                isOnboarded = false,
                requiresUnlock = false,
            ),
        )
    }

    @Test
    fun `rotating during first entry keeps the live editor`() {
        assertEquals(
            NavBootstrapAction.None,
            resolveNavBootstrapAction(
                backStack = listOf(OnboardingStart, EntryEditorRoute(firstEntry = true)),
                isOnboarded = false,
                requiresUnlock = false,
            ),
        )
    }

    @Test
    fun `a locked app keeps the back stack it will return to after unlocking`() {
        assertEquals(
            NavBootstrapAction.None,
            resolveNavBootstrapAction(
                backStack = listOf(HomeRoute, EntryEditorRoute(draftId = "draft-1")),
                isOnboarded = true,
                requiresUnlock = true,
            ),
        )
    }

    @Test
    fun `an empty back stack falls back to home`() {
        assertEquals(
            NavBootstrapAction.ResetTo(HomeRoute),
            resolveNavBootstrapAction(
                backStack = emptyList(),
                isOnboarded = true,
                requiresUnlock = false,
            ),
        )
    }
}
