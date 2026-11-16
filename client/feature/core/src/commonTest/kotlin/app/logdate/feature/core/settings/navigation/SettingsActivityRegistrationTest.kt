package app.logdate.feature.core.settings.navigation

import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import app.logdate.ui.navigation.ActivityPresentation
import app.logdate.ui.navigation.activityPresentation
import app.logdate.ui.navigation.hasViewModelsPerVisit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SettingsActivityRegistrationTest {
    @Test
    fun dataSettingsRoutesCloudRecoveryActionsToTheSettingsNavigator() {
        var accountCreationVisits = 0
        var signInVisits = 0
        val callbacks =
            dataSettingsNavigationCallbacks(
                onNavigateToCloudAccountCreation = { accountCreationVisits++ },
                onNavigateToSignIn = { signInVisits++ },
            )

        callbacks.onNavigateToCloudAccountCreation()
        callbacks.onNavigateToSignIn()

        assertEquals(1, accountCreationVisits)
        assertEquals(1, signInVisits)
    }

    @Test
    fun nestedAccountPagesKeepTheirCategoryAndExistingStateOwner() {
        val entries =
            entryProvider<NavKey> {
                settingsEntries(
                    onBack = {},
                    onResetApp = {},
                    onNavigateToProfile = {},
                    onNavigateToCloudAccountCreation = {},
                    onNavigateToSignIn = {},
                    onNavigateToEvent = {},
                    onNavigateTo = {},
                )
            }
        assertEquals(ActivityPresentation.SettingsRoot, entries(SettingsRoute()).activityPresentation())
        for (route in listOf(AccountSettingsRoute, HostingRoute, MoveServerRoute, RecoveryPhraseRoute)) {
            val entry = entries(route)
            assertEquals(ActivityPresentation.SettingsDetail("account"), entry.activityPresentation())
            assertTrue(entry.hasViewModelsPerVisit())
            assertNotNull(entry.metadata[SETTINGS_CATEGORIES_METADATA_KEY])
        }
        assertEquals(ActivityPresentation.SettingsDetail("timeline"), entries(DayBoundarySettingsRoute).activityPresentation())
        assertEquals(ActivityPresentation.SettingsDetail("reset"), entries(ClearDataSettingsRoute).activityPresentation())
        assertEquals(ActivityPresentation.SettingsDetail("about"), entries(LabsSettingsRoute).activityPresentation())
    }
}
