@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.editor.ui.camera

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import app.logdate.client.datastore.EntryStartMode
import app.logdate.feature.editor.ui.editor.CameraBlockUiState
import app.logdate.feature.editor.ui.editor.EditorState
import app.logdate.feature.editor.ui.handwriting.EntryPageChrome
import app.logdate.feature.editor.ui.handwriting.EntryPageOverviewState

/**
 * The camera as it sits inside the entry, with the header above and the creation dock below, for
 * screenshot references. A recording camera block holds its pending clip, as it does in the app.
 */
@Composable
fun CameraCaptureEntryPreview(
    state: CameraCapturePreviewState,
    modifier: Modifier = Modifier,
) {
    val recording = state is CameraCapturePreviewState.LiveCapture && state.isRecording
    val camera = remember(recording) { CameraBlockUiState(pendingFilePath = if (recording) "preview://clip.mp4" else null) }
    val overview = remember { EntryPageOverviewState(false) }
    EntryPageChrome(
        editor = EditorState(blocks = listOf(camera), expandedBlockId = camera.id),
        overview = overview,
        defaultMode = EntryStartMode.CAMERA,
        onReturn = {},
        onExit = {},
        onCapture = {},
        modifier = modifier,
    ) {
        CameraCapturePreviewContent(state, Modifier.fillMaxSize())
    }
}
