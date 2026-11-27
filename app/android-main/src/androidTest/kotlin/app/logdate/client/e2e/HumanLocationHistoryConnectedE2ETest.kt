package app.logdate.client.e2e

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.logdate.client.domain.location.history.LocationHistoryService
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.text.toUiText
import app.logdate.feature.location.timeline.ui.history.HumanLocationHistoryScreen
import app.logdate.feature.location.timeline.ui.history.HumanLocationHistoryViewModel
import app.logdate.shared.model.location.SemanticPlace
import app.logdate.shared.model.location.VisitMemoryContext
import app.logdate.ui.theme.LogDateTheme
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/** Real repositories, view model, permission status and native map integration on managed emulators. */
@RunWith(AndroidJUnit4::class)
class HumanLocationHistoryConnectedE2ETest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `saved visit opens from Room and carries its own place into add memory`() {
        val koin = GlobalContext.get()
        val history = koin.get<LocationHistoryService>()
        val start = Clock.System.now() - 1.hours
        val place = SemanticPlace(Uuid.random().toString(), "History integration cafe", 36.167, -115.148, userConfirmed = true)
        runBlocking { history.addVisit(start, start + 15.minutes, place) }
        lateinit var viewModel: HumanLocationHistoryViewModel
        var context: VisitMemoryContext? = null
        composeRule.runOnUiThread {
            viewModel = HumanLocationHistoryViewModel(history, koin.get(), koin.get(), SavedStateHandle())
            viewModel.setDate(start.toLocalDateTime(TimeZone.currentSystemDefault()).date)
        }
        composeRule.setContent {
            LogDateTheme(dynamicColor = false) {
                HumanLocationHistoryScreen(onOpenNote = {}, onAddMemory = { context = it }, viewModel = viewModel)
            }
        }
        composeRule.waitUntil(15_000) {
            viewModel.state.value.items
                .any { it.title == place.name.toUiText() }
        }
        composeRule.onNodeWithText(place.name, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Add a memory").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertNotNull(context)
            assertEquals(place.id, context?.placeId)
            assertEquals(place.latitude, context?.latitude)
            assertEquals(start, context?.visitStart)
        }
        val notes = koin.get<JournalNotesRepository>()
        val memory = JournalNote.Text(creationTimestamp = start, lastUpdated = start, content = "An unplaced memory to remember here")
        runBlocking { notes.create(memory) }
        composeRule.waitUntil(15_000) {
            viewModel.snapshot.value
                ?.notes
                ?.any { it.uid == memory.uid } == true
        }
        composeRule.onNodeWithContentDescription("See details").performScrollTo().performClick()
        composeRule.onNodeWithText("Link an existing memory").performScrollTo().performClick()
        composeRule.onNodeWithText(memory.content).performScrollTo().performClick()
        composeRule.waitUntil(15_000) {
            viewModel.state.value.items
                .any { it.title == place.name.toUiText() && it.memories.any { preview -> preview.id == memory.uid } }
        }
        runBlocking {
            val retained = notes.getNoteById(memory.uid)
            assertEquals(start.toEpochMilliseconds(), retained?.creationTimestamp?.toEpochMilliseconds())
            assertEquals(null, retained?.location)
        }
    }
}
