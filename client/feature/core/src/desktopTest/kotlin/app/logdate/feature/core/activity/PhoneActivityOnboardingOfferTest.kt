package app.logdate.feature.core.activity

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.logdate.ui.theme.LogDateTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class PhoneActivityOnboardingOfferTest {
    @Test
    fun decliningContinuesWithoutRequestingPhysicalActivity() =
        runDesktopComposeUiTest {
            var requests = 0
            var continued = 0
            setContent {
                LogDateTheme {
                    PhoneActivityOnboardingOffer(
                        link = PhoneActivityLink(PhoneActivityLinkState.Off, onLink = { requests++ }),
                        onContinue = { continued++ },
                    )
                }
            }
            onNodeWithText("Not now").performClick()

            assertEquals(0, requests)
            assertEquals(1, continued)
        }

    @Test
    fun denialStillLetsSomeoneContinueAndDoesNotClaimLinked() =
        runDesktopComposeUiTest {
            var continued = 0
            setContent {
                LogDateTheme {
                    PhoneActivityOnboardingOffer(
                        link = PhoneActivityLink(PhoneActivityLinkState.Denied),
                        onContinue = { continued++ },
                    )
                }
            }
            onNodeWithText("Open Settings").assertIsDisplayed()
            onNodeWithText("Phone activity linked").assertDoesNotExist()
            onNodeWithText("Not now").performClick()

            assertEquals(1, continued)
        }
}
