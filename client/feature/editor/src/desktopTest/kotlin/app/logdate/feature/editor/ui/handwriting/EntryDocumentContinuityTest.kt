package app.logdate.feature.editor.ui.handwriting

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import app.logdate.feature.editor.ui.EditorBlockContinuity
import app.logdate.feature.editor.ui.LocalEditorBlockContinuity
import app.logdate.feature.editor.ui.editor.TextBlockUiState
import app.logdate.feature.editor.ui.rememberRetainedEditorBlocks
import app.logdate.feature.editor.ui.state.BlocksUiState
import app.logdate.ui.theme.LogDateTheme
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class EntryDocumentContinuityTest {
    @Test
    fun multilineInputUsesItsActualInlineHeightAfterDoneAndRemainsReachable() =
        runDesktopComposeUiTest(width = 360, height = 800) {
            val memory = TextBlockUiState(content = "A short beginning")
            val page = EntryPageOverviewState(false)
            setContent { EntryPageStudy(initialBlocks = listOf(memory), overviewStateOverride = page) }
            onNodeWithTag("memory_block_${memory.id}").performScrollTo().performClick()
            onNodeWithTag("editor_text_input").performTextInput((1..45).joinToString("\n", prefix = "\n") { "Remembered line $it" })
            onNodeWithContentDescription("Return to entry").performClick()
            waitForIdle()
            val block = onNodeWithTag("memory_block_${memory.id}")
            assertTrue(block.fetchSemanticsNode().size.height > 800, "The same input must measure its full multiline source height")
            onNodeWithTag("entry_page_creation").performScrollTo()
            assertTrue(page.scroll.value > 0, "The real scroll ancestor must reveal the creation footer after growing input")
            block.performScrollTo().performClick()
            onNodeWithTag("entry_mode_focus").assertIsDisplayed()
        }

    @Test
    fun resizedViewportRetargetsFromTheCurrentSurfaceBounds() =
        runDesktopComposeUiTest(width = 1000, height = 800) {
            val memory = TextBlockUiState(content = "One surface while the viewport changes")
            var width by mutableStateOf(360)
            var focused by mutableStateOf(false)
            var progress by mutableFloatStateOf(0f)
            val page = EntryPageOverviewState(false)
            setContent {
                val ui =
                    BlocksUiState(
                        blocks = listOf(memory),
                        expandedBlockId = if (focused) memory.id else null,
                        availableJournals = emptyList(),
                        selectedJournalIds = emptyList(),
                        onBlockFocused = {},
                        onJournalSelectionChanged = {},
                        onUpdateBlock = {},
                        onCreateBlock = { _, _ -> memory },
                        onDeleteBlock = {},
                    )
                val host = rememberRetainedEditorBlocks(ui) { _, _ -> }
                CompositionLocalProvider(
                    LocalEditorBlockContinuity provides
                        EditorBlockContinuity(host, if (focused) memory.id else null, mapOf(memory.id to progress)),
                ) {
                    LogDateTheme(dynamicColor = false) {
                        Box(Modifier.fillMaxSize()) {
                            Box(Modifier.size(width.dp, 800.dp).testTag("resizing_viewport")) {
                                EntryPageOverview(ui, emptyList(), page)
                            }
                        }
                    }
                }
            }
            waitForIdle()
            // Hold the real document at the seekable transition's midpoint while layout remeasures.
            // MainEditor's interruption test separately exercises the running transition clock.
            runOnIdle {
                focused = true
                progress = 0.5f
            }
            waitForIdle()
            val before = onNodeWithTag("memory_block_${memory.id}").getUnclippedBoundsInRoot()
            runOnIdle { width = 600 }
            waitForIdle()
            assertEquals(600, onNodeWithTag("resizing_viewport").fetchSemanticsNode().size.width)
            val retargeted = onNodeWithTag("memory_block_${memory.id}").getUnclippedBoundsInRoot()
            assertEquals(before.left.value, retargeted.left.value, 1f, "A new endpoint must not teleport the current surface")
            assertEquals(
                (before.right - before.left).value,
                (retargeted.right - retargeted.left).value,
                1f,
                "Resize must retarget from the width already on screen",
            )
            runOnIdle { progress = 1f }
            waitForIdle()
            assertEquals(600.dp, onNodeWithTag("memory_block_${memory.id}").getUnclippedBoundsInRoot().let { it.right - it.left })
        }

    @Test(timeout = 60_000)
    fun targetFlipRetainsTheEditableContentBoundsAtTheSameProgress() =
        runDesktopComposeUiTest(width = 360, height = 800) {
            val memory = TextBlockUiState(content = "A real line of retained text")
            val page = EntryPageOverviewState(false)
            var focused by mutableStateOf(false)
            var progress by mutableFloatStateOf(0f)
            setContent { FixedProgressDocument(memory, focused, progress, page) }
            waitForIdle()
            runOnIdle {
                focused = true
                progress = 0.5f
            }
            waitForIdle()
            val before = onNodeWithTag("editor_text_input").getUnclippedBoundsInRoot()
            runOnIdle { focused = false }
            waitForIdle()
            val reversed = onNodeWithTag("editor_text_input").getUnclippedBoundsInRoot()
            assertEquals(before.left.value, reversed.left.value, 1f)
            assertEquals(before.top.value, reversed.top.value, 1f, "Done must not instantly remove safe/header spacing")
            assertEquals(before.right.value, reversed.right.value, 1f, "The inline menu reserve must approach with the same progress")
            assertEquals(before.bottom.value, reversed.bottom.value, 1f, "The actual editable target must follow its retained surface")
        }

    @Test(timeout = 60_000)
    fun platformRefocusDoesNotReplaceTheOriginalOverviewScrollBookmark() =
        runDesktopComposeUiTest(width = 360, height = 800) {
            val memories = (1..8).map { TextBlockUiState(content = "Retained memory $it") }
            val page = EntryPageOverviewState(false)
            lateinit var focusManager: FocusManager
            setContent {
                focusManager = LocalFocusManager.current
                EntryPageStudy(initialBlocks = memories, overviewStateOverride = page)
            }
            val last = onNodeWithTag("memory_block_${memories.last().id}")
            runOnIdle { runBlocking { page.scroll.scrollTo(page.scroll.maxValue) } }
            last.assertIsDisplayed()
            last.performSemanticsAction(SemanticsActions.OnClick) { it() }
            waitForIdle()
            val original = assertNotNull(page.focusScrollBookmark)
            assertTrue(original > 0)
            runOnIdle {
                focusManager.clearFocus()
                page.scroll.dispatchRawDelta(-16f)
            }
            onNodeWithTag("editor_text_input").performClick()
            waitForIdle()
            assertEquals(original, page.focusScrollBookmark, "A focused callback must retain the original overview bookmark")
            onNodeWithContentDescription("Return to entry").performClick()
            waitForIdle()
            assertEquals(original, page.scroll.value)
        }

    @Test
    fun earlierBrowserDoesNotExposeTheCoveredDocumentControls() =
        runDesktopComposeUiTest(width = 360, height = 800) {
            val page = EntryPageOverviewState(false)
            setContent { EntryPageStudy(overviewStateOverride = page) }
            onNodeWithContentDescription("Browse earlier today").assertExists()
            runOnIdle { page.earlierExpanded = true }
            onNodeWithTag("entry_day_browser").assertIsDisplayed()
            onNodeWithContentDescription("Browse earlier today").assertDoesNotExist()
        }
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun FixedProgressDocument(
    memory: TextBlockUiState,
    focused: Boolean,
    progress: Float,
    page: EntryPageOverviewState,
) {
    val ui =
        BlocksUiState(
            blocks = listOf(memory),
            expandedBlockId = if (focused) memory.id else null,
            availableJournals = emptyList(),
            selectedJournalIds = emptyList(),
            onBlockFocused = {},
            onJournalSelectionChanged = {},
            onUpdateBlock = {},
            onCreateBlock = { _, _ -> memory },
            onDeleteBlock = {},
        )
    val host = rememberRetainedEditorBlocks(ui) { _, _ -> }
    CompositionLocalProvider(
        LocalEditorBlockContinuity provides EditorBlockContinuity(host, if (focused) memory.id else null, mapOf(memory.id to progress)),
    ) {
        LogDateTheme(dynamicColor = false) { EntryPageOverview(ui, emptyList(), page) }
    }
}
