@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.editor.ui.camera

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import app.logdate.client.datastore.EntryStartMode
import app.logdate.feature.editor.ui.editor.AudioBlockUiState
import app.logdate.feature.editor.ui.editor.AudioCaptureState
import app.logdate.feature.editor.ui.editor.CameraBlockUiState
import app.logdate.feature.editor.ui.editor.EditorState
import app.logdate.feature.editor.ui.editor.ImageBlockUiState
import app.logdate.feature.editor.ui.editor.TextBlockUiState
import app.logdate.feature.editor.ui.handwriting.EntryPageChrome
import app.logdate.feature.editor.ui.handwriting.EntryPageOverviewState
import app.logdate.feature.editor.ui.handwriting.LocalCameraHeaderClock
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** 4:12 PM on the West Coast, the time zone screenshot references render in. */
private val PreviewNow = Instant.parse("2026-11-21T00:12:00Z")

/**
 * The camera as it sits inside the entry, with the header above and the creation dock below, for
 * screenshot references. A recording camera block holds its pending clip, as it does in the app.
 *
 * With [memoryPhotoUri], the entry already holds a few memories, so the header's filmstrip has
 * photos, a note and a voice recording in it.
 */
@Composable
fun CameraCaptureEntryPreview(
    state: CameraCapturePreviewState,
    modifier: Modifier = Modifier,
    memoryPhotoUri: String? = null,
) {
    val recording = state is CameraCapturePreviewState.LiveCapture && state.isRecording
    val camera = remember(recording) { CameraBlockUiState(pendingFilePath = if (recording) "preview://clip.mp4" else null) }
    val memories = remember(memoryPhotoUri) { memoryPhotoUri?.let(::previewMemories).orEmpty() }
    val overview = remember { EntryPageOverviewState(false) }
    val clock = remember { FixedClock(PreviewNow) }
    CompositionLocalProvider(LocalCameraHeaderClock provides clock) {
        EntryPageChrome(
            editor = EditorState(blocks = memories + camera, expandedBlockId = camera.id),
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
}

private fun previewMemories(photoUri: String) =
    listOf(
        ImageBlockUiState(id = Uuid.parse("00000000-0000-0000-0000-0000000000a1"), timestamp = PreviewNow, uri = photoUri),
        TextBlockUiState(id = Uuid.parse("00000000-0000-0000-0000-0000000000a2"), timestamp = PreviewNow, content = "Lunch by the water"),
        AudioBlockUiState(
            id = Uuid.parse("00000000-0000-0000-0000-0000000000a3"),
            timestamp = PreviewNow,
            captureState = AudioCaptureState.Ready("preview://voice.m4a", 14_000),
        ),
        CameraBlockUiState(id = Uuid.parse("00000000-0000-0000-0000-0000000000a4"), timestamp = PreviewNow, uri = photoUri),
    )

private class FixedClock(
    private val instant: Instant,
) : Clock {
    override fun now(): Instant = instant
}
