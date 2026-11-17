package app.logdate.feature.editor.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import app.logdate.feature.editor.ui.editor.ImageBlockUiState
import app.logdate.feature.editor.ui.editor.TextBlockUiState
import app.logdate.feature.editor.ui.image.ImageBlockEditor
import app.logdate.feature.editor.ui.layout.FocusedBlockLayout
import app.logdate.feature.editor.ui.text.TextBlockContent
import app.logdate.shared.model.PhotoPresentation
import app.logdate.ui.foldable.FoldableHingeBounds
import app.logdate.ui.foldable.FoldableHingeInfo
import app.logdate.ui.foldable.FoldableHingeOrientation
import app.logdate.ui.foldable.FoldableHingeState
import app.logdate.ui.foldable.FoldableLayoutInfo
import app.logdate.ui.foldable.FoldableOcclusionType
import app.logdate.ui.foldable.FoldablePosture
import app.logdate.ui.foldable.provideFoldableLayoutInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FocusedEditorLayoutTest {
    @Test
    fun `wide writing column stays readable`() =
        runDesktopComposeUiTest(width = 1280, height = 420) {
            setContent {
                MaterialTheme {
                    TextBlockContent(TextBlockUiState(content = "A day outside"), onTextChanged = {}, onFocused = {})
                }
            }
            val field = onNodeWithTag("editor_text_input").getBoundsInRoot()
            assertTrue(field.right - field.left <= 640.dp, "Writing column is too wide: $field")
        }

    @Test
    fun `photo caption has its own space in a short wide editor`() =
        runDesktopComposeUiTest(width = 1280, height = 320) {
            setContent {
                MaterialTheme {
                    ImageBlockEditor(
                        ImageBlockUiState(uri = "sample", caption = "Our campsite"),
                        onBlockUpdated = {},
                        onDeleteRequested = {},
                        previewImagePainter = ColorPainter(Color.Green),
                    )
                }
            }
            val photo = onNodeWithContentDescription("Our campsite").getBoundsInRoot()
            val caption = onNodeWithText("Our campsite").getBoundsInRoot()
            assertTrue(caption.left >= photo.right || caption.top >= photo.bottom, "Caption overlays the photo")
            assertTrue(caption.bottom <= 320.dp, "Caption is behind the keyboard")
        }

    @Test
    fun `book editing stays to the right of the fold`() =
        runDesktopComposeUiTest(width = 1000, height = 360) {
            setContent {
                provideFoldableLayoutInfo(
                    FoldableLayoutInfo(
                        true,
                        FoldablePosture.Book,
                        FoldableHingeInfo(
                            FoldableHingeOrientation.Vertical,
                            FoldableHingeState.HalfOpened,
                            FoldableOcclusionType.Full,
                            FoldableHingeBounds(490.dp, 0.dp, 510.dp, 800.dp, 20.dp, 800.dp),
                            true,
                        ),
                    ),
                ) {
                    FocusedBlockLayout(
                        preview = { Box(Modifier.fillMaxSize().testTag("preview")) },
                        editor = { Box(Modifier.fillMaxSize().testTag("input")) },
                    )
                }
            }
            assertTrue(onNodeWithTag("preview").getBoundsInRoot().right <= 490.dp)
            assertTrue(onNodeWithTag("input").getBoundsInRoot().left >= 510.dp)
        }

    @Test
    fun `switching photo styles keeps the caption`() =
        runDesktopComposeUiTest(width = 1000, height = 600) {
            val photo = mutableStateOf(ImageBlockUiState(uri = "sample", caption = "Our campsite"))
            setContent {
                MaterialTheme {
                    ImageBlockEditor(
                        photo.value,
                        { photo.value = it },
                        {},
                        previewImagePainter = ColorPainter(Color.Green),
                    )
                }
            }
            onNodeWithTag("photo_actions").performClick()
            onNodeWithText("Framed").performClick()
            assertEquals(PhotoPresentation.Framed, photo.value.presentation)
            assertEquals("Our campsite", photo.value.caption)
            onNodeWithTag("photo_actions").performClick()
            onNodeWithText("Edge to edge").performClick()
            assertEquals(PhotoPresentation.EdgeToEdge, photo.value.presentation)
            assertEquals("Our campsite", photo.value.caption)
        }

    @Test
    fun `framed draft wraps the image without a tall empty mat`() =
        runDesktopComposeUiTest(width = 360, height = 800) {
            setContent {
                MaterialTheme {
                    Column(Modifier.fillMaxWidth()) {
                        ImageBlockEditor(
                            ImageBlockUiState(uri = "sample", caption = "Our campsite", presentation = PhotoPresentation.Framed),
                            {},
                            {},
                            previewImagePainter = BitmapPainter(ImageBitmap(400, 300)),
                            isExpanded = false,
                        )
                    }
                }
            }
            val photo = onNodeWithContentDescription("Our campsite").getBoundsInRoot()
            assertEquals((photo.right - photo.left) * 0.75f, photo.bottom - photo.top)
        }

    @Test
    fun `edge to edge draft wraps the image without cropping`() =
        runDesktopComposeUiTest(width = 360, height = 800) {
            setContent {
                MaterialTheme {
                    Column(Modifier.fillMaxWidth()) {
                        ImageBlockEditor(
                            ImageBlockUiState(uri = "sample", caption = "Our campsite", presentation = PhotoPresentation.EdgeToEdge),
                            {},
                            {},
                            previewImagePainter = BitmapPainter(ImageBitmap(300, 600)),
                            isExpanded = false,
                        )
                    }
                }
            }
            val photo = onNodeWithContentDescription("Our campsite").getBoundsInRoot()
            assertEquals((photo.right - photo.left) * 2f, photo.bottom - photo.top)
        }

    @Test
    fun `expanded photo and caption stay together on wide screens`() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            setContent {
                MaterialTheme {
                    ImageBlockEditor(
                        ImageBlockUiState(uri = "sample", caption = "Our campsite"),
                        {},
                        {},
                        previewImagePainter = BitmapPainter(ImageBitmap(400, 300)),
                    )
                }
            }
            val photo = onNodeWithContentDescription("Our campsite").getBoundsInRoot()
            val caption = onNodeWithText("Our campsite").getBoundsInRoot()
            assertTrue((photo.right - photo.left) <= 640.dp)
            assertTrue((caption.right - caption.left) <= (photo.right - photo.left))
            assertTrue(caption.top >= photo.bottom && caption.top - photo.bottom <= 24.dp)
            assertTrue(caption.left >= photo.left && caption.left - photo.left <= 16.dp)
        }

    @Test
    fun `framed caption belongs inside the photo surface`() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            setContent {
                MaterialTheme {
                    ImageBlockEditor(
                        ImageBlockUiState(uri = "sample", caption = "Our campsite", presentation = PhotoPresentation.Framed),
                        {},
                        {},
                        previewImagePainter = BitmapPainter(ImageBitmap(400, 300)),
                    )
                }
            }
            val surface = onNodeWithTag("photo_surface").getBoundsInRoot()
            val photo = onNodeWithContentDescription("Our campsite").getBoundsInRoot()
            val caption = onNodeWithText("Our campsite").getBoundsInRoot()
            assertTrue(caption.top >= photo.bottom)
            assertTrue(caption.bottom <= surface.bottom)
            assertTrue(caption.left >= surface.left && caption.right <= surface.right)
            assertTrue(surface.bottom - caption.bottom <= 16.dp)
        }
}
