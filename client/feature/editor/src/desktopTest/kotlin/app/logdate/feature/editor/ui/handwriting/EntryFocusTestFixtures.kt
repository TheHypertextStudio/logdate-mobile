@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package app.logdate.feature.editor.ui.handwriting

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.logdate.feature.editor.ui.LocalAnimatedVisibilityScope
import app.logdate.feature.editor.ui.LocalSharedTransitionScope
import app.logdate.feature.editor.ui.audio.AudioRecordingControls
import app.logdate.feature.editor.ui.editor.AudioBlockUiState
import app.logdate.feature.editor.ui.editor.RecordingState
import app.logdate.feature.editor.ui.layout.LocalEntryChromeHeight
import app.logdate.feature.editor.ui.layout.LocalEntryCreationReserve
import app.logdate.feature.editor.ui.state.EditorBlockPresentation
import java.io.File
import kotlin.time.Duration
import kotlin.uuid.Uuid

internal fun samplePhotoUri(): String = File("../../../app/android-main/src/debug/assets/sample_note_photo.jpg").toURI().toString()

/** UI fixture only. The Android host uses AudioBlockEditor and its real recording services. */
internal fun recorderFixture(block: AudioBlockUiState): Pair<kotlin.uuid.Uuid, EditorBlockPresentation> =
    block.id to
        EditorBlockPresentation(
            inline = {
                Box(Modifier.fillMaxWidth().recorderFixtureBounds(block.id)) {
                    AudioRecordingControls(
                        RecordingState.INACTIVE,
                        emptyList(),
                        Duration.ZERO,
                        onStartRecording = {},
                        onStopRecording = {},
                    )
                }
            },
            focused = {
                Box(
                    Modifier
                        .fillMaxSize()
                        .recorderFixtureBounds(block.id)
                        .padding(
                            top = LocalEntryChromeHeight.current,
                            bottom = LocalEntryCreationReserve.current,
                        ),
                ) {
                    AudioRecordingControls(
                        RecordingState.INACTIVE,
                        emptyList(),
                        Duration.ZERO,
                        onStartRecording = {},
                        onStopRecording = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            },
        )

@Composable
private fun Modifier.recorderFixtureBounds(id: Uuid): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val visibility = LocalAnimatedVisibilityScope.current ?: return this
    return with(shared) {
        this@recorderFixtureBounds.sharedBounds(
            rememberSharedContentState("block_surface_$id"),
            animatedVisibilityScope = visibility,
        )
    }
}
