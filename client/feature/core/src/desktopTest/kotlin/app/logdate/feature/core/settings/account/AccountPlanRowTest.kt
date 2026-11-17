package app.logdate.feature.core.settings.account

import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.logdate.ui.theme.LogDateTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/** Where Account settings offers the LogDate Cloud plan, and where it does not. */
@OptIn(ExperimentalTestApi::class)
class AccountPlanRowTest {
    private fun signedIn(isLogDateCloud: Boolean) =
        AccountUiState.SignedIn(
            header = AccountHeader(displayName = "Alex Rivera", username = "alex"),
            signIn = SignInSummary.Known(passkeyCount = 1, linkedProviders = emptyList()),
            hasRecoveryPhrase = true,
            email = null,
            server =
                ServerRow(
                    name = if (isLogDateCloud) "LogDate Cloud" else "Home server",
                    host = if (isLogDateCloud) "cloud.logdate.app" else "journal.example.org",
                    isLogDateCloud = isLogDateCloud,
                ),
            isSigningOut = false,
        )

    private fun accountScreen(
        showPlan: Boolean,
        isLogDateCloud: Boolean = true,
        test: DesktopComposeUiTest.(planOpened: () -> Int) -> Unit,
    ) = runDesktopComposeUiTest(width = 411, height = 1100) {
        var opened = 0
        setContent {
            LogDateTheme(darkTheme = false, dynamicColor = false) {
                AccountContent(
                    state = signedIn(isLogDateCloud),
                    destinations = AccountDestinations({}, {}, {}, {}, {}, {}, {}, {}, onPlan = { opened++ }),
                    onOpenEmailVerification = {},
                    onSignOut = {},
                    showPlan = showPlan,
                )
            }
        }
        test { opened }
    }

    @Test
    fun thePlanIsOfferedUnderTheProviderAndOpensThePlanScreen() =
        accountScreen(showPlan = true) { planOpened ->
            onNodeWithText("Plan and billing").performScrollTo().assertIsDisplayed()

            onNodeWithText("Storage").performClick()

            assertEquals(1, planOpened())
        }

    @Test
    fun thePlanStaysHiddenUntilItIsSwitchedOn() =
        accountScreen(showPlan = false) {
            onNodeWithText("Plan and billing").assertDoesNotExist()
        }

    @Test
    fun anotherProviderHasNoLogDateCloudPlan() =
        accountScreen(showPlan = true, isLogDateCloud = false) {
            onNodeWithText("journal.example.org", substring = true).assertExists()
            onNodeWithText("Plan and billing").assertDoesNotExist()
        }
}
