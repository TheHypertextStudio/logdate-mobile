package app.logdate.feature.editor.ui.handwriting

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.logdate.client.datastore.EntryStartMode
import app.logdate.client.repository.journals.JournalNote
import app.logdate.feature.editor.ui.EditorBlockContinuity
import app.logdate.feature.editor.ui.LocalEditorBlockContinuity
import app.logdate.feature.editor.ui.editor.EditorDraftReveal
import app.logdate.feature.editor.ui.editor.EditorState
import app.logdate.feature.editor.ui.editor.TextBlockUiState
import app.logdate.feature.editor.ui.rememberRetainedEditorBlocks
import app.logdate.feature.editor.ui.state.rememberBlocksUiState
import app.logdate.ui.foldable.FoldablePosture
import app.logdate.ui.foldable.provideFoldableLayoutInfo
import app.logdate.ui.theme.LogDateTheme
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class LoadedDraftRevealTest {
    @Test
    fun acceptedDraftBeginsInsideUnobscuredViewportWithoutFocusingOrReplacingInput() =
        runDesktopComposeUiTest(width = 360, height = 800) {
            val block = TextBlockUiState(content = "Current retained draft")
            val request = EditorDraftReveal(block.id, Uuid.random())
            val page = EntryPageOverviewState(false)
            var editor by mutableStateOf(EditorState())
            var focuses = 0
            var consumes = 0
            setContent {
                document(editor, page, { focuses++ }) {
                    if (editor.pendingDraftReveal != it) {
                        false
                    } else {
                        consumes++
                        editor = editor.copy(pendingDraftReveal = null)
                        true
                    }
                }
            }
            runOnIdle {
                editor =
                    EditorState(
                        blocks = listOf(block) + (1..6).map { TextBlockUiState(content = "Later current block $it") },
                        creationInputTokens = mapOf(block.id to request.inputToken),
                        pendingDraftReveal = request,
                    )
            }
            waitForIdle()
            val memory = onNodeWithTag("memory_block_${block.id}")
            val bounds = memory.getUnclippedBoundsInRoot()
            val dock = onNodeWithTag("entry_add_memory").getUnclippedBoundsInRoot()
            val header = onNodeWithTag("test_header").getUnclippedBoundsInRoot()
            val heading = onNodeWithText("Now").getUnclippedBoundsInRoot()
            assertTrue(
                heading.top.value in (header.bottom.value + 32f)..(header.bottom.value + 34f),
                "Reveal must keep the heading below measured chrome: $heading / $header",
            )
            assertTrue(bounds.top >= heading.bottom + 12.dp, "The heading and first memory stay together")
            assertTrue(bounds.top.value + 120f <= dock.top.value, "Current draft must be visible above Add to entry: $bounds / $dock")
            onAllNodesWithTag("editor_text_input").onFirst().assertIsNotFocused()
            assertEquals(0, focuses)
            assertEquals(1, consumes)
            assertNull(editor.expandedBlockId)
            assertEquals(block, editor.blocks.first())
            val inputIdentity = onAllNodesWithTag("editor_text_input").onFirst().fetchSemanticsNode().id
            val revealed = page.scroll.value
            assertTrue(revealed > 0)
            runOnIdle { runBlocking { page.scroll.scrollTo(0) } }
            runOnIdle { editor = editor.copy(contentRevision = 2, blocks = listOf(block.copy(content = "Current retained draft updated"))) }
            waitForIdle()
            assertEquals(0, page.scroll.value, "Consumed reveal must not fight later scrolling or input updates")
            assertEquals(1, consumes)
            onAllNodesWithTag("editor_text_input").assertCountEquals(0)
            runOnIdle { runBlocking { page.scroll.scrollTo(revealed) } }
            waitForIdle()
            assertEquals(inputIdentity, onAllNodesWithTag("editor_text_input").onFirst().fetchSemanticsNode().id)
        }

    @Test
    fun overviewWithoutAnAcceptedLoadPreservesItsExistingScroll() =
        runDesktopComposeUiTest(width = 360, height = 800) {
            val page = EntryPageOverviewState(false)
            val block = TextBlockUiState(content = "Ordinary existing content")
            setContent { document(EditorState(blocks = listOf(block)), page, {}) { error("No load to reveal") } }
            waitForIdle()
            assertEquals(0, page.scroll.value)
        }

    @Test
    fun acceptedAlreadyVisibleDraftDoesNotMoveTheViewport() =
        runDesktopComposeUiTest(width = 360, height = 800) {
            val block = TextBlockUiState(content = "Already visible")
            val request = EditorDraftReveal(block.id, Uuid.random())
            var editor by mutableStateOf(EditorState(blocks = listOf(block), pendingDraftReveal = request))
            val page = EntryPageOverviewState(false)
            var consumes = 0
            setContent {
                document(editor, page, {}, earlierCount = 0, consume = {
                    consumes++
                    editor = editor.copy(pendingDraftReveal = null)
                    true
                })
            }
            waitForIdle()
            assertEquals(1, consumes)
            assertEquals(0, page.scroll.value)
            onAllNodesWithTag("editor_text_input").onFirst().assertIsNotFocused()
        }

    @Test
    fun tallerMeasuredHeaderStillLeavesLoadedDraftVisible() =
        runDesktopComposeUiTest(width = 360, height = 800) {
            val block = TextBlockUiState(content = "Read below a larger header")
            val request = EditorDraftReveal(block.id, Uuid.random())
            var editor by mutableStateOf(
                EditorState(
                    blocks =
                        listOf(block) +
                            (1..6).map {
                                TextBlockUiState(content = "Trailing $it")
                            },
                    pendingDraftReveal = request,
                ),
            )
            val page = EntryPageOverviewState(false)
            setContent {
                document(editor, page, {}, headerHeight = 160.dp, consume = {
                    editor = editor.copy(pendingDraftReveal = null)
                    true
                })
            }
            waitForIdle()
            val header = onNodeWithTag("test_header").getUnclippedBoundsInRoot()
            val memory = onNodeWithTag("memory_block_${block.id}").getUnclippedBoundsInRoot()
            val dock = onNodeWithTag("entry_add_memory").getUnclippedBoundsInRoot()
            val heading = onNodeWithText("Now").getUnclippedBoundsInRoot()
            assertTrue(heading.top.value >= header.bottom.value + 16f, "Heading must also clear taller chrome: $heading / $header")
            assertTrue(memory.top.value >= header.bottom.value + 16f, "Reveal must use actual header geometry: $memory / $header")
            assertTrue(memory.top.value + 120f <= dock.top.value)
            onAllNodesWithTag("editor_text_input").onFirst().assertIsNotFocused()
        }

    @Test
    fun captureDragCancelsLatestPendingLoadEvenBeforeRenderedStateReceivesIt() =
        runDesktopComposeUiTest(width = 360, height = 800) {
            val block = TextBlockUiState(content = "Rendered before the load completes")
            val rendered = EditorState(blocks = listOf(block))
            val page = EntryPageOverviewState(false)
            var latestRequest: EditorDraftReveal? = null
            var cancellations = 0
            setContent {
                document(rendered, page, {}, cancel = {
                    cancellations++
                    latestRequest = null
                }) { error("No rendered request to consume") }
            }
            runOnIdle {
                latestRequest = EditorDraftReveal(block.id, Uuid.random())
                page.captureDrag(-20f)
            }
            assertNull(latestRequest, "User intent must cancel latest model state, not an older rendered request")
            assertEquals(1, cancellations)
        }

    @Test
    fun bookPostureDoesNotScrollWritingBecauseOfLongContextInTheOtherPane() =
        runDesktopComposeUiTest(width = 1000, height = 800) {
            val block = TextBlockUiState(content = "Current writing beside context")
            val request = EditorDraftReveal(block.id, Uuid.random())
            var editor by mutableStateOf(EditorState(blocks = listOf(block), pendingDraftReveal = request))
            val page = EntryPageOverviewState(false)
            var consumes = 0
            setContent {
                provideFoldableLayoutInfo(foldFixture(FoldablePosture.Book, 1000, 800)) {
                    document(editor, page, {}) {
                        consumes++
                        editor = editor.copy(pendingDraftReveal = null)
                        true
                    }
                }
            }
            waitForIdle()
            val memory = onNodeWithTag("memory_block_${block.id}").getUnclippedBoundsInRoot()
            assertTrue(memory.left.value >= 512f)
            assertTrue(memory.top.value >= 112f && memory.top.value + 120f < 680f)
            assertEquals(0, page.scroll.value)
            assertEquals(1, consumes)
            onAllNodesWithTag("editor_text_input").onFirst().assertIsNotFocused()
        }

    @Test
    fun landscapeRevealShowsTheBeginningWithoutShrinkingLongWriting() =
        runDesktopComposeUiTest(width = 1280, height = 500) {
            val block = TextBlockUiState(content = (1..50).joinToString("\n") { "Retained line $it" })
            val request = EditorDraftReveal(block.id, Uuid.random())
            var editor by mutableStateOf(EditorState(blocks = listOf(block), pendingDraftReveal = request))
            val page = EntryPageOverviewState(false)
            setContent {
                document(editor, page, {}) {
                    editor = editor.copy(pendingDraftReveal = null)
                    true
                }
            }
            waitForIdle()
            val memory = onNodeWithTag("memory_block_${block.id}").getUnclippedBoundsInRoot()
            val header = onNodeWithTag("test_header").getUnclippedBoundsInRoot()
            val dock = onNodeWithTag("entry_add_memory").getUnclippedBoundsInRoot()
            val heading = onNodeWithText("Now").getUnclippedBoundsInRoot()
            assertTrue(heading.top.value >= header.bottom.value + 16f, "Landscape heading must clear chrome: $heading / $header")
            assertTrue(memory.top.value >= header.bottom.value + 16f)
            assertTrue(memory.top.value + 120f <= dock.top.value)
            assertTrue((memory.bottom - memory.top).value > 500f, "Long writing must keep its real document height")
            onAllNodesWithTag("editor_text_input").onFirst().assertIsNotFocused()
        }

    @Test
    fun phoneRevealsHeadingAndMemoryTogether() = verifyHeadingReveal(360, 800)

    @Test
    fun foldableSizedWindowRevealsHeadingAndMemoryTogether() = verifyHeadingReveal(1000, 800)

    @Test
    fun landscapeTabletRevealsHeadingAndMemoryTogether() = verifyHeadingReveal(1280, 800)

    @Test
    fun largeTextRevealsTheMeasuredHeadingWithoutFocusingInput() = verifyHeadingReveal(360, 800, fontScale = 2f)

    private fun verifyHeadingReveal(
        width: Int,
        height: Int,
        fontScale: Float = 1f,
    ) = runDesktopComposeUiTest(width = width, height = height) {
        val block = TextBlockUiState(content = "Retained draft stays beneath Now")
        val request = EditorDraftReveal(block.id, Uuid.random())
        var editor by mutableStateOf(
            EditorState(
                blocks = listOf(block) + (1..6).map { TextBlockUiState(content = "Following retained memory $it") },
                pendingDraftReveal = request,
            ),
        )
        val page = EntryPageOverviewState(false)
        var focuses = 0
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                document(editor, page, { focuses++ }, actualHeader = true) {
                    editor = editor.copy(pendingDraftReveal = null)
                    true
                }
            }
        }
        waitForIdle()
        val heading = onNodeWithText("Now").getUnclippedBoundsInRoot()
        val header = onNodeWithTag("entry_mode_overview").getUnclippedBoundsInRoot()
        val memory = onNodeWithTag("memory_block_${block.id}").getUnclippedBoundsInRoot()
        val dock = onNodeWithTag("entry_add_memory").getUnclippedBoundsInRoot()
        assertTrue(heading.top.value >= header.bottom.value + 16f, "Heading is hidden: $heading / $header")
        assertTrue(memory.top >= heading.bottom + 12.dp)
        assertTrue(memory.top.value + 120f <= dock.top.value, "First memory must also remain visible")
        onAllNodesWithTag("editor_text_input").onFirst().assertIsNotFocused()
        assertEquals(0, focuses)
        assertNull(editor.expandedBlockId)
        assertEquals(block, editor.blocks.first())
        assertFalse(editor.isRecordingAudio)
        val output = File("build/reports/editor-draft-reveal/${width}x$height-font$fontScale.png")
        output.parentFile.mkdirs()
        output.writeBytes(requireNotNull(Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).encodeToData()).bytes)
    }

    @Test
    fun aNewLoadOfTheSameBlockConsumesItsOwnRequestWithoutRemountingTheInput() =
        runDesktopComposeUiTest(width = 360, height = 800) {
            val block = TextBlockUiState(content = "Same retained identity")
            var editor by mutableStateOf(
                EditorState(blocks = listOf(block), pendingDraftReveal = EditorDraftReveal(block.id, Uuid.random())),
            )
            val page = EntryPageOverviewState(false)
            var consumes = 0
            setContent {
                document(editor, page, {}, earlierCount = 0, consume = {
                    consumes++
                    editor = editor.copy(pendingDraftReveal = null)
                    true
                })
            }
            waitForIdle()
            val original = onAllNodesWithTag("editor_text_input").onFirst().fetchSemanticsNode().id
            runOnIdle { editor = editor.copy(pendingDraftReveal = EditorDraftReveal(block.id, Uuid.random())) }
            waitForIdle()
            assertEquals(2, consumes)
            assertEquals(original, onAllNodesWithTag("editor_text_input").onFirst().fetchSemanticsNode().id)
            assertEquals(0, page.scroll.value)
        }

    @Composable
    private fun document(
        editor: EditorState,
        page: EntryPageOverviewState,
        onFocus: () -> Unit,
        earlierCount: Int = 12,
        headerHeight: Dp = 56.dp,
        actualHeader: Boolean = false,
        cancel: () -> Unit = {},
        consume: (EditorDraftReveal) -> Boolean,
    ) {
        val ui =
            rememberBlocksUiState(
                editor,
                onUpdateBlock = {},
                onFocusBlock = { onFocus() },
                onCreateBlock = { _, _ -> error("No creation during reveal") },
                onDeleteBlock = {},
                onUpdateJournalSelection = {},
                onConsumeDraftReveal = consume,
                onCancelDraftReveal = cancel,
            )
        val host = rememberRetainedEditorBlocks(ui) { _, _ -> }
        CompositionLocalProvider(LocalEditorBlockContinuity provides EditorBlockContinuity(host, null, emptyMap())) {
            LogDateTheme(dynamicColor = false) {
                Box(Modifier.fillMaxSize()) {
                    EntryPageChrome(
                        editor,
                        page,
                        EntryStartMode.TEXT,
                        {},
                        {},
                        {},
                        safeInsets = WindowInsets(top = 24, bottom = 24),
                        header = {
                            if (actualHeader) {
                                EntryJournalHeader(
                                    journal = null,
                                    journalSelected = false,
                                    availableJournals = emptyList(),
                                    onSelectJournal = null,
                                    onJournalSelection = {},
                                    onExit = {},
                                    onSave = {},
                                    saveEnabled = true,
                                    saveInProgress = false,
                                    onShowDrafts = null,
                                    draftCount = 0,
                                    floating = true,
                                )
                            } else {
                                Box(Modifier.fillMaxWidth().height(headerHeight).testTag("test_header"))
                            }
                        },
                    ) {
                        EntryPageOverview(
                            ui,
                            (1..earlierCount).map {
                                JournalNote.Text(
                                    content = "Earlier context $it",
                                    creationTimestamp = Instant.parse("2026-10-26T15:12:00Z"),
                                    lastUpdated = Instant.parse("2026-10-26T15:12:00Z"),
                                )
                            },
                            page,
                        )
                    }
                }
            }
        }
    }
}
