package app.logdate.feature.editor.ui.editor

import app.logdate.client.domain.places.CapturePlace
import app.logdate.client.repository.journals.NoteCoordinates
import app.logdate.client.repository.journals.NoteLocation
import app.logdate.client.repository.journals.NotePlace
import app.logdate.feature.editor.ui.mapper.toDomainBlock
import app.logdate.feature.editor.ui.mapper.toJournalNote
import app.logdate.shared.model.AltitudeUnit
import app.logdate.shared.model.Location
import app.logdate.shared.model.LocationAltitude
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class CameraPlaceSessionTest {
    private val spot = Location(30.2669, -97.7729, LocationAltitude(0.0, AltitudeUnit.METERS))
    private val homeId = Uuid.random()
    private val home = CapturePlace(spot, "Home", homeId)

    @Test
    fun aShotThatLandsIsSavedWithThePlaceTheHeaderShows() =
        runTest(UnconfinedTestDispatcher()) {
            val camera = CameraBlockUiState()
            val session = liveSession(camera, places = MutableStateFlow(home))

            val shot = session.tag(camera, camera.copy(uri = "file:///shot.jpg"))

            assertEquals(
                NoteLocation(NoteCoordinates(spot.latitude, spot.longitude), NotePlace(homeId, "Home")),
                (shot as CameraBlockUiState).noteLocation,
            )
            assertEquals(spot, shot.location)
        }

    @Test
    fun choosingToLeaveThePlaceOutKeepsShotsUntagged() =
        runTest(UnconfinedTestDispatcher()) {
            val camera = CameraBlockUiState()
            val session = liveSession(camera, places = MutableStateFlow(home))

            session.setTagging(false)
            val shot = session.tag(camera, camera.copy(uri = "file:///shot.jpg")) as CameraBlockUiState

            assertNull(shot.noteLocation)
        }

    @Test
    fun aPlaceWithoutANameIsNeitherShownNorSaved() =
        runTest(UnconfinedTestDispatcher()) {
            val camera = CameraBlockUiState()
            val session = liveSession(camera, places = MutableStateFlow(CapturePlace(spot, null)))

            assertNull(session.place.value)
            assertNull((session.tag(camera, camera.copy(uri = "file:///shot.jpg")) as CameraBlockUiState).noteLocation)
        }

    @Test
    fun closingTheCameraForgetsThePlaceAndTheChoice() =
        runTest(UnconfinedTestDispatcher()) {
            val camera = CameraBlockUiState()
            val state = MutableStateFlow(EditorState(blocks = listOf(camera), expandedBlockId = camera.id))
            val session = CameraPlaceSession(backgroundScope, state) { MutableStateFlow(home) }
            session.setTagging(false)

            state.value = state.value.copy(expandedBlockId = null)

            assertNull(session.place.value)
            assertEquals(true, session.tagging.value)
        }

    @Test
    fun onlyTheMomentAShotLandsIsTagged() =
        runTest(UnconfinedTestDispatcher()) {
            val camera = CameraBlockUiState()
            val session = liveSession(camera, places = MutableStateFlow(home))
            val landed = camera.copy(uri = "file:///shot.jpg")

            val captioned = session.tag(landed, landed.copy(caption = "Lunch")) as CameraBlockUiState

            assertNull(captioned.noteLocation)
        }

    @Test
    fun aTaggedPhotoKeepsItsPlaceWhenTheEntryIsSavedAndOpenedAgain() {
        val location = NoteLocation(NoteCoordinates(spot.latitude, spot.longitude), NotePlace(homeId, "Home"))
        val shot = CameraBlockUiState(uri = "file:///shot.jpg", noteLocation = location)

        val reopened = shot.toJournalNote()!!.toDomainBlock()

        assertIs<ImageBlockUiState>(reopened)
        assertEquals(location, reopened.noteLocation)
        assertEquals(location, reopened.toJournalNote()!!.location)
    }

    private fun TestScope.liveSession(
        camera: CameraBlockUiState,
        places: MutableStateFlow<CapturePlace>,
    ): CameraPlaceSession {
        val state = MutableStateFlow(EditorState(blocks = listOf(camera), expandedBlockId = camera.id))
        return CameraPlaceSession(backgroundScope, state) { places.filterNotNull() }
    }
}
