package app.logdate.feature.editor.ui.handwriting

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import app.logdate.client.datastore.EntryStartMode
import app.logdate.client.repository.journals.JournalNote
import app.logdate.feature.editor.ui.MainEditorContent
import app.logdate.feature.editor.ui.blocks.MemoryCaptionField
import app.logdate.feature.editor.ui.editor.EditorState
import app.logdate.feature.editor.ui.editor.ImageBlockUiState
import app.logdate.feature.editor.ui.editor.TextBlockUiState
import app.logdate.feature.editor.ui.layout.LocalEntryContentBottom
import app.logdate.feature.editor.ui.layout.LocalEntryContentInsets
import app.logdate.feature.editor.ui.layout.clipEntryContentToSafeWindow
import app.logdate.feature.editor.ui.state.BlocksUiState
import app.logdate.ui.foldable.FoldableHingeBounds
import app.logdate.ui.foldable.FoldableHingeInfo
import app.logdate.ui.foldable.FoldableHingeOrientation
import app.logdate.ui.foldable.FoldableHingeState
import app.logdate.ui.foldable.FoldableLayoutInfo
import app.logdate.ui.foldable.FoldableOcclusionType
import app.logdate.ui.foldable.FoldablePosture
import app.logdate.ui.foldable.provideFoldableLayoutInfo
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

@OptIn(ExperimentalTestApi::class)
class EntryEdgeToEdgeTest {
    @Test
    fun partiallyVisibleReadingOwnerKeepsItsHeadingButHidesClippedActionUntilBoundaryMoves() =
        runDesktopComposeUiTest(width = 400, height = 800) {
            val boundary = mutableStateOf(544f)
            var clicks = 0
            setContent {
                CompositionLocalProvider(LocalEntryContentBottom provides boundary.value) {
                    Box(
                        Modifier
                            .absoluteOffset(y = 500.dp)
                            .size(150.dp)
                            .testTag("partial_owner")
                            .clipEntryContentToSafeWindow(),
                    ) {
                        Box(Modifier.size(24.dp).testTag("reading_heading").clipEntryContentToSafeWindow())
                        Box(
                            Modifier
                                .absoluteOffset(
                                    y = 60.dp,
                                ).size(48.dp)
                                .testTag("reading_action")
                                .clipEntryContentToSafeWindow()
                                .clickable { clicks++ },
                        )
                    }
                }
            }
            val heading = onNodeWithTag("reading_heading")
            val action = onNodeWithTag("reading_action")
            val identity = action.fetchSemanticsNode().id
            assertTrue(!heading.fetchSemanticsNode().config.contains(SemanticsProperties.HideFromAccessibility))
            assertTrue(!onNodeWithTag("partial_owner").fetchSemanticsNode().config.contains(SemanticsProperties.HideFromAccessibility))
            assertTrue(action.fetchSemanticsNode().config.contains(SemanticsProperties.HideFromAccessibility))
            onRoot().performTouchInput { click(Offset(24f, 580f)) }
            assertEquals(0, clicks)
            runOnIdle { boundary.value = 700f }
            assertTrue(!action.fetchSemanticsNode().config.contains(SemanticsProperties.HideFromAccessibility))
            assertEquals(identity, action.fetchSemanticsNode().id)
            onRoot().performTouchInput { click(Offset(24f, 580f)) }
            assertEquals(1, clicks)
        }

    @Test
    fun clippedControlCannotReceivePointerInputInSystemUi() =
        runDesktopComposeUiTest(width = 400, height = 800) {
            var clicks = 0
            val y = mutableStateOf(0.dp)
            setContent {
                CompositionLocalProvider(LocalEntryContentInsets provides WindowInsets(0, 24, 0, 48)) {
                    Box(
                        Modifier
                            .absoluteOffset(y = y.value)
                            .size(100.dp)
                            .testTag("clip_owner")
                            .clipEntryContentToSafeWindow(),
                    ) {
                        Box(Modifier.size(100.dp).clickable { clicks++ }.testTag("safe_control"))
                    }
                }
            }
            onRoot().performTouchInput { click(Offset(50f, 12f)) }
            assertEquals(0, clicks, "Hidden part of a control must not intercept system-bar input")
            onRoot().performTouchInput { click(Offset(50f, 50f)) }
            assertEquals(1, clicks, "Visible control remains tappable")
            val identity = onNodeWithTag("safe_control").fetchSemanticsNode().id
            runOnIdle { y.value = (-100).dp }
            assertTrue(
                onNodeWithTag("clip_owner").fetchSemanticsNode().config.contains(SemanticsProperties.HideFromAccessibility),
                "Fully clipped controls must be hidden from accessibility",
            )
            runOnIdle { y.value = 0.dp }
            assertEquals(identity, onNodeWithTag("safe_control").fetchSemanticsNode().id, "Scrolling must preserve control identity")
            assertTrue(!onNodeWithTag("clip_owner").fetchSemanticsNode().config.contains(SemanticsProperties.HideFromAccessibility))
        }

