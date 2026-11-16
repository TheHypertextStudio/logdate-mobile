@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.assistants

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.logdate.client.repository.assistants.AssistantCapability.READ_MEMORIES
import app.logdate.client.repository.assistants.AssistantCapability.START_CAPTURE
import app.logdate.ui.theme.LogDateTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class AndroidAssistantsContentTest {
    private val travel = Uuid.parse("00000000-0000-4000-8000-000000000001")
    private val journals = listOf(AssistantJournalOption(travel, "Travel"))

    @Test
    fun `everything is off until the person turns it on, and assistants never write`() =
        runDesktopComposeUiTest(width = 560, height = 2400) {
            setContent { Page(AssistantAccessSelection()) {} }

            onNodeWithText("Android assistants").assertIsDisplayed()
            onNodeWithText("Entries").assertIsOff()
            onNodeWithText("Start a voice note or entry").assertIsOff()
            onNodeWithText("Can’t write or change your entries").assertIsDisplayed()
            onNodeWithText("Can’t see anything while LogDate is locked").assertIsDisplayed()
        }

    @Test
    fun `turning on entries shares every journal until the person narrows it`() =
        runDesktopComposeUiTest(width = 560, height = 2400) {
            var access by mutableStateOf(AssistantAccessSelection())
            setContent { Page(access) { access = it } }

            onNodeWithText("Entries").performClick()
            onNodeWithText("Start a voice note or entry").performClick()
            runOnIdle {
                assertEquals(setOf(READ_MEMORIES, START_CAPTURE), access.capabilities)
                assertEquals(setOf(travel), access.journalIds)
            }
            onNodeWithText("Entries").assertIsOn()
        }

    @Test
    fun `settings summarizes what assistants may do`() =
        runDesktopComposeUiTest(width = 560, height = 800) {
            setContent {
                LogDateTheme(dynamicColor = false) {
                    ConnectedAssistantsContent(
                        assistants = emptyList(),
                        deviceName = "Alex’s Pixel",
                        anytimeAccess = null,
                        onOpenAssistant = {},
                        onCopyConnectorLink = {},
                        onAnytimeAccessChange = {},
                        onBack = {},
                        androidAssistants = setOf(READ_MEMORIES, START_CAPTURE),
                    )
                }
            }

            onNodeWithText("On this phone").assertIsDisplayed()
            onNodeWithText("Can see entries · Can start new entries").assertIsDisplayed()
        }

    @Composable
    private fun Page(
        access: AssistantAccessSelection,
        onAccessChange: (AssistantAccessSelection) -> Unit,
    ) {
        LogDateTheme(dynamicColor = false) {
            AndroidAssistantsContent(journals, access, onAccessChange, onBack = {})
        }
    }
}
