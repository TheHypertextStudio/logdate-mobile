package app.logdate.feature.editor.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class RetainedEditorFocusMotionTest {
    @Test(timeout = 60_000)
    fun heldBackCannotAdoptAReplacementInputForTheSameMemory() = heldBackCannotAdoptReplacement(sameMemory = true)

    @Test(timeout = 60_000)
    fun heldBackCannotAdoptAnotherFocusedMemory() = heldBackCannotAdoptReplacement(sameMemory = false)

    private fun heldBackCannotAdoptReplacement(sameMemory: Boolean) =
        runDesktopComposeUiTest {
            var focused by mutableStateOf<Uuid?>(Uuid.random())
            var token by mutableStateOf(Uuid.random())
            lateinit var motion: RetainedEditorFocusMotion
            var dismissals = 0
            setContent {
                motion =
                    rememberRetainedEditorFocusMotion(
                        focused,
                        setOfNotNull(focused),
                        false,
                        onDismiss = {
                            dismissals++
                            focused = null
                            true
                        },
                        onPrepare = {},
                        onProgress = {},
                        onCommit = {},
                        onCancel = {},
                        inputGeneration = token,
                    )
                Text("A held gesture belongs to its original input")
            }
            waitForIdle()
            mainClock.autoAdvance = false
            runOnIdle { motion.seekBack(0.3f) }
            mainClock.advanceTimeBy(16)
            runOnIdle {
                if (!sameMemory) focused = Uuid.random()
                token = Uuid.random()
            }
            repeat(60) { mainClock.advanceTimeBy(16) }
            val successor = focused
            runOnIdle { motion.seekBack(0.6f) } // Another event from the same still-held OS gesture.
            mainClock.advanceTimeBy(16)
            assertEquals(1f, motion.surfaceProgress, 0.001f, "An old held gesture must not move its successor")
            runOnIdle { motion.commitBack() }
            repeat(60) { mainClock.advanceTimeBy(16) }
            assertEquals(successor, focused)
            assertEquals(0, dismissals)
            // The terminal event releases the sentinel; a genuinely new gesture can act normally.
            runOnIdle {
                motion.seekBack(0.4f)
                motion.commitBack()
            }
            repeat(60) { mainClock.advanceTimeBy(16) }
            assertEquals(1, dismissals)
            assertEquals(null, focused)
            mainClock.autoAdvance = true
        }

    @Test(timeout = 60_000)
    fun reducedMotionHoldsGeometryUntilCommitAndCancelRetainsTheInput() =
        runDesktopComposeUiTest {
            val id = Uuid.random()
            var focused by mutableStateOf<Uuid?>(id)
            lateinit var motion: RetainedEditorFocusMotion
            var dismissals = 0
            setContent {
                motion =
                    rememberRetainedEditorFocusMotion(
                        focused,
                        setOf(id),
                        true,
                        onDismiss = {
                            dismissals++
                            focused = null
                            true
                        },
                        onPrepare = {},
                        onProgress = {},
                        onCommit = {},
                        onCancel = {},
                    )
                Text("Reduced motion retains the same input")
            }
            waitForIdle()
            runOnIdle { motion.seekBack(0.7f) }
            waitForIdle()
            assertEquals(1f, motion.surfaceProgress)
            assertEquals(id, focused)
            runOnIdle { motion.cancelBack() }
            waitForIdle()
            assertEquals(id, motion.targetId)
            assertEquals(0, dismissals)
            runOnIdle {
                motion.seekBack(0.7f)
                motion.commitBack()
            }
            waitForIdle()
            assertEquals(null, focused)
            assertEquals(1, dismissals)
            assertEquals(0f, motion.surfaceProgress)
        }

    @Test(timeout = 60_000)
    fun changingTheInputGenerationCancelsAnOldGestureForTheSameMemory() =
        runDesktopComposeUiTest {
            val id = Uuid.random()
            var token by mutableStateOf(Uuid.random())
            lateinit var motion: RetainedEditorFocusMotion
            var dismissals = 0
            setContent {
                motion =
                    rememberRetainedEditorFocusMotion(
                        id,
                        setOf(id),
                        false,
                        onDismiss = {
                            dismissals++
                            true
                        },
                        onPrepare = {},
                        onProgress = {},
                        onCommit = {},
                        onCancel = {},
                        inputGeneration = token,
                    )
                Text("One memory, newly owned input")
            }
            waitForIdle()
            mainClock.autoAdvance = false
            runOnIdle { motion.seekBack(0.4f) }
            mainClock.advanceTimeBy(16)
            runOnIdle {
                motion.commitBack()
                token = Uuid.random()
            }
            repeat(60) { mainClock.advanceTimeBy(16) }
            assertEquals(0, dismissals)
            assertEquals(id, motion.targetId)
            assertEquals(1f, motion.surfaceProgress, 0.001f)
            mainClock.autoAdvance = true
        }

    @Test(timeout = 60_000)
    fun backStartsAtTheVisibleUnfinishedEntranceAndCancelFinishesThatEntrance() =
        runDesktopComposeUiTest {
            val id = Uuid.random()
            var focused by mutableStateOf<Uuid?>(null)
            lateinit var motion: RetainedEditorFocusMotion
            var dismissals = 0
            setContent {
                motion =
                    rememberRetainedEditorFocusMotion(
                        focused,
                        setOf(id),
                        false,
                        onDismiss = {
                            dismissals++
                            focused = null
                            true
                        },
                        onPrepare = {},
                        onProgress = {},
                        onCommit = {},
                        onCancel = {},
                    )
                Text("One live motion owner")
            }
            waitForIdle()
            mainClock.autoAdvance = false
            for (threshold in listOf(0.1f, 0.35f, 0.7f)) {
                runOnIdle { focused = null }
                repeat(60) { mainClock.advanceTimeBy(16) }
                runOnIdle { focused = id }
                var frames = 0
                while (motion.surfaceProgress < threshold && frames++ < 60) mainClock.advanceTimeBy(16)
                val visible = motion.surfaceProgress
                assertTrue(visible >= threshold && visible < 1f)
                runOnIdle { motion.seekBack(0.4f) }
                mainClock.advanceTimeBy(16)
                assertEquals(visible * 0.6f, motion.surfaceProgress, 0.001f)
                assertEquals(null, motion.targetId)
                assertEquals(id, focused, "Partial back must not dismiss logical focus")
                runOnIdle { motion.cancelBack() }
                repeat(60) { mainClock.advanceTimeBy(16) }
                assertEquals(1f, motion.surfaceProgress, 0.001f)
                assertEquals(id, motion.targetId)
                assertEquals(0, dismissals)
            }
            mainClock.autoAdvance = true
        }

    @Test(timeout = 60_000)
    fun oldCommitCannotDismissANewerFocusedMemory() =
        runDesktopComposeUiTest {
            val first = Uuid.random()
            val second = Uuid.random()
            var focused by mutableStateOf<Uuid?>(first)
            lateinit var motion: RetainedEditorFocusMotion
            var dismissals = 0
            setContent {
                motion =
                    rememberRetainedEditorFocusMotion(
                        focused,
                        setOf(first, second),
                        false,
                        onDismiss = {
                            dismissals++
                            focused = null
                            true
                        },
                        onPrepare = {},
                        onProgress = {},
                        onCommit = {},
                        onCancel = {},
                    )
                Text("Retarget without losing input ownership")
            }
            waitForIdle()
            mainClock.autoAdvance = false
            runOnIdle { motion.seekBack(0.3f) }
            mainClock.advanceTimeBy(16)
            runOnIdle {
                motion.commitBack()
                focused = second
            }
            repeat(60) { mainClock.advanceTimeBy(16) }
            assertEquals(second, focused)
            assertEquals(second, motion.targetId)
            assertEquals(1f, motion.surfaceProgress, 0.001f)
            assertEquals(0, dismissals)
            mainClock.autoAdvance = true
        }

    @Test(timeout = 60_000)
    fun refusedDismissalRestoresTheFocusedSurface() =
        runDesktopComposeUiTest {
            val id = Uuid.random()
            lateinit var motion: RetainedEditorFocusMotion
            var requests = 0
            setContent {
                motion =
                    rememberRetainedEditorFocusMotion(
                        id,
                        setOf(id),
                        false,
                        onDismiss = {
                            requests++
                            false
                        },
                        onPrepare = {},
                        onProgress = {},
                        onCommit = {},
                        onCancel = {},
                    )
                Text("Existing ownership guard retains focus")
            }
            waitForIdle()
            mainClock.autoAdvance = false
            runOnIdle { motion.seekBack(0.8f) }
            mainClock.advanceTimeBy(16)
            runOnIdle { motion.commitBack() }
            repeat(120) { mainClock.advanceTimeBy(16) }
            assertEquals(1, requests)
            assertEquals(id, motion.targetId)
            assertEquals(1f, motion.surfaceProgress, 0.001f)
            mainClock.autoAdvance = true
        }

    @Test(timeout = 60_000)
    fun aMemoryArrivingWhileTheEntryOpensLandsInFocusWithoutASecondMotion() = firstMemoryArrives(opening = true)

    @Test(timeout = 60_000)
    fun aMemoryAddedToAnOpenEntryStillGrowsIntoFocus() = firstMemoryArrives(opening = false)

    private fun firstMemoryArrives(opening: Boolean) =
        runDesktopComposeUiTest {
            val id = Uuid.random()
            var focused by mutableStateOf<Uuid?>(null)
            lateinit var motion: RetainedEditorFocusMotion
            setContent {
                motion =
                    rememberRetainedEditorFocusMotion(
                        focused,
                        setOfNotNull(focused),
                        false,
                        onDismiss = { true },
                        onPrepare = {},
                        onProgress = {},
                        onCommit = {},
                        onCancel = {},
                        settleArrivingFocus = { opening },
                    )
                Text("The preferred memory arrives after the entry")
            }
            waitForIdle()
            mainClock.autoAdvance = false
            runOnIdle { focused = id }
            mainClock.advanceTimeBy(16)
            if (opening) {
                assertEquals(1f, motion.surfaceProgress, 0.001f, "The opening motion already carries the memory into focus")
            } else {
                assertTrue(motion.surfaceProgress < 0.5f, "A memory added later grows from its place")
            }
            repeat(60) { mainClock.advanceTimeBy(16) }
            assertEquals(1f, motion.surfaceProgress, 0.001f)
            mainClock.autoAdvance = true
        }
}
