package app.logdate.feature.editor.ui.handwriting

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.logdate.client.datastore.EntryStartMode
import app.logdate.feature.editor.ui.editor.EntryBlockUiState
import app.logdate.feature.editor.ui.editor.HandwritingBlockUiState
import app.logdate.feature.editor.ui.editor.TextBlockUiState
import app.logdate.feature.editor.ui.text.LOGDATE_EDITOR_TEXT_INPUT_TAG
import app.logdate.ui.foldable.FoldablePosture
import app.logdate.ui.theme.LogDateTheme
import org.jetbrains.skia.Image
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A viewport handoff within one editor composition, not separately seeded open/closed screens. */
@OptIn(ExperimentalTestApi::class)
@RunWith(Parameterized::class)
class EntryPageAdaptiveFlowTest(
    private val kind: String,
) {
    @Test(timeout = 120_000)
    fun writingAndBrowsingSurvivePostureChanges() =
        runDesktopComposeUiTest(width = 1280, height = 900) {
            val folding = kind == "foldable"
            var size by mutableStateOf(IntSize(if (folding) 1000 else 1280, 800))
            var closed by mutableStateOf(false)
            val ink = HandwritingBlockUiState(page = entryPageStudyInk())
            val first = TextBlockUiState(content = "Took the long way home.\n\nThe light was too good to miss.")
            val longNote =
                TextBlockUiState(
                    content =
                        listOf(
                            "A few things I want to remember.",
                            "The walk along the water was quiet. I stopped for a while and watched the reflections move across the buildings.",
                            "We found a little place for lunch and stayed until the afternoon light started coming through the windows.",
                            "I want to make a little more room for days like this, with enough time to notice what's around me.",
                            "On the way back, I took a few notes so I could return to this feeling later.",
                        ).joinToString("\n\n"),
                )
            var latest: List<EntryBlockUiState> = emptyList()
            val overview = EntryPageOverviewState(false)
            val outputRoot =
                System.getenv("DISK_JANITOR_ARTIFACT_ROOT")?.let { File(it, "adaptive-entry") }
                    ?: File("build/reports/entry-adaptive")
            val output = File(outputRoot, kind).apply { mkdirs() }
            setContent {
                val base = foldFixture(if (folding && !closed) FoldablePosture.Book else FoldablePosture.Standard, size.width, size.height)
                val offsetX = ((1280 - size.width) / 2).dp
                val offsetY = ((900 - size.height) / 2).dp
                val hinge = base.hinge
                val fold =
                    if (hinge == null) {
                        base
                    } else {
                        base.copy(
                            hinge =
                                hinge.copy(
                                    bounds =
                                        hinge.bounds.copy(
                                            left = hinge.bounds.left + offsetX,
                                            right = hinge.bounds.right + offsetX,
                                            top = hinge.bounds.top + offsetY,
                                            bottom = hinge.bounds.bottom + offsetY,
                                        ),
                                ),
                        )
                    }
                LogDateTheme(dynamicColor = false) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceDim)) {
                        Box(
                            Modifier
                                .align(Alignment.Center)
                                .size(size.width.dp, size.height.dp)
                                .clipToBounds()
                                .testTag("adaptive_device"),
                        ) {
                            EntryPageStudy(
                                fold = fold,
                                defaultMode = EntryStartMode.TEXT,
                                initialBlocks = listOf(first, ink, longNote),
                                onBlocksChanged = { latest = it },
                                overviewStateOverride = overview,
                            )
                        }
                    }
                }
            }
            waitForIdle()
            var frame = 0

            fun save(
                file: File,
                deviceOnly: Boolean = false,
            ) {
                val node = if (deviceOnly) onNodeWithTag("adaptive_device") else onRoot()
                node.captureToImage().asSkiaBitmap().use { bitmap ->
                    Image.makeFromBitmap(bitmap).use { image ->
                        checkNotNull(image.encodeToData()).use { data -> file.writeBytes(data.bytes) }
                    }
                }
            }

            fun frames(count: Int) {
                repeat(count) {
                    mainClock.advanceTimeBy(40)
                    if (frame % 5 == 0 && frame < 640) {
                        save(File(output, "frame-${frame.toString().padStart(4, '0')}.png"))
                    }
                    frame++
                }
            }

            fun range() = onNodeWithTag("entry_page_scroll").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]

            fun assertCaptureBarFits() {
                onAllNodesWithTag("entry_capture_tools").assertCountEquals(1)
                val device = onNodeWithTag("adaptive_device").fetchSemanticsNode().boundsInRoot
                val bar = onNodeWithTag("entry_creation_actions").fetchSemanticsNode().boundsInRoot
                assertTrue(
                    bar.left >= device.left - 1f && bar.right <= device.right + 1f && bar.bottom <= device.bottom + 1f,
                    "$bar escapes $device",
                )
            }

            fun drag(
                distance: Float,
                count: Int = 12,
                hold: Int = 0,
            ) {
                onNodeWithTag("entry_page_scroll").performTouchInput { down(Offset(width - 6f, if (distance < 0f) height - 30f else 30f)) }
                repeat(count) {
                    onNodeWithTag("entry_page_scroll").performTouchInput { moveBy(Offset(0f, distance / count), delayMillis = 40) }
                    frames(1)
                }
                frames(hold)
                onNodeWithTag("entry_page_scroll").performTouchInput {
                    advanceEventTime(160)
                    up()
                }
            }

            fun scrollToTop() {
                var attempts = 0
                while (range().value() > 1f) {
                    check(attempts++ < 30)
                    drag(minOf(range().value() + 32f, 320f))
                    frames(3)
                }
            }
            mainClock.autoAdvance = false
            frames(12)
            save(File(output, "overview.png"), true)
            drag(-(range().maxValue() + 32f), count = 20)
            frames(10)
            val previousScroll = range().value()
            assertTrue(previousScroll > 0f)
            drag(-240f, count = 16, hold = 10)
            frames(24)
            assertCaptureBarFits()
            onNodeWithTag(LOGDATE_EDITOR_TEXT_INPUT_TAG).performTextInput("Started with the screen open.")
            frames(10)
            val draftIds = latest.map { it.id }
            val writing = latest.filterIsInstance<TextBlockUiState>().last()
            File(
                output,
                "anchor-trace.txt",
            ).writeText("created: ${overview.focusedAnchor}, scroll=${overview.scroll.value}, width=${overview.viewportWidth}\n")
            assertEquals(4, latest.size)
            onNodeWithTag("entry_capture_tools").assertIsNotDisplayed()
            onNodeWithContentDescription("Return to entry").performClick()
            frames(20)
            mainClock.autoAdvance = true
            runOnIdle {
                kotlinx.coroutines.runBlocking {
                    overview.scroll.scrollTo(
                        (checkNotNull(overview.blockDocumentTops[ink.id]) - 180).coerceAtLeast(0),
                    )
                }
            }
            waitForIdle()
            mainClock.autoAdvance = false
            onNodeWithTag("memory_block_${ink.id}").performClick()
            frames(20)
            onNodeWithTag("focused_block_${ink.id}").assertExists()
            onNodeWithTag("entry_capture_tools").assertIsNotDisplayed()
            if (folding) {
                runOnIdle {
                    size = IntSize(360, 800)
                    closed = true
                }
                frames(24)
                onNodeWithTag("focused_block_${ink.id}").assertExists()
                onNodeWithTag("entry_capture_tools").assertIsNotDisplayed()
                assertEquals(draftIds, latest.map { it.id })
                assertEquals(ink.page, latest.filterIsInstance<HandwritingBlockUiState>().single().page)
                save(File(output, "closed-handwriting.png"), true)
            }
            onNodeWithContentDescription("Return to entry").performClick()
            frames(20)
            mainClock.autoAdvance = true
            runOnIdle {
                kotlinx.coroutines.runBlocking {
                    overview.scroll.scrollTo(
                        (
                            checkNotNull(overview.blockDocumentTops[writing.id]) -
                                180
                        ).coerceAtLeast(0),
                    )
                }
            }
            val memory = onNodeWithTag("memory_block_${writing.id}")
            val footer = onNodeWithTag("entry_creation_actions")
            val overlap = memory.fetchSemanticsNode().boundsInRoot.bottom - footer.fetchSemanticsNode().boundsInRoot.top + 16f
            if (overlap > 0f) {
                onNodeWithTag("entry_page_scroll").performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy -> scrollBy(0f, overlap) }
                waitForIdle()
            }
            assertTrue(
                memory
                    .fetchSemanticsNode()
                    .boundsInRoot.center.y < footer.fetchSemanticsNode().boundsInRoot.top,
                "Scroll the retained memory clear of the floating Add action before its physical tap",
            )
            mainClock.autoAdvance = false
            memory.performClick()
            frames(20)
            onNodeWithTag("focused_block_${writing.id}").assertExists()
            if (folding) {
                onNodeWithTag(LOGDATE_EDITOR_TEXT_INPUT_TAG).performTextInput("\n\nContinued after folding it closed.")
                frames(12)
                save(File(output, "closed-writing.png"), true)
                assertTrue(
                    latest
                        .filterIsInstance<TextBlockUiState>()
                        .last()
                        .content
                        .contains("Continued after folding it closed."),
                )
            } else {
                save(File(output, "tablet-writing.png"), true)
            }
            assertEquals(ink.page, latest.filterIsInstance<HandwritingBlockUiState>().single().page)
            File(
                output,
                "anchor-trace.txt",
            ).appendText("before-return: ${overview.focusedAnchor}, scroll=${overview.scroll.value}, width=${overview.viewportWidth}\n")
            onNodeWithContentDescription("Return to entry").performClick()
            frames(26)
            File(
                output,
                "anchor-trace.txt",
            ).appendText(
                "after-return: ${overview.focusedAnchor}, scroll=${overview.scroll.value}, width=${overview.viewportWidth}, positions=${overview.blockPositions}\n",
            )
            onNodeWithTag("memory_block_${writing.id}").assertIsDisplayed()
            assertTrue(range().value() > 0f, "Returning to the retained last memory must keep its scroll anchor visible")
            assertEquals(draftIds, latest.map { it.id })
            save(File(output, "returned-draft.png"), true)
            frames(8)
            scrollToTop()
            frames(8)
            drag(240f, count = 16, hold = 10)
            frames(24)
            onNodeWithTag("entry_day_browser").assertExists()
            save(File(output, "earlier-day.png"), true)
            frames(12)
            onNodeWithContentDescription("Return to writing").performClick()
            frames(20)
            if (folding) {
                runOnIdle {
                    size = IntSize(1000, 800)
                    closed = false
                }
                frames(20)
                assertEquals(draftIds, latest.map { it.id })
                assertEquals(0f, range().value(), 1f)
                drag(240f, count = 16, hold = 10)
                frames(24)
                save(File(output, "book-earlier-day.png"), true)
                onNodeWithContentDescription("Return to writing").performClick()
                frames(20)
            }
            onAllNodesWithTag("entry_capture_tools").assertCountEquals(1)
            mainClock.autoAdvance = true
            File(output, "frames.txt").writeText(frame.toString())
        }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun variants(): List<Array<Any>> = listOf(arrayOf("tablet"), arrayOf("foldable"))
    }
}
