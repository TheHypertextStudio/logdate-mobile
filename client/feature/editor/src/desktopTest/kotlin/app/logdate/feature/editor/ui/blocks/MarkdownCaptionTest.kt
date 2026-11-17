package app.logdate.feature.editor.ui.blocks

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import app.logdate.feature.editor.ui.common.OverlayCaptionField
import app.logdate.feature.editor.ui.editor.AudioBlockUiState
import app.logdate.feature.editor.ui.editor.AudioCaptureState
import app.logdate.feature.editor.ui.editor.ImageBlockUiState
import app.logdate.feature.editor.ui.editor.TextBlockUiState
import app.logdate.feature.editor.ui.editor.VideoBlockUiState
import app.logdate.feature.editor.ui.image.ImageBlockPreview
import app.logdate.feature.editor.ui.image.PhotoEditingControls
import app.logdate.ui.theme.LogDateTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MarkdownCaptionTest {
    @Test
    fun `overlay caption preserves editable source and applies parsed emphasis`() =
        captionEditor { caption, update ->
            OverlayCaptionField(caption, update)
        }

    @Test
    fun `memory caption preserves editable source and applies parsed emphasis`() =
        captionEditor { caption, update ->
            MemoryCaptionField(ImageBlockUiState(caption = caption), {}, { update((it as ImageBlockUiState).caption) }, 0)
        }

    @Test
    fun `photo editing caption preserves editable source and applies parsed emphasis`() =
        captionEditor { caption, update ->
            PhotoEditingControls(ImageBlockUiState(caption = caption)) { update(it.caption) }
        }

    @Test
    fun `completed audio caption reads shared Markdown without syntax`() =
        runSkikoComposeUiTest {
            setContent {
                LogDateTheme(dynamicColor = false) {
                    MemoryCaptionField(
                        AudioBlockUiState(captureState = AudioCaptureState.Ready("file:///clip.wav", 5000), caption = CAPTION_SOURCE),
                        {},
                        {},
                        0,
                    )
                }
            }
            onNodeWithText("A bold caption").assertExists()
        }

    @Test
    fun `passive entry text and video captions share readable Markdown`() =
        runSkikoComposeUiTest {
            setContent {
                LogDateTheme(dynamicColor = false) {
                    EntryContextPreview(listOf(TextBlockUiState(content = CAPTION_SOURCE), VideoBlockUiState(caption = "A *quiet* walk")))
                }
            }
            onNodeWithText("A bold caption").assertExists()
            onNodeWithText("A quiet walk").assertExists()
        }

    @Test
    fun `image description uses readable caption without formatting syntax`() =
        runSkikoComposeUiTest {
            setContent {
                LogDateTheme(dynamicColor = false) {
                    ImageBlockPreview(ImageBlockUiState(caption = CAPTION_SOURCE), painter = ColorPainter(Color.Blue))
                }
            }
            onNodeWithContentDescription("A bold caption").assertExists()
        }

    private fun captionEditor(content: @Composable (String, (String) -> Unit) -> Unit) =
        runSkikoComposeUiTest {
            var update: String? = null
            setContent { LogDateTheme(dynamicColor = false) { content(CAPTION_SOURCE) { update = it } } }
            val field = onNode(hasSetTextAction())
            field.assertTextEquals(CAPTION_SOURCE)
            val layouts = mutableListOf<TextLayoutResult>()
            field.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val styled = layouts.single().layoutInput.text
            assertEquals(CAPTION_SOURCE, styled.text)
            assertTrue(styled.spanStyles.any { it.item.fontWeight == FontWeight.Bold && it.start == 4 && it.end == 8 })
            val replacement = "Keep \\*literal\\* and **unfinished"
            field.performTextReplacement(replacement)
            assertEquals(replacement, update)
        }
}

private const val CAPTION_SOURCE = "A **bold** caption"