    @Test
    fun imageCaptionProtectsInkWithoutClippingImagePaint() =
        runDesktopComposeUiTest(width = 400, height = 800) {
            val image = ImageBlockUiState(uri = "test://photo", caption = "Keep this caption clear of system bars.")
            setContent {
                MaterialTheme(colorScheme = lightColorScheme(onSurface = Color.Green)) {
                    CompositionLocalProvider(LocalEntryContentInsets provides WindowInsets(0, 24, 0, 48)) {
                        Box(Modifier.size(400.dp, 800.dp).background(Color.Blue)) {
                            MemoryCaptionField(image, {}, {}, 0)
                        }
                    }
                }
            }
            val pixels = onRoot().captureToImage().toPixelMap()
            assertEquals(Color.Blue, pixels[200, 2], "Image paint must continue behind status UI")
            assertTrue(
                (24 until 80).any { y ->
                    (0 until 400).any { x ->
                        pixels[x, y].green > 0.5f && pixels[x, y].blue < 0.4f
                    }
                },
                "Caption oracle must see real ink below status UI",
            )
            assertTrue((0 until 24).none { y -> (0 until 400).any { x -> pixels[x, y].green > 0.5f && pixels[x, y].blue < 0.4f } })
        }

    @Test
    fun tallerBookReadingPaneKeepsItsLastMemoryAboveNavigation() =
        runDesktopComposeUiTest(width = 900, height = 800) {
            val block = TextBlockUiState(content = "A short current memory")
            val overview = EntryPageOverviewState(false)
            val now = Clock.System.now()
            val notes = (1..8).map { JournalNote.Text(content = "Earlier memory $it", creationTimestamp = now, lastUpdated = now) }
            val fold =
                FoldableLayoutInfo(
                    isFoldable = true,
                    posture = FoldablePosture.Book,
                    hinge =
                        FoldableHingeInfo(
                            FoldableHingeOrientation.Vertical,
                            FoldableHingeState.HalfOpened,
                            FoldableOcclusionType.None,
                            FoldableHingeBounds(450.dp, 0.dp, 450.dp, 800.dp, 0.dp, 800.dp),
                            true,
                        ),
                )
            setContent {
                provideFoldableLayoutInfo(fold) {
                    MaterialTheme {
                        EntryPageChrome(
                            EditorState(blocks = listOf(block)),
                            overview,
                            EntryStartMode.TEXT,
                            {},
                            {},
                            {},
                            header = {},
                            safeInsets = WindowInsets(0, 24, 0, 48),
                        ) {
                            EntryPageOverview(
                                BlocksUiState(
                                    blocks = listOf(block),
                                    availableJournals = emptyList(),
                                    selectedJournalIds = emptyList(),
                                    onBlockFocused = {},
                                    onJournalSelectionChanged = {},
                                    onUpdateBlock = {},
                                    onCreateBlock = { _, _ -> block },
                                    onDeleteBlock = {},
                                ),
                                notes,
                                overview,
                            )
                        }
                    }
                }
            }
            runOnIdle { runBlocking { overview.scroll.scrollTo(overview.scroll.maxValue) } }
            val lastMemory = onNodeWithTag("earlier_note_${notes.last().uid}").getUnclippedBoundsInRoot()
            assertEquals(736.dp, lastMemory.bottom, "Reading pane needs its own safe bottom plus one 16 dp design margin")
        }

