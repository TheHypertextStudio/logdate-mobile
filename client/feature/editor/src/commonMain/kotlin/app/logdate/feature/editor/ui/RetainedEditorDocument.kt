@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.editor.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.layout.approachLayout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.logdate.feature.editor.ui.handwriting.EntryPageOverviewState
import app.logdate.feature.editor.ui.handwriting.EntryPageScrollAnchor
import app.logdate.feature.editor.ui.handwriting.clearEntryDock
import app.logdate.feature.editor.ui.handwriting.recordDraftReveal
import app.logdate.feature.editor.ui.layout.EditorBlockMinHeight
import app.logdate.feature.editor.ui.layout.EntryPageRailMaxWidth
import app.logdate.feature.editor.ui.layout.LocalEntryContentBottom
import app.logdate.feature.editor.ui.layout.LocalEntryContentInsets
import app.logdate.feature.editor.ui.state.BlocksUiState
import app.logdate.ui.foldable.FoldableSplitLayout
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The document stays inside its real verticalScroll ancestor, including while a memory fills the
 * viewport. Lookahead measures the actual input at its endpoint; approach only changes its bounds.
 * This preserves Android modifier attachment, selection, accessibility reveal and nested scrolling.
 */
@Composable
internal fun RetainedEditorDocument(
    ui: BlocksUiState,
    continuity: EditorBlockContinuity,
    state: EntryPageOverviewState,
    viewport: IntSize,
    contentTop: Int,
    book: FoldableSplitLayout.Vertical?,
    modifier: Modifier,
    reading: @Composable () -> Unit,
    heading: @Composable () -> Unit,
    dock: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val safe = LocalEntryContentInsets.current
    val left = safe.getLeft(density, direction)
    val right = safe.getRight(density, direction)
    val gutter = with(density) { 16.dp.roundToPx() }
    val related = with(density) { 12.dp.roundToPx() }
    val section = with(density) { 24.dp.roundToPx() }
    val minimum = with(density) { EditorBlockMinHeight.roundToPx() }
    val railMaximum = with(density) { EntryPageRailMaxWidth.roundToPx() }
    val railWidth = (viewport.width - left - right).coerceIn(1, railMaximum)
    val railLeft = left + (viewport.width - left - right - railWidth) / 2
    val writingLeft = book?.let { maxOf(left, with(density) { it.rightPane.left.roundToPx() }) + gutter } ?: railLeft
    val writingWidth =
        book?.let { minOf(viewport.width - right, with(density) { it.rightPane.right.roundToPx() }) - writingLeft } ?: railWidth
    val blockWidth = (writingWidth - gutter * 2).coerceAtLeast(1)
    val readingLeft = book?.let { maxOf(left, with(density) { it.leftPane.left.roundToPx() }) } ?: railLeft
    val readingWidth =
        book?.let { minOf(viewport.width - right, with(density) { it.leftPane.right.roundToPx() }) - readingLeft - gutter } ?: railWidth
    val readingBottom = if (book != null) gutter + safe.getBottom(density) else 0
    val scope = rememberCoroutineScope()
    val focusedId = continuity.focusedId
    val maximumProgress = continuity.progress.values.maxOrNull() ?: 0f
    val moving = focusedId != null || maximumProgress > 0f
    SideEffect { state.blockDocumentTops.keys.retainAll(ui.blocks.map { it.id }.toSet()) }

    // Capture before focus effects can ask the scroll ancestor to reveal the cursor.
    if (ui.expandedBlockId != null && state.focusScrollBookmark == null) state.focusScrollBookmark = state.scroll.value
    LaunchedEffect(ui.expandedBlockId) {
        if (ui.expandedBlockId == null) {
            state.focusScrollBookmark?.let { state.scroll.scrollTo(it) }
            state.focusScrollBookmark = null
        }
    }
    Box(modifier) {
        LookaheadScope {
            Layout(
                modifier = Modifier.fillMaxWidth(),
                content = {
                    Box {
                        CompositionLocalProvider(
                            LocalEntryContentBottom provides
                                if (book ==
                                    null
                                ) {
                                    state.dockTop?.minus(gutter)
                                } else {
                                    null
                                },
                        ) {
                            reading()
                        }
                    }
                    Box { heading() }
                    ui.blocks.forEach { block ->
                        key(block.id) {
                            val expanded = focusedId == block.id
                            val progress = continuity.progress[block.id] ?: 0f
                            val measurement = continuity.host.measure(block.id)
                            val clippedByDock by remember(state, block.id, gutter) {
                                derivedStateOf {
                                    val top = state.blockDocumentTops[block.id]
                                    val dock = state.dockTop
                                    top != null &&
                                        dock != null &&
                                        state.viewportTop - state.scroll.value + top +
                                        (if (state.pull > 0f) state.pull else state.pull * 0.35f) >= dock - gutter
                                }
                            }
                            val approach =
                                Modifier.approachLayout(
                                    isMeasurementApproachInProgress = { target ->
                                        // This callback also runs when the completed approach pass is skipped.
                                        if (!expanded) measurement.inlineSize = target
                                        progress != if (expanded) 1f else 0f
                                    },
                                ) { measurable, _ ->
                                    val source = measurement.inlineSize.takeUnless { it == IntSize.Zero } ?: IntSize(blockWidth, minimum)
                                    val width =
                                        measurement.width
                                            .approach(
                                                source.width.toFloat(),
                                                viewport.width.toFloat(),
                                                progress,
                                            ).roundToInt()
                                            .coerceAtLeast(1)
                                    val height =
                                        measurement.height
                                            .approach(
                                                source.height.toFloat(),
                                                viewport.height.toFloat(),
                                                progress,
                                            ).roundToInt()
                                            .coerceAtLeast(minimum)
                                    val child = measurable.measure(Constraints.fixed(width, height))
                                    layout(child.width, child.height) { child.place(0, 0) }
                                }
                            continuity.host.Content(
                                block,
                                expanded,
                                progress,
                                approach.onGloballyPositioned { coordinates ->
                                    if (!expanded && progress == 0f && !state.earlierExpanded) {
                                        state.recordDraftReveal(ui.draftReveal, block.id, coordinates)
                                        val top = coordinates.positionInWindow().y
                                        state.blockPositions[block.id] = top
                                        val anchor = state.focusedAnchor
                                        if (ui.draftReveal == null && anchor?.blockId == block.id && anchor.width != viewport.width) {
                                            val desired = anchor.top.coerceAtMost(state.viewportTop + viewport.height / 2f)
                                            state.focusedAnchor = anchor.copy(top = desired, width = viewport.width)
                                            scope.launch { state.scroll.scrollTo(state.scroll.value + (top - desired).roundToInt()) }
                                        }
                                    }
                                },
                                onSelect = {
                                    if (state.focusScrollBookmark == null) state.focusScrollBookmark = state.scroll.value
                                    state.focusedAnchor =
                                        EntryPageScrollAnchor(block.id, state.blockPositions[block.id] ?: 88f, viewport.width)
                                    ui.onBlockFocused(block.id)
                                },
                                hidden = state.earlierExpanded || (focusedId != null && !expanded) || (progress == 0f && clippedByDock),
                            )
                        }
                    }
                    Box { dock() }
                },
            ) { children, constraints ->
                val lookahead = isLookingAhead
                val read = children[0].measure(Constraints(maxWidth = readingWidth.coerceAtLeast(1)))
                val now = children[1].measure(Constraints(maxWidth = writingWidth.coerceAtLeast(1)))
                val blocks =
                    ui.blocks.mapIndexed { index, block ->
                        val expanded = focusedId == block.id
                        val progress = continuity.progress[block.id] ?: 0f
                        val target =
                            when {
                                lookahead || progress == if (expanded) 1f else 0f ->
                                    if (expanded) Constraints.fixed(viewport.width, viewport.height) else Constraints.fixedWidth(blockWidth)
                                else -> Constraints(maxWidth = viewport.width, maxHeight = Constraints.Infinity)
                            }
                        val child = children[index + 2].measure(target)
                        val source = continuity.host.measure(block.id)
                        if (!expanded && (lookahead || progress == 0f)) source.inlineSize = IntSize(child.width, child.height)
                        if (!lookahead && progress == if (expanded) 1f else 0f) {
                            source.width.approach(source.inlineSize.width.toFloat(), viewport.width.toFloat(), progress)
                            source.height.approach(source.inlineSize.height.toFloat(), viewport.height.toFloat(), progress)
                        }
                        child
                    }
                val dockPlaceable = children.last().measure(Constraints.fixedWidth(writingWidth.coerceAtLeast(1)))
                val nowTop = contentTop + if (book == null && read.height > 0) read.height + section else 0
                var next = nowTop + now.height + related
                val positions =
                    ui.blocks.mapIndexed { index, block ->
                        val top = next
                        if (!lookahead) state.blockDocumentTops[block.id] = top
                        next += continuity.host
                            .measure(block.id)
                            .inlineSize.height
                            .coerceAtLeast(minimum) + if (index == ui.blocks.lastIndex) gutter else related
                        top
                    }
                val documentHeight = maxOf(next + dockPlaceable.height, contentTop + read.height + readingBottom)
                // Counter-placement keeps focus fixed without resetting or clamping the overview bookmark.
                val height = if (moving) maxOf(documentHeight, state.scroll.value + viewport.height) else documentHeight
                layout(constraints.maxWidth, height) {
                    val documentTop = state.viewportTop - state.scroll.value + if (state.pull > 0f) state.pull else state.pull * 0.35f
                    val passiveAlpha =
                        if (lookahead) {
                            if (focusedId == null) {
                                1f
                            } else {
                                0f
                            }
                        } else {
                            1f - maximumProgress
                        }
                    if (passiveAlpha > 0f && !state.earlierExpanded) {
                        read.placeWithLayer(readingLeft, contentTop) {
                            alpha = passiveAlpha
                            if (book == null) {
                                clearEntryDock(documentTop + contentTop, state.dockTop, gutter.toFloat())
                            } else {
                                clip = false
                            }
                        }
                        now.placeWithLayer(writingLeft, nowTop) {
                            alpha = passiveAlpha
                            clearEntryDock(documentTop + nowTop, state.dockTop, gutter.toFloat())
                        }
                        dockPlaceable.placeWithLayer(writingLeft, next) { alpha = passiveAlpha }
                    }
                    blocks.forEachIndexed { index, child ->
                        val block = ui.blocks[index]
                        val progress =
                            if (lookahead) {
                                if (focusedId == block.id) {
                                    1f
                                } else {
                                    0f
                                }
                            } else {
                                continuity.progress[block.id] ?: 0f
                            }
                        val visible = if (focusedId == block.id || progress > 0f) 1f else 1f - maximumProgress
                        if (visible > 0f && !state.earlierExpanded) {
                            val measure = continuity.host.measure(block.id)
                            val expanded = focusedId == block.id
                            val x =
                                if (lookahead) {
                                    interpolate(writingLeft + gutter, 0, progress)
                                } else {
                                    measure.x
                                        .approach(
                                            (writingLeft + gutter).toFloat(),
                                            0f,
                                            progress,
                                        ).roundToInt()
                                }
                            val y =
                                if (lookahead) {
                                    interpolate(positions[index], state.scroll.value, progress)
                                } else {
                                    measure.y
                                        .approach(
                                            positions[index].toFloat(),
                                            state.scroll.value.toFloat(),
                                            progress,
                                        ).roundToInt()
                                }
                            child.placeWithLayer(
                                x,
                                y,
                                zIndex = progress,
                            ) {
                                alpha = visible
                                clearEntryDock(documentTop + y, state.dockTop, gutter.toFloat(), progress)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun interpolate(
    start: Int,
    end: Int,
    progress: Float,
): Int = (start + (end - start) * progress.coerceIn(0f, 1f)).roundToInt()
