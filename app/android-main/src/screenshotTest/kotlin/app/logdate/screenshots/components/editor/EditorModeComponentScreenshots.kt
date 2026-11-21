package app.logdate.screenshots.components.editor

import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import app.logdate.client.R
import app.logdate.feature.editor.ui.audio.ActiveRecordingDisplay
import app.logdate.feature.editor.ui.audio.AudioRecordingControls
import app.logdate.feature.editor.ui.camera.CameraCaptureEntryPreview
import app.logdate.feature.editor.ui.camera.CameraCapturePreviewContent
import app.logdate.feature.editor.ui.camera.CameraCapturePreviewState
import app.logdate.feature.editor.ui.editor.ImageBlockUiState
import app.logdate.feature.editor.ui.editor.RecordingState
import app.logdate.feature.editor.ui.editor.TextBlockUiState
import app.logdate.feature.editor.ui.editor.VideoBlockUiState
import app.logdate.feature.editor.ui.image.ImageBlockEditor
import app.logdate.feature.editor.ui.image.ImagePickerPreviewContent
import app.logdate.feature.editor.ui.image.ImagePickerPreviewState
import app.logdate.feature.editor.ui.layout.ImmersiveEditorLayout
import app.logdate.feature.editor.ui.media.ManagedMediaSelectionState
import app.logdate.feature.editor.ui.text.TextBlockContent
import app.logdate.feature.editor.ui.video.VideoBlockEditor
import app.logdate.feature.editor.ui.video.VideoPickerPreviewContent
import app.logdate.screenshots.common.LargeScreenAuditPreviewMatrix
import app.logdate.screenshots.common.ScreenshotPreviewMatrix
import app.logdate.screenshots.common.ScreenshotTestData
import app.logdate.screenshots.common.ScreenshotTestData.COMPACT_PHONE
import app.logdate.screenshots.common.ScreenshotTestData.PHONE
import app.logdate.screenshots.common.ScreenshotTestData.PHONE_LANDSCAPE
import app.logdate.screenshots.common.ScreenshotTestData.TABLET_PORTRAIT
import app.logdate.screenshots.common.ScreenshotTheme
import app.logdate.ui.audio.expansion.SpatialExpandedAudioBlock
import app.logdate.ui.audio.model.AudioPalette
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.android.tools.screenshot.PreviewTest
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private const val SAMPLE_IMAGE_URI =
    "file:///android_asset/sample_note_photo.jpg"
private const val SAMPLE_VIDEO_URI = SAMPLE_IMAGE_URI
private const val BOOK_FOLDABLE = "spec:width=1440dp,height=900dp"
private const val TABLETOP_FOLDABLE = "spec:width=1440dp,height=900dp"