    @Test
    fun scrolledPageUsesOneSymmetricMarginInsideSafeBoundsAndRetainsWideRail() {
        for (width in listOf(400, 700, 900)) {
            runDesktopComposeUiTest(width = width, height = 800) {
                val block = TextBlockUiState(content = (1..40).joinToString("\n\n") { "Detail $it from the quiet walk." })
                val overview = EntryPageOverviewState(false)
                val ui =
                    BlocksUiState(
                        blocks = listOf(block),
                        availableJournals = emptyList(),
                        selectedJournalIds = emptyList(),
                        onBlockFocused = {},
                        onJournalSelectionChanged = {},
                        onUpdateBlock = {},
                        onCreateBlock = { _, _ -> block },
                        onDeleteBlock = {},
                    )
                setContent {
                    MaterialTheme(
                        colorScheme = lightColorScheme(surfaceDim = Color.Red, surfaceContainerLow = Color.Blue, onSurface = Color.Green),
                    ) {
                        EntryPageChrome(
                            EditorState(blocks = listOf(block)),
                            overview,
                            EntryStartMode.TEXT,
                            {},
                            {},
                            {},
                            header = {},
                            safeInsets = WindowInsets(20, 24, 36, 48),
                        ) { EntryPageOverview(ui, emptyList(), overview) }
                    }
                }
                onNodeWithTag("entry_page_creation").performScrollTo()
                val capture = onNodeWithTag("entry_creation_actions").getUnclippedBoundsInRoot()
                val pixels = onRoot().captureToImage().toPixelMap()
                assertEquals(Color.Blue, pixels[width / 2, 2], "Scrolling surfaces must paint behind the status bar")
                val railWidth = minOf(width - 56, 640)
                val railLeft = 20 + (width - 56 - railWidth) / 2f
                assertEquals((railLeft + 16).dp, capture.left, "One inner horizontal margin")
                assertEquals((railLeft + railWidth - 16).dp, capture.right, "The large-window rail stays bounded")
                assertEquals(16.dp, 752.dp - capture.bottom, "Bottom design margin must equal the rail's side margin")

                fun isInk(color: Color) = color.green > 0.5f && color.red < 0.4f && color.blue < 0.4f
                assertTrue(
                    (40 until 720).any { y ->
                        (railLeft.toInt() + 20 until (railLeft + railWidth).toInt() - 20).any { x ->
                            isInk(pixels[x, y])
                        }
                    },
                    "The text oracle must see actual ink below the status strip",
                )
                val end = overview.scroll.value
                for (offset in 0..48 step 8) {
                    runOnIdle { runBlocking { overview.scroll.scrollTo(end - offset) } }
                    val frame = onRoot().captureToImage().toPixelMap()
                    assertTrue(
                        (0 until 24).none { y ->
                            (railLeft.toInt() + 20 until (railLeft + railWidth).toInt() - 20).any { x ->
                                isInk(frame[x, y])
                            }
                        },
                        "Inner text drew through the status strip at scroll offset $offset",
                    )
                }
            }
        }
    }

    @Test
    fun focusedSurfacePaintsBehindEveryBarWhileWritingRemainsInsideSafeInsets() =
        runDesktopComposeUiTest(width = 400, height = 800) {
            val block = TextBlockUiState(content = "A quiet walk by the water.")
            setContent {
                MaterialTheme(colorScheme = lightColorScheme(surfaceDim = Color.Red, surfaceContainerLow = Color.Blue)) {
                    EntryPageChrome(
                        EditorState(blocks = listOf(block), expandedBlockId = block.id),
                        EntryPageOverviewState(false),
                        EntryStartMode.TEXT,
                        {},
                        {},
                        {},
                        header = {},
                        safeInsets = WindowInsets(20, 24, 36, 48),
                    ) {
                        MainEditorContent(
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
                            expandAnySelectedBlock = true,
                            floatingChrome = true,
                        )
                    }
                }
            }
            val pixels = onRoot().captureToImage().toPixelMap()
            for ((x, y) in listOf(200 to 2, 2 to 400, 398 to 400, 200 to 798)) {
                assertEquals(Color.Blue, pixels[x, y], "Focused surface must continue behind the bar at $x,$y")
            }
            val field = onNodeWithTag("editor_text_input").getUnclippedBoundsInRoot()
            assertEquals(20.dp, field.left)
            assertEquals(364.dp, field.right)
            assertEquals(88.dp, field.top)
            assertEquals(752.dp, field.bottom)
        }
}
