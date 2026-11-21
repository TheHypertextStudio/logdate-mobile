package app.logdate.feature.editor.ui.editor

import app.logdate.client.domain.places.CapturePlace
import app.logdate.client.repository.journals.NoteCoordinates
import app.logdate.client.repository.journals.NoteLocation
import app.logdate.client.repository.journals.NotePlace
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The place the camera shows in its header, and whether new shots are saved with it.
 *
 * It follows the person's location only while the live camera is open. A shot is tagged only with a
 * place the header is showing, so nothing is saved that the person couldn't see; choosing not to tag
 * lasts until the camera closes.
 */
internal class CameraPlaceSession(
    scope: CoroutineScope,
    editorState: Flow<EditorState>,
    observePlace: (() -> Flow<CapturePlace>)?,
) {
    private val mutablePlace = MutableStateFlow<CapturePlace?>(null)
    private val mutableTagging = MutableStateFlow(true)

    /** The named place to show over the camera, or null when there's nothing to show. */
    val place: StateFlow<CapturePlace?> = mutablePlace.asStateFlow()

    /** Whether shots taken now are saved with [place]. */
    val tagging: StateFlow<Boolean> = mutableTagging.asStateFlow()

    init {
        if (observePlace != null) {
            scope.launch {
                editorState
                    .map { it.hasLiveCamera() }
                    .distinctUntilChanged()
                    .collectLatest { live ->
                        mutablePlace.value = null
                        mutableTagging.value = true
                        if (live) {
                            observePlace()
                                .catch { Napier.w("The camera's place stopped updating", it) }
                                .collect { mutablePlace.value = it.takeIf { place -> !place.name.isNullOrBlank() } }
                        }
                    }
            }
        }
    }

    fun setTagging(tag: Boolean) {
        mutableTagging.value = tag
    }

    /** Saves the shown place with a camera block whose shot has just landed. */
    fun tag(
        previous: EntryBlockUiState?,
        updated: EntryBlockUiState,
    ): EntryBlockUiState {
        if (updated !is CameraBlockUiState || updated.noteLocation != null) return updated
        val landed = updated.uri != null && (previous as? CameraBlockUiState)?.uri == null
        val place = mutablePlace.value
        if (!landed || place == null || !mutableTagging.value) return updated
        return updated.copy(location = place.location, noteLocation = place.toNoteLocation())
    }
}

internal fun EditorState.hasLiveCamera(): Boolean {
    val focused = blocks.firstOrNull { it.id == expandedBlockId }
    return focused is CameraBlockUiState && focused.uri == null
}

internal fun CapturePlace.toNoteLocation(): NoteLocation =
    NoteLocation(
        coordinates = NoteCoordinates(location.latitude, location.longitude, location.altitude.value.takeIf { it != 0.0 }),
        place = savedPlaceId?.let { NotePlace(it, name) },
    )
