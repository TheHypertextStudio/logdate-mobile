package app.logdate.client.e2e

import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.logdate.client.text.UiText
import app.logdate.feature.location.timeline.ui.history.HistoryEditAction
import app.logdate.feature.location.timeline.ui.history.HistoryItemKind
import app.logdate.feature.location.timeline.ui.history.HistoryItemUi
import app.logdate.feature.location.timeline.ui.history.HistoryMemoryKind
import app.logdate.feature.location.timeline.ui.history.HistoryMemoryUi
import app.logdate.feature.location.timeline.ui.history.HistoryPlaceUi
import app.logdate.feature.location.timeline.ui.history.HistoryTab
import app.logdate.feature.location.timeline.ui.history.HumanLocationHistoryActions
import app.logdate.feature.location.timeline.ui.history.HumanLocationHistoryContent
import app.logdate.feature.location.timeline.ui.history.HumanLocationHistoryState
import app.logdate.ui.theme.LogDateTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

/** Run with smokeDevicesGroupDebugAndroidTest; physical devices are never a target. */
@RunWith(AndroidJUnit4::class)
class HumanLocationHistoryE2ETest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var state: MutableState<HumanLocationHistoryState>
    private val edits = mutableListOf<Pair<String?, HistoryEditAction>>()
    private val openedMemories = mutableListOf<Uuid>()
    private val morningMemoryId = Uuid.parse("00000000-0000-0000-0000-000000000101")
    private val returnMemoryId = Uuid.parse("00000000-0000-0000-0000-000000000102")

    @Test
    fun `selection stays in the day until details are explicitly opened`() {
        show(singleVisit())
        composeRule.onNodeWithText("Change place").assertDoesNotExist()
        composeRule.onNodeWithText("9:00–9:30 AM", useUnmergedTree = true).performClick()
        composeRule.runOnIdle {
            assertEquals("cafe-morning", state.value.selectedItemId)
            assertTrue("Tapping the time must not open a memory", openedMemories.isEmpty())
        }
        composeRule.onNodeWithText("Change place").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("See details").performClick()
        composeRule.onNodeWithText("Change place").assertIsDisplayed()
    }

    @Test
    fun `the nested memory preview opens the memory without selecting the visit`() {
        show(singleVisit())
        composeRule.onNodeWithText("A quiet morning").performClick()
        composeRule.runOnIdle {
            assertEquals(listOf(morningMemoryId), openedMemories)
            assertEquals(null, state.value.selectedItemId)
            assertFalse(state.value.detailVisible)
        }
    }

    @Test
    fun `dismissed details stay dismissed after new history arrives`() {
        show(singleVisit())
        composeRule.onNodeWithContentDescription("See details").performClick()
        composeRule.onNodeWithContentDescription("Close").performScrollTo().performClick()
        composeRule.runOnIdle {
            state.value = state.value.copy(recoveryMessage = UiText.Raw("Location is paused. Your saved day is still here."))
        }
        composeRule.onNodeWithText("Change place").assertDoesNotExist()
        composeRule.onNodeWithText("Location is paused. Your saved day is still here.").assertIsDisplayed()
    }

    @Test
    fun `repeated visits remain separately selectable`() {
        show(day())
        composeRule.onNodeWithText("9:00–9:30 AM", useUnmergedTree = true).performClick()
        composeRule.runOnIdle {
            assertEquals("cafe-morning", state.value.selectedItemId)
            assertTrue("Tapping the time must not open a memory", openedMemories.isEmpty())
        }
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("10:00–10:30 AM"))
        composeRule.onNodeWithText("10:00–10:30 AM", useUnmergedTree = true).performClick()
        composeRule.runOnIdle { assertEquals("cafe-return", state.value.selectedItemId) }
        composeRule.onNodeWithText("Change place").assertDoesNotExist()
    }

    @Test
    fun `replay steps through visits journeys and gaps without opening details`() {
        show(day())
        composeRule.onNodeWithText("Replay your day").performClick()
        nextMoment("cafe-morning")
        nextMoment("walk")
        nextMoment("gap")
        composeRule.onNodeWithContentDescription("Previous moment").performClick()
        composeRule.runOnIdle { assertEquals("walk", state.value.selectedItemId) }
        composeRule.onNodeWithText("Change activity").assertDoesNotExist()
    }

    @Test
    fun `replay advances the visible selected moment and memory together`() {
        show(
            day().let { initial ->
                initial.copy(
                    items =
                        initial.items.map { item ->
                            if (item.id == "cafe-return") {
                                item.copy(
                                    title = UiText.Raw("Mothership Coffee again"),
                                    memories =
                                        listOf(
                                            HistoryMemoryUi(returnMemoryId, UiText.Raw("Back for a second cup"), HistoryMemoryKind.Text),
                                        ),
                                )
                            } else {
                                item
                            }
                        },
                )
            },
        )
        composeRule.onNodeWithText("Replay your day").performClick()
        nextMoment("cafe-morning")
        val inReplay = hasAnyAncestor(hasTestTag("history-replay-preview"))
        composeRule.onNode(hasText("Mothership Coffee") and inReplay).assertIsDisplayed()
        composeRule.onNode(hasText("A quiet morning") and inReplay).assertIsDisplayed()
        nextMoment("walk")
        nextMoment("gap")
        nextMoment("cafe-return")
        composeRule.onNode(hasText("Mothership Coffee again") and inReplay).assertIsDisplayed()
        composeRule.onNode(hasText("Back for a second cup") and inReplay).assertIsDisplayed()
        composeRule.onNode(hasText("A quiet morning") and inReplay).assertDoesNotExist()
        composeRule.runOnIdle { assertFalse(state.value.detailVisible) }
    }

    @Test
    fun `tapping a journey opens its details directly`() {
        show(day())
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Walked · 15 min"))
        composeRule.onNodeWithText("Walked · 15 min").performClick()
        composeRule.runOnIdle { assertEquals("walk", state.value.selectedItemId) }
        composeRule.onNodeWithText("Change activity").assertIsDisplayed()
    }

    @Test
    fun `collapsing replay also pauses playback`() {
        show(day())
        composeRule.onNodeWithText("Replay your day").performClick()
        composeRule.onNodeWithText("Play").performClick()
        composeRule.runOnIdle { assertTrue(state.value.replayPlaying) }
        composeRule.onNodeWithText("Hide replay").performClick()
        composeRule.runOnIdle { assertFalse(state.value.replayPlaying) }
        composeRule.onNodeWithText("Pause").assertDoesNotExist()
    }

    @Test
    fun `empty places and unmatched search have different recovery messages`() {
        show(day().copy(tab = HistoryTab.Places, places = emptyList()))
        composeRule.onNodeWithText("No places yet").assertIsDisplayed()
        composeRule.runOnIdle {
            state.value =
                state.value.copy(
                    places = listOf(HistoryPlaceUi("cafe", UiText.Raw("Mothership Coffee"), UiText.Raw("2 visits"))),
                    placesQuery = "library",
                )
        }
        composeRule.onNodeWithText("No places match your search").assertIsDisplayed()
        composeRule.onNodeWithText("No places yet").assertDoesNotExist()
        composeRule.onNodeWithText("library").performTextClearance()
        composeRule.onNodeWithText("Mothership Coffee").assertIsDisplayed()
    }

    @Test
    fun `a visit without memories offers an inline retrospective memory action`() {
        show(singleVisit().let { it.copy(items = it.items.map { item -> item.copy(memories = emptyList()) }) })
        composeRule.onNodeWithText("Add a memory").performClick()
        composeRule.runOnIdle {
            assertEquals(listOf("cafe-morning" to HistoryEditAction.AddMemory), edits)
            assertFalse(state.value.detailVisible)
        }
    }

    @Test
    fun `delete requires confirmation and sends only the selected visit action`() {
        show(singleVisit())
        composeRule.onNodeWithContentDescription("See details").performClick()
        composeRule.onNodeWithText("Delete from history").performScrollTo().performClick()
        composeRule.onNodeWithText("Delete this moment?").assertIsDisplayed()
        composeRule.runOnIdle { assertTrue(edits.isEmpty()) }
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.runOnIdle { assertTrue(edits.isEmpty()) }
        composeRule.onNodeWithText("Delete from history").performScrollTo().performClick()
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.runOnIdle {
            assertEquals(listOf("cafe-morning" to HistoryEditAction.Delete), edits)
            assertTrue(openedMemories.isEmpty())
        }
    }

    private fun nextMoment(expectedId: String) {
        composeRule.onNodeWithContentDescription("Next moment").performClick()
        composeRule.runOnIdle {
            assertEquals(expectedId, state.value.selectedItemId)
            assertFalse(state.value.detailVisible)
        }
    }

    private fun show(initial: HumanLocationHistoryState) {
        state = mutableStateOf(initial)
        composeRule.setContent {
            LogDateTheme(dynamicColor = false) {
                HumanLocationHistoryContent(
                    state = state.value,
                    actions =
                        HumanLocationHistoryActions(
                            onSelectItem = { state.value = state.value.copy(selectedItemId = it) },
                            onOpenDetail = { state.value = state.value.copy(selectedItemId = it, detailVisible = true) },
                            onCloseDetail = { state.value = state.value.copy(detailVisible = false) },
                            onTab = { state.value = state.value.copy(tab = it) },
                            onPlacesQuery = { state.value = state.value.copy(placesQuery = it) },
                            onReplayPlaying = { state.value = state.value.copy(replayPlaying = it) },
                            onOpenMemory = { openedMemories.add(it) },
                            onEdit = { id, action -> edits.add(id to action) },
                        ),
                )
            }
        }
    }

    private fun singleVisit() = day().let { it.copy(items = it.items.take(1)) }

    private fun day() =
        HumanLocationHistoryState(
            dateLabel = UiText.Raw("Tuesday, September 29"),
            items =
                listOf(
                    HistoryItemUi(
                        "cafe-morning",
                        HistoryItemKind.Visit,
                        UiText.Raw("Mothership Coffee"),
                        UiText.Raw("9:00–9:30 AM"),
                        memories =
                            listOf(
                                HistoryMemoryUi(morningMemoryId, UiText.Raw("A quiet morning"), HistoryMemoryKind.Text),
                            ),
                    ),
                    HistoryItemUi(
                        "walk",
                        HistoryItemKind.Journey,
                        UiText.Raw("Walked"),
                        UiText.Raw("9:30–9:45 AM"),
                        UiText.Raw("15 min"),
                    ),
                    HistoryItemUi(
                        "gap",
                        HistoryItemKind.Gap,
                        UiText.Raw("Part of your day is missing"),
                        UiText.Raw("9:45–10:00 AM"),
                    ),
                    HistoryItemUi(
                        "cafe-return",
                        HistoryItemKind.Visit,
                        UiText.Raw("Mothership Coffee"),
                        UiText.Raw("10:00–10:30 AM"),
                    ),
                ),
        )
}
