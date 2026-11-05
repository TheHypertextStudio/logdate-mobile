package app.logdate.client.e2e

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.abs
import kotlin.test.assertTrue

internal fun SemanticsNodeInteractionsProvider.assertRecoveryViewerAlignment() {
    val action = onNodeWithText("Show recovery phrase").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
    val description =
        onNodeWithText("For restoring a journal backup when automatic recovery isn’t available.")
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
    val help = onNodeWithText("How recovery works").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
    val title = onNodeWithText("Recovery phrase").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
    val back = onNodeWithContentDescription("Back").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
    val rtl = InstrumentationRegistry.getArguments().getString("recoveryRtl") == "true"

    fun Rect.leading(): Float = if (rtl) right else left
    assertTrue(abs(help.leading() - description.leading()) <= 1f, "Help shares the description reading inset")
    assertTrue(abs(action.leading() - description.leading()) <= 1f, "Show shares the description reading inset")
    if (title.top >= back.bottom - 1f) {
        assertTrue(abs(title.leading() - description.leading()) <= 1f, "The expanded title shares the reading inset")
    }
}
