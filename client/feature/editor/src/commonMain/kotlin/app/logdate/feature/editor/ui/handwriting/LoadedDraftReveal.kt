package app.logdate.feature.editor.ui.handwriting

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import app.logdate.feature.editor.ui.editor.EditorDraftReveal
import app.logdate.feature.editor.ui.layout.EditorBlockMinHeight
import app.logdate.feature.editor.ui.state.BlocksUiState
import app.logdate.ui.theme.Spacing
import kotlin.math.roundToInt
import kotlin.uuid.Uuid

internal data class DraftRevealPlacement(
    val request: EditorDraftReveal,
    val top: Float,
    val height: Int,
)

internal fun EntryPageOverviewState.recordDraftReveal(
    request: EditorDraftReveal?,
    blockId: Uuid,
    coordinates: LayoutCoordinates,
) {
    if (request?.blockId == blockId) {
        draftRevealPlacement = DraftRevealPlacement(request, coordinates.positionInWindow().y, coordinates.size.height)
    }
}

@Composable
internal fun revealLoadedDraft(
    ui: BlocksUiState,
    page: EntryPageOverviewState,
    viewportHeight: Int,
    headerReserve: Int,
    dockReserve: Int,
    moving: Boolean,
) {
    val request = ui.draftReveal
    val placed = page.draftRevealPlacement
    val heading = page.draftRevealHeading
    val consume by rememberUpdatedState(ui.onConsumeDraftReveal)
    val minimum = with(LocalDensity.current) { EditorBlockMinHeight.roundToPx() }
    val gap = with(LocalDensity.current) { Spacing.lg.roundToPx() }
    val safeTop = maxOf(page.viewportTop + headerReserve, (page.headerBottom ?: page.viewportTop) + gap)
    val safeBottom = minOf(page.viewportTop + viewportHeight - dockReserve, (page.dockTop ?: Float.POSITIVE_INFINITY) - gap)
    val contextLoading = ui.draftRevealContextLoading
    LaunchedEffect(request, placed, heading, ui.expandedBlockId, page.earlierExpanded, moving, safeTop, safeBottom, contextLoading) {
        if (request == null) {
            page.draftRevealPlacement = null
            page.draftRevealHeading = null
            return@LaunchedEffect
        }
        if (ui.expandedBlockId != null || page.earlierExpanded || page.scroll.isScrollInProgress) {
            consume(request)
            return@LaunchedEffect
        }
        // Today's earlier notes load separately and are laid out above the draft. Revealing before
        // they arrive leaves the draft pushed below the screen once they do.
        if (contextLoading) return@LaunchedEffect
        // Notes that just arrived are laid out after this effect starts, so measure on the next frame.
        withFrameNanos {}
        val measured = page.draftRevealPlacement
        val measuredHeading = page.draftRevealHeading
        if (moving || measured?.request != request || measuredHeading?.request != request) return@LaunchedEffect
        if (!consume(request)) return@LaunchedEffect
        page.focusedAnchor = null
        page.focusScrollBookmark = null
        val top = safeTop
        val bottom = safeBottom
        val beginning = minOf(measuredHeading.top, measured.top)
        val visibleBeginning =
            minOf(
                (measured.top - beginning).roundToInt() + minOf(measured.height, minimum),
                (bottom - top).roundToInt().coerceAtLeast(1),
            )
        if (beginning < top || beginning + visibleBeginning > bottom) {
            page.scroll.scrollTo((page.scroll.value + beginning - top).roundToInt().coerceIn(0, page.scroll.maxValue))
        }
    }
}