@Preview(name = "Compact Light", showBackground = true, device = PHONE)
@Preview(
    name = "Compact Dark",
    showBackground = true,
    device = PHONE,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Preview(name = "Large Text Light", showBackground = true, device = PHONE, fontScale = 2f)
@Preview(
    name = "Large Text Dark",
    showBackground = true,
    device = PHONE,
    fontScale = 2f,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
private annotation class MediaImportStatePreviewMatrix

private val mockPalette =
    AudioPalette(
        waveformGradientStart = 0xFFE8A044,
        waveformGradientEnd = 0xFFD4603A,
        playedFillColor = 0xFFE8A044,
        accentColor = 0xFFE8A044,
        immersiveBackground = 0xFF1A0F05,
    )

private val mockCreatedAt = Instant.fromEpochMilliseconds(1_740_000_000_000L)

@PreviewTest
@ScreenshotPreviewMatrix
@Composable
fun EditorMode_TextFocused() {
    EditorModeFrame {
        TextBlockContent(
            block =
                TextBlockUiState(
                    content = "Shipped the editor transition cleanup today. The reverse motion finally feels coherent.",
                ),
            onTextChanged = {},
            onFocused = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@PreviewTest
@ScreenshotPreviewMatrix
@Composable
fun EditorMode_InlineMarkdownFocused() {
    EditorModeFrame {
        TextBlockContent(
            block =
                TextBlockUiState(
                    content =
                        """# Olympic Peninsula

                            |The tide was **much higher** than expected, but the trail stayed open.

                            |- Pack the `rain shell`
                            |- Share the [photos](https://logdate.app)
                            |
                            |> Come back in autumn.
                        """.trimMargin(),
                ),
            onTextChanged = {},
            onFocused = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@PreviewTest
@Preview(showBackground = true, device = PHONE)
@Composable
fun EditorMode_ImagePickerLoaded() {
    EditorModeFrame {
        ImagePickerPreviewContent(
            state = ImagePickerPreviewState.Loaded(sampleImageUri = SAMPLE_IMAGE_URI, itemCount = 12),
            previewImagePainter = painterResource(R.drawable.sample_note_photo),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@PreviewTest
@MediaImportStatePreviewMatrix
@Composable
fun EditorMode_ImagePickerImporting() {
    EditorModeFrame {
        ImagePickerPreviewContent(
            state = ImagePickerPreviewState.Loaded(sampleImageUri = SAMPLE_IMAGE_URI, itemCount = 12),
            importState = ManagedMediaSelectionState.Importing,
            previewImagePainter = painterResource(R.drawable.sample_note_photo),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@PreviewTest
@MediaImportStatePreviewMatrix
@Composable
fun EditorMode_ImagePickerImportFailed() {
    EditorModeFrame {
        ImagePickerPreviewContent(
            state = ImagePickerPreviewState.Loaded(sampleImageUri = SAMPLE_IMAGE_URI, itemCount = 12),
            importState = ManagedMediaSelectionState.Failed,
            previewImagePainter = painterResource(R.drawable.sample_note_photo),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@PreviewTest
@Preview(showBackground = true, device = PHONE)
@Composable
fun EditorMode_ImagePickerPermission() {
    EditorModeFrame {
        ImagePickerPreviewContent(
            state = ImagePickerPreviewState.PermissionRequired(permanentlyDenied = false),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@PreviewTest
@Preview(showBackground = true, device = PHONE)
@Composable
fun EditorMode_ImageFocused() {
    EditorModeFrame {
        ImageBlockEditor(
            block =
                ImageBlockUiState(
                    uri = SAMPLE_IMAGE_URI,
                    caption = "Wrapped up for a rainy walk",
                ),
            onBlockUpdated = {},
            onDeleteRequested = {},
            previewImagePainter = painterResource(R.drawable.sample_note_photo),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@PreviewTest
@Preview(showBackground = true, device = PHONE)
@Composable
fun EditorMode_AudioIdle() {
    EditorModeFrame {
        AudioRecordingControls(
            recordingState = RecordingState.INACTIVE,
            audioLevels = emptyList(),
            recordingDuration = 0.seconds,
            onStartRecording = {},
            onStopRecording = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@PreviewTest
@Preview(showBackground = true, device = PHONE)
@Composable
fun EditorMode_AudioRecording() {
    EditorModeFrame {
        ActiveRecordingDisplay(
            audioLevels = ScreenshotTestData.mockAudioLevels,
            recordingDuration = 1.minutes + 45.seconds,
            onRestart = {},
            onPause = {},
            onFinish = {},
            isPaused = false,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@PreviewTest
@Preview(showBackground = true, device = PHONE)
@Composable
fun EditorMode_AudioPlayback() {
    EditorModeFrame {
        SpatialExpandedAudioBlock(
            amplitudes = ScreenshotTestData.mockAmplitudes,
            progress = 0.42f,
            isPlaying = true,
            palette = mockPalette,
            durationMs = 185_000L,
            createdAt = mockCreatedAt,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@PreviewTest
@ScreenshotPreviewMatrix
@LargeScreenAuditPreviewMatrix
@Composable
fun EditorMode_CameraCapture() {
    EditorModeFrame(isImmersiveBlockActive = true) {
        CameraCapturePreviewContent(
            state = CameraCapturePreviewState.LiveCapture(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@PreviewTest
@Preview(showBackground = true, device = BOOK_FOLDABLE)
@Composable
fun EditorMode_CameraCapture_BookPosture() {
    EditorModeFrame(isImmersiveBlockActive = true) {
        CameraCapturePreviewContent(
            state = CameraCapturePreviewState.LiveCapture(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@PreviewTest
@Preview(showBackground = true, device = TABLETOP_FOLDABLE)
@Composable
fun EditorMode_CameraCapture_TabletopPosture() {
    EditorModeFrame(isImmersiveBlockActive = true) {
        CameraCapturePreviewContent(
            state = CameraCapturePreviewState.LiveCapture(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@PreviewTest
@ScreenshotPreviewMatrix
@LargeScreenAuditPreviewMatrix
@Composable
fun EditorMode_CameraCapture_ExternalCamera() {
    EditorModeFrame(isImmersiveBlockActive = true) {
        CameraCapturePreviewContent(
            state = CameraCapturePreviewState.LiveCapture(showCameraPicker = true),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@PreviewTest
@ScreenshotPreviewMatrix
@LargeScreenAuditPreviewMatrix
@Composable
fun EditorMode_CameraCapture_Recording() {
    EditorModeFrame(isImmersiveBlockActive = true) {
        CameraCapturePreviewContent(
            state = CameraCapturePreviewState.LiveCapture(isRecording = true),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@PreviewTest
@Preview(name = "Phone", showBackground = true, device = PHONE)
@Preview(name = "Compact Phone", showBackground = true, device = COMPACT_PHONE)
@Preview(name = "Landscape", showBackground = true, device = PHONE_LANDSCAPE)
@Preview(name = "Tablet", showBackground = true, device = TABLET_PORTRAIT)
@Preview(name = "RTL", showBackground = true, device = PHONE, locale = "ar")
@Preview(name = "Large Text", showBackground = true, device = PHONE, fontScale = 2f)
@Composable
fun EditorMode_CameraInEntry() {
    CameraInEntryFrame {
        CameraCaptureEntryPreview(
            CameraCapturePreviewState.LiveCapture(),
            Modifier.fillMaxSize(),
            memoryPhotoUri = SAMPLE_PHOTO_URI,
            placeName = "Zilker Park",
        )
    }
}

@PreviewTest
@Preview(name = "Phone", showBackground = true, device = PHONE)
@Composable
fun EditorMode_CameraInEntry_PlaceLeftOut() {
    CameraInEntryFrame {
        CameraCaptureEntryPreview(
            CameraCapturePreviewState.LiveCapture(),
            Modifier.fillMaxSize(),
            memoryPhotoUri = SAMPLE_PHOTO_URI,
            placeName = "Zilker Park",
            placeSaved = false,
        )
    }
}

@PreviewTest
@Preview(name = "Phone", showBackground = true, device = PHONE)
@Composable
fun EditorMode_CameraInEntry_NewEntry() {
    CameraInEntryFrame {
        CameraCaptureEntryPreview(CameraCapturePreviewState.LiveCapture(), Modifier.fillMaxSize())
    }
}

@PreviewTest
@Preview(name = "Phone", showBackground = true, device = PHONE)
@Composable
fun EditorMode_CameraInEntry_Recording() {
    CameraInEntryFrame {
        CameraCaptureEntryPreview(
            CameraCapturePreviewState.LiveCapture(isRecording = true),
            Modifier.fillMaxSize(),
            memoryPhotoUri = SAMPLE_PHOTO_URI,
        )
    }
}

private const val SAMPLE_PHOTO_URI = "android.resource://studio.hypertext.logdate.debug/drawable/workspace_sample_photo"

/** Serves the sample photo for every image the camera header's filmstrip asks for. */
@Composable
private fun CameraInEntryFrame(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val image = remember { BitmapFactory.decodeResource(context.resources, R.drawable.workspace_sample_photo).asImage() }
    val handler = remember(image) { AsyncImagePreviewHandler { image } }
    ScreenshotTheme {
        CompositionLocalProvider(LocalAsyncImagePreviewHandler provides handler) { content() }
    }
}

@PreviewTest
@ScreenshotPreviewMatrix
@Composable
fun EditorMode_CameraPermission() {
    EditorModeFrame {
        CameraCapturePreviewContent(
            state = CameraCapturePreviewState.PermissionRequired,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@PreviewTest
@Preview(showBackground = true, device = PHONE)
@Composable
fun EditorMode_VideoPicker() {
    EditorModeFrame {
        VideoPickerPreviewContent(
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@PreviewTest
@MediaImportStatePreviewMatrix
@Composable
fun EditorMode_VideoPickerImporting() {
    EditorModeFrame {
        VideoPickerPreviewContent(
            importState = ManagedMediaSelectionState.Importing,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@PreviewTest
@MediaImportStatePreviewMatrix
@Composable
fun EditorMode_VideoPickerImportFailed() {
    EditorModeFrame {
        VideoPickerPreviewContent(
            importState = ManagedMediaSelectionState.Failed,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@PreviewTest
@Preview(showBackground = true, device = PHONE)
@Composable
fun EditorMode_VideoFocused() {
    EditorModeFrame {
        VideoBlockEditor(
            block =
                VideoBlockUiState(
                    uri = SAMPLE_VIDEO_URI,
                    caption = "Golden hour commute",
                    durationMs = 84_000L,
                ),
            onBlockUpdated = {},
            onDeleteRequested = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * The room a focused block editor is laid out in, with nothing drawn around it. These scenes show
 * one editor each, so they carry no top bar or journal control of their own.
 */
@Composable
private fun EditorModeFrame(
    isImmersiveBlockActive: Boolean = false,
    content: @Composable () -> Unit,
) {
    ScreenshotTheme {
        ImmersiveEditorLayout(
            isImmersiveBlockActive = isImmersiveBlockActive,
            topBarContent = {},
            editorContent = content,
            bottomContent = {},
        )
    }
}
