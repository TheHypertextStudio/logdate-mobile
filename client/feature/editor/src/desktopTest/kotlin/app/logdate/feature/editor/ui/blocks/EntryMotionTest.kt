package app.logdate.feature.editor.ui.blocks

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import app.logdate.feature.editor.ui.LocalSharedTransitionScope
import app.logdate.feature.editor.ui.MainEditorContent
import app.logdate.feature.editor.ui.editor.EntryBlockUiState
import app.logdate.feature.editor.ui.editor.TextBlockUiState
import app.logdate.feature.editor.ui.state.BlocksUiState
import app.logdate.ui.theme.LogDateTheme
import org.jetbrains.skia.Image
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class, ExperimentalSharedTransitionApi::class)
class EntryMotionTest {
    @Test
    fun `adding another writing block morphs from its Add choice`() =
        runSkikoComposeUiTest(size = Size(390f, 780f)) {
            var blocks by mutableStateOf<List<EntryBlockUiState>>(listOf(TextBlockUiState(content = "Already in the entry.")))
            var selected by mutableStateOf<Uuid?>(null)
            mainClock.autoAdvance = false
            setContent {
                LogDateTheme(dynamicColor = false, darkTheme = false) {
                    MainEditorContent(
                        uiState =
                            BlocksUiState(
                                blocks = blocks,
                                expandedBlockId = selected,
                                availableJournals = emptyList(),
                                selectedJournalIds = emptyList(),
                                onBlockFocused = { selected = it },
                                onJournalSelectionChanged = {},
                                onUpdateBlock = {},
                                onCreateBlock = { _, id ->
                                    TextBlockUiState(id = id).also {
                                        blocks = blocks + it
                                        selected = id
                                    }
                                },
                                onDeleteBlock = {},
                            ),
                        shouldReturnToPickerOnBack = false,
                        onDismissExpanded = {},
                    )
                }
            }
            waitForIdle()
            onNodeWithTag("add_to_entry").performClick()
            mainClock.advanceTimeBy(1000)
            waitForIdle()
            screenshot("entry-add-before.png")
            onNodeWithTag("add_memory_TEXT").performClick()
            mainClock.advanceTimeBy(240)
            waitForIdle()
            val card = onNodeWithTag("memory_block_${blocks.last().id}")
            val middle = card.getUnclippedBoundsInRoot()
            val middlePixels = onRoot().captureToImage().toPixelMap()
            val middleRow = middle.top.value.toInt() + 60
            val middleWidth =
                (0 until middlePixels.width).count {
                    middlePixels[it, middleRow].toArgb() !=
                        middlePixels[0, middleRow].toArgb()
                }
            screenshot("entry-add-mid.png")
            mainClock.advanceTimeBy(1000)
            waitForIdle()
            val finish = card.getUnclippedBoundsInRoot()
            screenshot("entry-add-after.png")
            val finalPixels = onRoot().captureToImage().toPixelMap()
            val finalRow = finish.top.value.toInt() + 60
            val finalWidth = (0 until finalPixels.width).count { finalPixels[it, finalRow].toArgb() != finalPixels[0, finalRow].toArgb() }
            assertTrue(
                middleWidth in 1 until finalWidth - 8,
                "The additional block popped to full width: $middleWidth -> $finalWidth ($middle -> $finish)",
            )
        }

    @Test
    fun `clearing a lone empty block morphs back through the supplied editor scope`() =
        runSkikoComposeUiTest(size = Size(390f, 780f)) {
            val block = TextBlockUiState()
            var blocks by mutableStateOf<List<EntryBlockUiState>>(listOf(block))
            var active = false
            mainClock.autoAdvance = false
            setContent {
                LogDateTheme(dynamicColor = false, darkTheme = false) {
                    SharedTransitionLayout {
                        active = isTransitionActive
                        CompositionLocalProvider(LocalSharedTransitionScope provides this) {
                            MainEditorContent(
                                uiState =
                                    BlocksUiState(
                                        blocks = blocks,
                                        expandedBlockId = block.id,
                                        availableJournals = emptyList(),
                                        selectedJournalIds = emptyList(),
                                        onBlockFocused = {},
                                        onJournalSelectionChanged = {},
                                        onUpdateBlock = {},
                                        onCreateBlock = { _, _ -> block },
                                        onDeleteBlock = {},
                                    ),
                                shouldReturnToPickerOnBack = blocks.isNotEmpty(),
                                onDismissExpanded = {},
                            )
                        }
                    }
                }
            }
            mainClock.advanceTimeBy(1000)
            waitForIdle()
            runOnIdle { blocks = emptyList() }
            mainClock.advanceTimeBy(100)
            waitForIdle()
            screenshot("entry-return-mid.png")
            assertTrue(active, "The outgoing empty card must remain paired with its picker tile")
            mainClock.advanceTimeBy(1000)
            waitForIdle()
            assertTrue(!active, "Return morph must settle")
        }

    @Test
    fun `keyboard resizing keeps the writing card height stable`() =
        runSkikoComposeUiTest(size = Size(390f, 780f)) {
            var height by mutableStateOf(780.dp)
            val block = TextBlockUiState(content = "A memory.")
            setContent {
                LogDateTheme(dynamicColor = false) {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.requiredHeight(height).testTag("viewport")) {
                            MainEditorContent(
                                uiState =
                                    BlocksUiState(
                                        blocks = listOf(block),
                                        expandedBlockId = block.id,
                                        availableJournals = emptyList(),
                                        selectedJournalIds = emptyList(),
                                        onBlockFocused = {},
                                        onJournalSelectionChanged = {},
                                        onUpdateBlock = {},
                                        onCreateBlock = { _, _ -> block },
                                        onDeleteBlock = {},
                                    ),
                                shouldReturnToPickerOnBack = false,
                                onDismissExpanded = {},
                            )
                        }
                    }
                }
            }
            waitForIdle()
            val before = onNodeWithTag("memory_block_${block.id}").getUnclippedBoundsInRoot()
            runOnIdle { height = 460.dp }
            waitForIdle()
            val after = onNodeWithTag("memory_block_${block.id}").getUnclippedBoundsInRoot()
            val viewport = onNodeWithTag("viewport").getUnclippedBoundsInRoot()
            assertEquals(460.dp, viewport.bottom - viewport.top)
            assertEquals(
                (before.bottom - before.top),
                (after.bottom - after.top),
                message = "IME resizing must not shrink the card and move every following block",
            )
        }
}

@OptIn(ExperimentalTestApi::class)
private fun androidx.compose.ui.test.ComposeUiTest.screenshot(name: String) {
    val directory = File(System.getProperty("logdate.review.screenshots", "build/reports/editor-screenshots/motion"))
    directory.mkdirs()
    File(directory, name).writeBytes(requireNotNull(Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).encodeToData()).bytes)
}
