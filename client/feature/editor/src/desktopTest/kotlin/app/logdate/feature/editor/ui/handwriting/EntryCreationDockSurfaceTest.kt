package app.logdate.feature.editor.ui.handwriting

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import app.logdate.client.datastore.EntryStartMode
import app.logdate.client.repository.journals.JournalNote
import app.logdate.feature.editor.ui.EditorBlockContinuity
import app.logdate.feature.editor.ui.LocalEditorBlockContinuity
import app.logdate.feature.editor.ui.editor.CameraBlockUiState
import app.logdate.feature.editor.ui.editor.EditorState
import app.logdate.feature.editor.ui.editor.TextBlockUiState
import app.logdate.feature.editor.ui.rememberRetainedEditorBlocks
import app.logdate.feature.editor.ui.state.EditorBlockPresentation
import app.logdate.feature.editor.ui.state.rememberBlocksUiState
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class EntryCreationDockSurfaceTest {
    private val canvas = Color.Blue
    private val modes = Color.Green
    private val add = Color.Red
    private val addContent = Color.Yellow
    private val colors =
        lightColorScheme(
            surfaceDim = canvas,
            surface = Color.White,
            surfaceContainerHigh = modes,
            secondaryContainer = add,
            onSecondaryContainer = addContent,
        )

    @Test
    fun addUsesItsTonalContainerAndContentColors() =
        runDesktopComposeUiTest(width = 360, height = 800) {
            val block = TextBlockUiState(content = "A memory")
            setContent {
                MaterialTheme(colorScheme = colors) {
                    EntryPageChrome(
                        EditorState(blocks = listOf(block)),
                        EntryPageOverviewState(false),
                        EntryStartMode.TEXT,
                        {},
                        {},
                        {},
                        header = {},
                    ) {}
                }
            }
            val dock = onNodeWithTag("entry_creation_actions").getUnclippedBoundsInRoot()
            val pixels = onRoot().captureToImage().toPixelMap()
            assertEquals(
                add,
                pixels[(dock.left.value + 4).toInt(), ((dock.top.value + dock.bottom.value) / 2).toInt()],
                "Add must use secondaryContainer",
            )
            val label = onNodeWithText("Add to entry").captureToImage().toPixelMap()
            assertTrue(
                (0 until label.width).any { x ->
                    (0 until label.height).any { y ->
                        label[x, y].red > .9f &&
                            label[x, y].green > .9f &&
                            label[x, y].blue < .1f
                    }
                },
                "Add must inherit onSecondaryContainer rather than fixed primary text",
            )
        }

    @Test
    fun swappingModesForAddKeepsOneSurfaceHeightAndInterpolatesColorDuringReversal() =
        runDesktopComposeUiTest(width = 360, height = 800) {
            val block = TextBlockUiState()
            var editor by mutableStateOf(EditorState(blocks = listOf(block), expandedBlockId = block.id))
            setContent {
                MaterialTheme(colorScheme = colors) {
                    EntryPageChrome(editor, EntryPageOverviewState(false), EntryStartMode.TEXT, {}, {}, {}, header = {}) {}
                }
            }
            val surface = onNodeWithTag("entry_creation_actions")
            val identity = surface.fetchSemanticsNode().id
            val initial = surface.getUnclippedBoundsInRoot()
            mainClock.autoAdvance = false
            runOnIdle { editor = editor.copy(expandedBlockId = null) }
            mainClock.advanceTimeByFrame()
            mainClock.advanceTimeBy(96)
            val middle = surface.getUnclippedBoundsInRoot()
            assertEquals(initial.bottom - initial.top, middle.bottom - middle.top, "A role swap must not collapse the shared surface")
            val color =
                onRoot().captureToImage().toPixelMap()[
                    (middle.left.value + 4).toInt(), (
                        (middle.top.value + middle.bottom.value) /
                            2
                    ).toInt(),
                ]
            assertTrue(color.red > .05f && color.green > .05f, "Container color must interpolate between mode and Add roles: $color")
            runOnIdle { editor = editor.copy(expandedBlockId = block.id) }
            mainClock.advanceTimeBy(48)
            assertEquals(identity, surface.fetchSemanticsNode().id)
            assertEquals(initial.bottom - initial.top, surface.getUnclippedBoundsInRoot().let { it.bottom - it.top })
            mainClock.advanceTimeBy(1200)
            val end = surface.getUnclippedBoundsInRoot()
            assertEquals(
                modes,
                onRoot().captureToImage().toPixelMap()[(end.left.value + 4).toInt(), ((end.top.value + end.bottom.value) / 2).toInt()],
            )
            mainClock.autoAdvance = true
        }

    @Test
    fun scrollingMemoryCannotPaintOrReceiveTouchesInTheDockClearanceOrBelowIt() =
        runDesktopComposeUiTest(width = 360, height = 800) {
            val block = TextBlockUiState(content = (1..70).joinToString("\n") { "Retained line $it" })
            var focusRequests = 0
            val page = EntryPageOverviewState(false)
            setContent {
                val ui = rememberBlocksUiState(EditorState(blocks = listOf(block)), {}, { focusRequests++ }, { _, _ -> block }, {}, {})
                val host = rememberRetainedEditorBlocks(ui) { _, _ -> }
                CompositionLocalProvider(LocalEditorBlockContinuity provides EditorBlockContinuity(host, null, emptyMap())) {
                    MaterialTheme(colorScheme = colors) {
                        EntryPageChrome(EditorState(blocks = listOf(block)), page, EntryStartMode.TEXT, {}, {}, {}, header = {}) {
                            EntryPageOverview(ui, emptyList(), page)
                        }
                    }
                }
            }
            val input = onNodeWithTag("editor_text_input")
            val identity = input.fetchSemanticsNode().id
            val dock = onNodeWithTag("entry_creation_actions").getUnclippedBoundsInRoot()
            val pixels = onRoot().captureToImage().toPixelMap()
            assertEquals(canvas, pixels[180, (dock.top.value - 8).toInt()], "A readable canvas gap must separate memory and dock")
            assertEquals(
                canvas,
                pixels[180, (dock.bottom.value + 8).toInt()],
                "A scrolling memory cannot create a lower rectangular backing strip",
            )
            onRoot().performTouchInput { click(Offset(180f, 790f)) }
            assertEquals(0, focusRequests, "Clipped content must not receive touches behind the dock")
            input.assertIsNotFocused()
            assertEquals(identity, input.fetchSemanticsNode().id)
        }

    @Test
    fun focusedCameraOwnsItsControlsWithoutVisibleOrInteractiveEntryModes() =
        runDesktopComposeUiTest(width = 360, height = 800) {
            val camera = CameraBlockUiState()
            var modeTaps = 0
            setContent {
                MaterialTheme {
                    EntryPageChrome(
                        EditorState(
                            blocks = listOf(camera),
                            expandedBlockId = camera.id,
                        ),
                        EntryPageOverviewState(false),
                        EntryStartMode.TEXT,
                        {
                        },
                        {},
                        { modeTaps++ },
                        header = {},
                    ) {
                        Box(Modifier.fillMaxSize())
                    }
                }
            }
            onNodeWithTag("entry_capture_tools").assertIsNotDisplayed()
            onNodeWithContentDescription("Write").assertIsNotDisplayed()
            onRoot().performTouchInput { click(Offset(40f, 760f)) }
            assertEquals(0, modeTaps)
        }

    @Test
    fun earlierMemoryBehindTheDockLeavesAccessibilityWithoutReplacingItsContent() =
        runDesktopComposeUiTest(width = 360, height = 800) {
            val time = Instant.parse("2026-10-30T12:00:00Z")
            val notes =
                (1..7).map {
                    JournalNote.Text(
                        content = "Earlier retained memory $it",
                        creationTimestamp = time,
                        lastUpdated = time,
                    )
                }
            val target = notes[5]
            val block = TextBlockUiState(content = (1..70).joinToString("\n") { "Current line $it" })
            val page = EntryPageOverviewState(false)
            setContent {
                val ui = rememberBlocksUiState(EditorState(blocks = listOf(block)), {}, {}, { _, _ -> block }, {}, {})
                val host = rememberRetainedEditorBlocks(ui) { _, _ -> }
                CompositionLocalProvider(LocalEditorBlockContinuity provides EditorBlockContinuity(host, null, emptyMap())) {
                    MaterialTheme(colorScheme = colors) {
                        EntryPageChrome(EditorState(blocks = listOf(block)), page, EntryStartMode.TEXT, {}, {}, {}, header = {}) {
                            EntryPageOverview(ui, notes, page)
                        }
                    }
                }
            }
            val content = onNodeWithText(target.content)
            val original = content.fetchSemanticsNode().id
            val top = content.getUnclippedBoundsInRoot().top.value
            val dock = onNodeWithTag("entry_creation_actions").getUnclippedBoundsInRoot()
            runOnIdle { runBlocking { page.scroll.scrollTo((top - dock.top.value - 24).toInt().coerceAtLeast(0)) } }
            waitForIdle()
            val moved = content.getUnclippedBoundsInRoot()
            assertTrue(
                moved.top >= dock.top && moved.top < 800.dp,
                "Earlier content must be inside the invisible footer strip: $moved / $dock",
            )
            content.assert(hasAnyAncestor(SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility)))
            runOnIdle { runBlocking { page.scroll.scrollTo(page.scroll.value + 250) } }
            waitForIdle()
            content.assertIsDisplayed()
            assertEquals(original, content.fetchSemanticsNode().id)
        }

    @Test
    fun fullyClippedMemoryLeavesAccessibilityAndReturnsWithItsOriginalInputIdentity() =
        runDesktopComposeUiTest(width = 360, height = 800) {
            val first = TextBlockUiState(content = "Preceding memory")
            val next = TextBlockUiState(content = "Following memory remains the same owner")
            val tail = TextBlockUiState(content = (1..70).joinToString("\n") { "Later line $it" })
            var firstHeight by mutableStateOf(400.dp)
            val page = EntryPageOverviewState(false)
            setContent {
                val editor = EditorState(blocks = listOf(first, next, tail))
                val ui =
                    rememberBlocksUiState(
                        editor,
                        {},
                        {},
                        { _, _ -> first },
                        {},
                        {},
                        blockPresentations =
                            mapOf(
                                first.id to
                                    EditorBlockPresentation(inline = { Box(Modifier.fillMaxWidth().height(firstHeight)) }, focused = {}),
                            ),
                    )
                val host = rememberRetainedEditorBlocks(ui) { _, _ -> }
                CompositionLocalProvider(LocalEditorBlockContinuity provides EditorBlockContinuity(host, null, emptyMap())) {
                    MaterialTheme(colorScheme = colors) {
                        EntryPageChrome(
                            editor,
                            page,
                            EntryStartMode.TEXT,
                            {},
                            {},
                            {},
                            header = {},
                        ) { EntryPageOverview(ui, emptyList(), page) }
                    }
                }
            }
            val field = onNodeWithText(next.content)
            val original = field.fetchSemanticsNode().id
            val dock = onNodeWithTag("entry_creation_actions").getUnclippedBoundsInRoot()
            val placed = onNodeWithTag("memory_block_${next.id}").getUnclippedBoundsInRoot()
            runOnIdle { firstHeight += dock.top - 8.dp - placed.top }
            waitForIdle()
            val movedTop = runOnIdle { checkNotNull(page.blockPositions[next.id]) }
            val pinned = onNodeWithTag("entry_creation_actions").getUnclippedBoundsInRoot()
            assertTrue(
                movedTop >= pinned.top.value - 16 && movedTop < 800,
                "The owner must actually be fully behind the dock inside the window: $movedTop / $pinned",
            )
            field.assertDoesNotExist()
            runOnIdle { runBlocking { page.scroll.scrollTo(250) } }
            waitForIdle()
            field.assertIsDisplayed()
            assertEquals(original, field.fetchSemanticsNode().id, "Hiding a fully occluded memory must not remount its input")
        }
}
