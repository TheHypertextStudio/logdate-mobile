@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.assistants

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import app.logdate.client.repository.assistants.AssistantCapability
import app.logdate.client.repository.assistants.AssistantCapability.READ_MEMORIES
import app.logdate.client.repository.assistants.AssistantCapability.READ_PEOPLE
import app.logdate.client.repository.assistants.AssistantCapability.READ_PLACES
import app.logdate.client.repository.assistants.AssistantCapability.START_CAPTURE
import app.logdate.ui.theme.LogDateTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class AssistantConnectContentTest {
    private val travel = Uuid.parse("00000000-0000-4000-8000-000000000001")
    private val notes = Uuid.parse("00000000-0000-4000-8000-000000000002")
    private val journals = listOf(AssistantJournalOption(travel, "Travel"), AssistantJournalOption(notes, "Notes"))
    private val everything = setOf(READ_MEMORIES, READ_PLACES, READ_PEOPLE, START_CAPTURE)

    @Test
    fun `allow waits until something is on, then grants exactly that`() =
        runDesktopComposeUiTest(width = 560, height = 2400) {
            var selection by mutableStateOf(AssistantAccessSelection())
            val granted = mutableListOf<AssistantAccessGrant>()
            setContent { Sheet(selection, onSelectionChange = { selection = it }, onAllow = granted::add) }

            onNodeWithText("Claude wants to use LogDate").assertIsDisplayed()
            onNodeWithText("Turn on something it can see or do").assertIsDisplayed()
            onNodeWithText("Allow").assertIsNotEnabled()
            onNodeWithText("Places").assertIsOff().performClick()
            onNodeWithText("Places").assertIsOn()
            onNodeWithText("Allow").assertIsEnabled().performClick()
            runOnIdle {
                assertEquals(setOf(READ_PLACES), granted.single().capabilities)
                assertTrue(
                    granted
                        .single()
                        .contentScope.journalIds
                        .isEmpty(),
                )
            }
        }

    @Test
    fun `entries open a journal picker that narrows what is shared`() =
        runDesktopComposeUiTest(width = 560, height = 2400) {
            var selection by mutableStateOf(AssistantAccessSelection())
            val granted = mutableListOf<AssistantAccessGrant>()
            setContent { Sheet(selection, onSelectionChange = { selection = it }, onAllow = granted::add) }

            onNodeWithText("Entries").performClick()
            onNodeWithText("Notes").assertIsOn().performClick()
            onNodeWithText("Entries outside journals").performClick()
            onNodeWithText("Allow").performClick()
            runOnIdle {
                assertEquals(setOf(READ_MEMORIES), granted.single().capabilities)
                assertEquals(setOf(travel), granted.single().contentScope.journalIds)
                assertEquals(false, granted.single().contentScope.includeUnfiled)
            }
        }

    @Test
    fun `only what the assistant asked for is offered`() =
        runDesktopComposeUiTest(width = 560, height = 2400) {
            setContent { Sheet(AssistantAccessSelection(), requested = setOf(READ_PLACES)) }
            onNodeWithText("Places").assertIsDisplayed()
            listOf("Entries", "People", "Start a voice note or entry", "Do").forEach { onNodeWithText(it).assertDoesNotExist() }
        }

    @Test
    fun `the sheet says it cannot write entries and where its activity appears`() =
        runDesktopComposeUiTest(width = 560, height = 2400) {
            setContent { Sheet(AssistantAccessSelection()) }
            onNodeWithText("Can’t write or change your entries").assertIsDisplayed()
            onNodeWithText("Everything it does shows up in Settings").assertIsDisplayed()
            onNodeWithText("May keep what it sees after you disconnect").assertIsDisplayed()
            onNodeWithText("Works while Alex’s Mac is awake and unlocked").assertIsDisplayed()
            onNodeWithText("You record or write it yourself").assertIsDisplayed()
        }

    @Test
    fun `availability follows the anytime setting`() =
        runDesktopComposeUiTest(width = 560, height = 2400) {
            setContent { Sheet(AssistantAccessSelection(), anytimeAccess = true) }
            onNodeWithText("Works anytime for backed-up journals").assertIsDisplayed()
        }

    @Test
    fun `a failed allow says why while allowing locks the choices`() {
        runDesktopComposeUiTest(width = 560, height = 2400) {
            val failure = "LogDate couldn’t reach your account. Try again."
            setContent { Sheet(AssistantAccessSelection(setOf(READ_PLACES)), failure = failure) }
            onNodeWithText("LogDate couldn’t reach your account. Try again.").assertIsDisplayed()
            onNodeWithText("Allow").assertIsEnabled()
        }
        runDesktopComposeUiTest(width = 560, height = 2400) {
            var denies = 0
            setContent { Sheet(AssistantAccessSelection(setOf(READ_PLACES)), allowing = true, onDeny = { denies++ }) }
            onNodeWithText("Allowing…").assertIsNotEnabled()
            onNodeWithText("Places").assertIsNotEnabled()
            onNodeWithText("Don’t allow").performClick()
            runOnIdle { assertEquals(1, denies) }
        }
    }

    @Test
    fun `large right-to-left text keeps every choice reachable and the actions visible`() =
        runDesktopComposeUiTest(width = 360, height = 640) {
            setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, 2f),
                    LocalLayoutDirection provides LayoutDirection.Rtl,
                ) {
                    Sheet(AssistantAccessSelection(setOf(READ_PLACES)), dark = true)
                }
            }
            onNodeWithText("Allow").assertIsDisplayed()
            onNodeWithText("Don’t allow").assertIsDisplayed()
            listOf("People", "Start a voice note or entry", "Can’t write or change your entries").forEach { label ->
                onNode(hasScrollToIndexAction()).performScrollToNode(hasText(label))
                onNodeWithText(label).assertIsDisplayed()
            }
        }

    @Composable
    private fun Sheet(
        selection: AssistantAccessSelection,
        requested: Set<AssistantCapability> = everything,
        anytimeAccess: Boolean = false,
        allowing: Boolean = false,
        dark: Boolean = false,
        failure: String? = null,
        onSelectionChange: (AssistantAccessSelection) -> Unit = {},
        onAllow: (AssistantAccessGrant) -> Unit = {},
        onDeny: () -> Unit = {},
    ) {
        LogDateTheme(dynamicColor = false, darkTheme = dark) {
            AssistantConnectContent(
                request = AssistantConnectRequest("Claude", "Alex’s Mac", requested),
                journals = journals,
                selection = selection,
                anytimeAccess = anytimeAccess,
                allowing = allowing,
                onSelectionChange = onSelectionChange,
                onAllow = onAllow,
                onDeny = onDeny,
                failure = failure,
            )
        }
    }
}
