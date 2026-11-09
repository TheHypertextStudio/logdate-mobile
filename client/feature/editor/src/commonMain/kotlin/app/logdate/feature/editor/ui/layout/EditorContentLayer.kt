@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.editor.ui.layout

import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.logdate.feature.editor.ui.LocalSharedTransitionScope
import app.logdate.feature.editor.ui.editor.AudioBlockUiState
import app.logdate.feature.editor.ui.editor.EntryBlockUiState
import app.logdate.feature.editor.ui.editor.TextBlockUiState

internal val LocalEntryChromeHeight = compositionLocalOf { 64.dp }

/** Safe layout belongs to the inner document/controls, never the full-window painted surface. */
internal val LocalEntryContentInsets = compositionLocalOf { WindowInsets(0, 0, 0, 0) }
internal val LocalEntryCreationReserve = compositionLocalOf { 0.dp }
internal val LocalEntryHeaderReserve = compositionLocalOf { 0.dp }

/**
 * Chrome clearances (top header height and bottom dock reserve) required
 * for a focused block to avoid occlusion by floating editor chrome.
 */
internal data class FocusedBlockChromeClearance(
    val top: Dp = 0.dp,
    val bottom: Dp = 0.dp,
)

/**
 * Layout policy governing chrome clearance for focused blocks in the editor.
 *
 * Immersive and full-bleed viewports (such as camera capture) render edge-to-edge behind
 * floating chrome, while interactive controls and reading content (such as text editing and
 * idle audio recording controls) require safe clearance above the dock and below the header.
 */
internal object FocusedBlockChromePolicy {
    /**
     * Resolves the top clearance required to keep [block] below the persistent header.
     */
    fun topClearance(
        block: EntryBlockUiState,
        chromeHeight: Dp,
    ): Dp =
        when (block) {
            is TextBlockUiState, is AudioBlockUiState -> chromeHeight
            else -> 0.dp
        }

    /**
     * Resolves the bottom reserve required to keep [block] above the floating creation dock.
     */
    fun bottomClearance(
        block: EntryBlockUiState,
        dockReserve: Dp,
    ): Dp =
        when {
            block is TextBlockUiState -> dockReserve
            block is AudioBlockUiState && block.uri == null -> dockReserve
            else -> 0.dp
        }

    /**
     * Resolves both top and bottom clearances for [block].
     */
    fun clearanceFor(
        block: EntryBlockUiState,
        chromeHeight: Dp,
        dockReserve: Dp,
    ): FocusedBlockChromeClearance =
        FocusedBlockChromeClearance(
            top = topClearance(block, chromeHeight),
            bottom = bottomClearance(block, dockReserve),
        )
}

/** Apply after painting the owning surface; shared by writing and recording's inner layout. */
@Composable
internal fun Modifier.entryContentPadding(
    top: Dp = 0.dp,
    bottom: Dp = 0.dp,
): Modifier = windowInsetsPadding(LocalEntryContentInsets.current.union(WindowInsets.ime)).padding(top = top, bottom = bottom)

/** Shared block overlays stay inside the content layer, below the frame's interactive chrome. */
@Composable
internal fun EditorContentLayer(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    SharedTransitionLayout(modifier) {
        CompositionLocalProvider(LocalSharedTransitionScope provides this) { content() }
    }
}
