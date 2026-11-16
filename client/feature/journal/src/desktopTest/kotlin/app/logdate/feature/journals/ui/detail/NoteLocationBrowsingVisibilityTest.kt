package app.logdate.feature.journals.ui.detail

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.logdate.client.repository.journals.NoteCoordinates
import app.logdate.client.repository.journals.NoteLocation
import app.logdate.client.repository.journals.NotePlace
import app.logdate.ui.theme.LogDateTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class NoteLocationBrowsingVisibilityTest {
    @Test
    fun browsingActionsFollowAvailabilityWhileLocationMetadataRemainsVisible() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            var opens = 0
            val browse = mutableStateOf<(() -> Unit)?>(null)
            val shared =
                NoteViewerShared(
                    noteId = Uuid.random(),
                    createdAt = Instant.parse("2026-10-13T12:00:00Z"),
                    lastUpdated = Instant.parse("2026-10-13T12:00:00Z"),
                    location =
                        NoteLocation(
                            coordinates = NoteCoordinates(latitude = 37.5, longitude = -122.5),
                            place = NotePlace(id = Uuid.random(), name = "Riverside Café"),
                        ),
                )
            setContent {
                LogDateTheme(dynamicColor = false) {
                    NoteViewerContent(shared, onOpenLocationTimeline = browse.value) {
                        Text("A memory at the café")
                    }
                }
            }

            onNodeWithText("Open in Locations").assertDoesNotExist()
            onAllNodes(hasClickAction()).assertCountEquals(0)
            onNodeWithText("Riverside Café").assertIsDisplayed()
            onNodeWithText("37.5000, -122.5000").assertIsDisplayed()

            runOnIdle { browse.value = { opens++ } }
            onNodeWithText("Open in Locations").assertIsDisplayed()
            onAllNodes(hasClickAction()).assertCountEquals(2)
            onAllNodes(hasClickAction())[0].performClick()
            onAllNodes(hasClickAction())[1].performClick()
            assertEquals(2, opens)

            runOnIdle { browse.value = null }
            onAllNodes(hasClickAction()).assertCountEquals(0)
            onNodeWithText("Riverside Café").assertIsDisplayed()
            onNodeWithText("37.5000, -122.5000").assertIsDisplayed()
            onNodeWithText("A memory at the café").assertIsDisplayed()
        }
}
