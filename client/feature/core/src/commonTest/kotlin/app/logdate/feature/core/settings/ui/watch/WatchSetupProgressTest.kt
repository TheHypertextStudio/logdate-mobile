package app.logdate.feature.core.settings.ui.watch

import app.logdate.feature.core.settings.ui.watch.v2.SetupStepProgress
import app.logdate.feature.core.settings.ui.watch.v2.WatchSetupProgressContext
import app.logdate.feature.core.settings.ui.watch.v2.WatchSetupVisualStep
import app.logdate.feature.core.settings.ui.watch.v2.resolveWatchSetupProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchSetupProgressTest {
    @Test
    fun `non-progress and prerequisite states return null`() {
        for (step in listOf(
            WatchSetupVisualStep.LOADING,
            WatchSetupVisualStep.UNSUPPORTED,
            WatchSetupVisualStep.NO_WATCH,
            WatchSetupVisualStep.DISCOVERY_UNAVAILABLE,
        )) {
            assertNull(resolveWatchSetupProgress(step, WatchStatus.NoWatch))
        }
        assertNull(resolveWatchSetupProgress(WatchSetupVisualStep.REQUEST_FAILED, WatchStatus.NoWatch))
        assertNull(
            resolveWatchSetupProgress(
                WatchSetupVisualStep.REQUEST_FAILED,
                WatchStatus.Unknown(watchName = null, nodeId = null),
            ),
        )
    }

    @Test
    fun `single watch install journey advances strictly through all 4 steps and reaches 1 only on complete`() {
        val status = WatchStatus.NotInstalled("Pixel Watch 5", "watch-a")
        val steps =
            listOf(
                WatchSetupVisualStep.NOT_INSTALLED to SetupStepProgress(1, 4),
                WatchSetupVisualStep.INSTALL_SENT to SetupStepProgress(2, 4),
                WatchSetupVisualStep.LINKING to SetupStepProgress(3, 4),
                WatchSetupVisualStep.COMPLETE to SetupStepProgress(4, 4),
            )
        var previous = 0f
        steps.forEach { (step, expected) ->
            val resolved =
                resolveWatchSetupProgress(
                    step = step,
                    status = status,
                    context = WatchSetupProgressContext(sawAppStep = step != WatchSetupVisualStep.NOT_INSTALLED),
                )
            assertEquals(expected, resolved)
            assertTrue(resolved!!.fraction > previous, "Expected $resolved to advance past $previous")
            previous = resolved.fraction
        }
        assertTrue(resolveWatchSetupProgress(WatchSetupVisualStep.LINKING, status)!!.fraction < 1f)
        assertEquals(1f, resolveWatchSetupProgress(WatchSetupVisualStep.COMPLETE, status)!!.fraction)
    }

    @Test
    fun `multi watch install journey advances strictly across all 5 steps`() {
        val watches =
            listOf(
                DiscoveredWatch("watch-a", "Pixel Watch 5", isNearby = true, appInstalled = false),
                DiscoveredWatch("watch-b", "Galaxy Watch 7", isNearby = true, appInstalled = true),
            )
        val status = WatchStatus.NotInstalled("Pixel Watch 5", "watch-a", availableWatches = watches)
        val context = WatchSetupProgressContext(sawChooser = true, sawAppStep = true)
        val expected =
            listOf(
                WatchSetupVisualStep.CHOOSE_WATCH to SetupStepProgress(1, 5),
                WatchSetupVisualStep.NOT_INSTALLED to SetupStepProgress(2, 5),
                WatchSetupVisualStep.INSTALL_SENT to SetupStepProgress(3, 5),
                WatchSetupVisualStep.LINKING to SetupStepProgress(4, 5),
                WatchSetupVisualStep.COMPLETE to SetupStepProgress(5, 5),
            )
        var previous = 0f
        expected.forEach { (step, progress) ->
            val resolved = resolveWatchSetupProgress(step, status, context)
            assertEquals(progress, resolved)
            assertTrue(resolved!!.fraction > previous)
            previous = resolved.fraction
        }
    }

    @Test
    fun `pairing without app step advances monotonically across 4 checkpoints`() {
        val status = WatchStatus.SettingUp(WatchSetupStep.PairWithPhone("Pixel Watch 5"), "watch-a")
        val pair = resolveWatchSetupProgress(WatchSetupVisualStep.PAIR_PHONE, status)
        val confirm = resolveWatchSetupProgress(WatchSetupVisualStep.CONFIRM_PHONE, status)
        val linking =
            resolveWatchSetupProgress(
                WatchSetupVisualStep.LINKING,
                status,
                WatchSetupProgressContext(sawPairing = true, sawAppStep = false),
            )
        val complete =
            resolveWatchSetupProgress(
                WatchSetupVisualStep.COMPLETE,
                status,
                WatchSetupProgressContext(sawPairing = true, sawAppStep = false),
            )

        assertEquals(SetupStepProgress(1, 5), pair)
        assertEquals(SetupStepProgress(2, 5), confirm)
        assertEquals(SetupStepProgress(3, 4), linking)
        assertEquals(SetupStepProgress(4, 4), complete)
        assertTrue(pair!!.fraction < confirm!!.fraction)
        assertTrue(confirm.fraction < linking!!.fraction)
        assertTrue(linking.fraction < complete!!.fraction)
    }

    @Test
    fun `multi watch pairing with app step advances monotonically across all 7 steps`() {
        val watches =
            listOf(
                DiscoveredWatch("watch-a", "Pixel Watch 5", isNearby = true, appInstalled = false),
                DiscoveredWatch("watch-b", "Galaxy Watch 7", isNearby = true, appInstalled = false),
            )
        val status = WatchStatus.NotInstalled("Pixel Watch 5", "watch-a", availableWatches = watches)
        val steps =
            listOf(
                WatchSetupVisualStep.CHOOSE_WATCH to WatchSetupProgressContext(sawChooser = true),
                WatchSetupVisualStep.PAIR_PHONE to WatchSetupProgressContext(sawChooser = true, sawPairing = true),
                WatchSetupVisualStep.CONFIRM_PHONE to WatchSetupProgressContext(sawChooser = true, sawPairing = true),
                WatchSetupVisualStep.NOT_INSTALLED to WatchSetupProgressContext(sawChooser = true, sawPairing = true, sawAppStep = true),
                WatchSetupVisualStep.INSTALL_SENT to WatchSetupProgressContext(sawChooser = true, sawPairing = true, sawAppStep = true),
                WatchSetupVisualStep.LINKING to WatchSetupProgressContext(sawChooser = true, sawPairing = true, sawAppStep = true),
                WatchSetupVisualStep.COMPLETE to WatchSetupProgressContext(sawChooser = true, sawPairing = true, sawAppStep = true),
            )
        var previous = 0f
        steps.forEach { (step, ctx) ->
            val resolved = resolveWatchSetupProgress(step, status, ctx)
            assertTrue(resolved != null && resolved.fraction > previous, "Step $step did not advance cleanly: $resolved vs $previous")
            previous = resolved.fraction
        }
        assertEquals(1f, previous)
    }
}
