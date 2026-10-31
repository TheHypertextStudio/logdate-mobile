@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.assistants

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.logdate.client.repository.assistants.AssistantCapability.READ_MEMORIES
import app.logdate.client.repository.assistants.AssistantCapability.READ_PEOPLE
import app.logdate.client.repository.assistants.AssistantCapability.READ_PLACES
import app.logdate.client.repository.assistants.AssistantCapability.START_CAPTURE
import app.logdate.ui.theme.LogDateTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class ConnectedAssistantsContentTest {
    private val travel = Uuid.parse("00000000-0000-4000-8000-000000000001")
    private val journals = listOf(AssistantJournalOption(travel, "Travel"))
    private val claude = ConnectedAssistantSummary("claude-1", "Claude", setOf(READ_MEMORIES, READ_PLACES), "October 30", "today")
    private val explanation = "A LogDate server gets a key to the backed-up journals you share"

    @Test
    fun `with nothing connected the page invites a connection`() =
        runDesktopComposeUiTest(width = 560, height = 2400) {
            var copies = 0
            setContent { Page(emptyList(), anytimeAccess = null, onCopy = { copies++ }) }

            onNodeWithText("Ask about your life").assertIsDisplayed()
            onNodeWithText("Copy connector link").performClick()
            runOnIdle { assertEquals(1, copies) }
            onNodeWithText("Connected").assertDoesNotExist()
        }

    @Test
    fun `each assistant opens its own page and says when it was last used`() =
        runDesktopComposeUiTest(width = 560, height = 2400) {
            val opened = mutableListOf<String>()
            setContent { Page(listOf(claude, claude.copy(id = "other", name = "Other", lastUsedLabel = null)), null, onOpen = opened::add) }

            onNodeWithText("Used today").assertIsDisplayed()
            onNodeWithText("Not used yet").assertIsDisplayed()
            onNodeWithText("Claude").performClick()
            runOnIdle { assertEquals(listOf("claude-1"), opened) }
        }

    @Test
    fun `without the gateway there is no anytime switch, only how assistants reach LogDate`() =
        runDesktopComposeUiTest(width = 560, height = 2400) {
            setContent { Page(listOf(claude), anytimeAccess = null) }
            onNodeWithText("Answer while Alex’s Mac is off").assertDoesNotExist()
            onNodeWithText("Assistants reach LogDate through Alex’s Mac while it’s awake and unlocked").assertIsDisplayed()
        }

    @Test
    fun `anytime access starts off and explains itself on request`() =
        runDesktopComposeUiTest(width = 560, height = 2400) {
            var anytime by mutableStateOf(false)
            setContent { Page(listOf(claude), anytimeAccess = anytime, onAnytime = { anytime = it }) }

            onNodeWithText("Answer while Alex’s Mac is off").assertIsOff()
            onNodeWithText("A protected LogDate server answers instead").assertIsDisplayed()
            onNodeWithText(explanation, substring = true).assertDoesNotExist()
            onNodeWithText("How it works").performClick()
            onNodeWithText(explanation, substring = true).assertIsDisplayed()
            onNodeWithText("Answer while Alex’s Mac is off").performClick()
            runOnIdle { assertEquals(true, anytime) }
            onNodeWithText("Answer while Alex’s Mac is off").assertIsOn()
        }

    @Test
    fun `a pending or failed anytime change is shown`() {
        runDesktopComposeUiTest(width = 560, height = 2400) {
            setContent { Page(listOf(claude), anytimeAccess = false, changing = true) }
            onNodeWithText("Updating…").assertIsDisplayed()
            onNodeWithText("Answer while Alex’s Mac is off").assertIsNotEnabled()
        }
        runDesktopComposeUiTest(width = 560, height = 2400) {
            val failure = "Your Mac couldn’t confirm the server’s software. Try again."
            setContent { Page(listOf(claude), anytimeAccess = false, failure = failure) }
            onNodeWithText("Your Mac couldn’t confirm the server’s software. Try again.").assertIsDisplayed()
            onNodeWithText("Answer while Alex’s Mac is off").assertIsEnabled()
        }
    }

    @Test
    fun `an assistant's page shows its activity, lets access change and disconnects`() =
        runDesktopComposeUiTest(width = 560, height = 2400) {
            var access by mutableStateOf(AssistantAccessSelection(setOf(READ_MEMORIES, READ_PLACES), setOf(travel), includeUnfiled = false))
            var disconnects = 0
            setContent {
                LogDateTheme(dynamicColor = false) {
                    ConnectedAssistantContent(
                        assistant = claude,
                        offered = setOf(READ_MEMORIES, READ_PLACES, READ_PEOPLE, START_CAPTURE),
                        journals = journals,
                        access = access,
                        activity = listOf(AssistantActivity(READ_PLACES, "Looked at October places", "Yesterday")),
                        disconnecting = false,
                        onAccessChange = { access = it },
                        onDisconnect = { disconnects++ },
                        onBack = {},
                    )
                }
            }

            onNodeWithText("Connected October 30 · Used today").assertIsDisplayed()
            onNodeWithText("Looked at October places").assertIsDisplayed()
            onNodeWithText("Places").assertIsOn().performClick()
            runOnIdle { assertFalse(READ_PLACES in access.capabilities) }
            onNodeWithText("People").assertIsOff()
            onNodeWithText("Claude may keep what it already saw.").assertIsDisplayed()
            onNodeWithText("Disconnect").performClick()
            runOnIdle { assertEquals(1, disconnects) }
        }

    @Test
    fun `a new assistant's page explains that activity appears here`() =
        runDesktopComposeUiTest(width = 560, height = 2400) {
            setContent {
                LogDateTheme(dynamicColor = false) {
                    ConnectedAssistantContent(
                        claude,
                        setOf(READ_PLACES),
                        journals,
                        AssistantAccessSelection(setOf(READ_PLACES)),
                        emptyList(),
                        disconnecting = true,
                        onAccessChange = {},
                        onDisconnect = {},
                        onBack = {},
                    )
                }
            }
            onNodeWithText("Nothing yet. Requests show up here as they happen.").assertIsDisplayed()
            onNodeWithText("Disconnecting…").assertIsNotEnabled()
        }

    @Composable
    private fun Page(
        assistants: List<ConnectedAssistantSummary>,
        anytimeAccess: Boolean?,
        onOpen: (String) -> Unit = {},
        onCopy: () -> Unit = {},
        onAnytime: (Boolean) -> Unit = {},
        changing: Boolean = false,
        failure: String? = null,
    ) {
        LogDateTheme(dynamicColor = false) {
            ConnectedAssistantsContent(
                assistants = assistants,
                deviceName = "Alex’s Mac",
                anytimeAccess = anytimeAccess,
                onOpenAssistant = onOpen,
                onCopyConnectorLink = onCopy,
                onAnytimeAccessChange = onAnytime,
                onBack = {},
                anytimeChanging = changing,
                anytimeFailure = failure,
            )
        }
    }
}
