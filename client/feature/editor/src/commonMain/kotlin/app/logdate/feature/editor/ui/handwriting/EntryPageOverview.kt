@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class, androidx.compose.animation.ExperimentalAnimationApi::class)
@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.editor.ui.handwriting

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import app.logdate.client.datastore.EntryStartMode
import app.logdate.client.repository.journals.JournalNote
import app.logdate.feature.editor.ui.LocalAnimatedVisibilityScope
import app.logdate.feature.editor.ui.LocalBlockFocusProgress
import app.logdate.feature.editor.ui.LocalEditorBlockContinuity
import app.logdate.feature.editor.ui.LocalSharedTransitionScope
import app.logdate.feature.editor.ui.RetainedEditorDocument
import app.logdate.feature.editor.ui.blocks.MemoryBlockContent
import app.logdate.feature.editor.ui.blocks.MemoryBlockSurface
import app.logdate.feature.editor.ui.content.EarlierEntryContext
import app.logdate.feature.editor.ui.content.blockType
import app.logdate.feature.editor.ui.editor.BlockType
import app.logdate.feature.editor.ui.editor.HandwritingBlockUiState
import app.logdate.feature.editor.ui.editor.TextBlockUiState
import app.logdate.feature.editor.ui.editor.delegate.PendingAudioResolver
import app.logdate.feature.editor.ui.editorBlockFocusElement
import app.logdate.feature.editor.ui.layout.EditorBlockCornerRadius
import app.logdate.feature.editor.ui.layout.EntryPageRailMaxWidth
import app.logdate.feature.editor.ui.layout.LocalEntryChromeHeight
import app.logdate.feature.editor.ui.layout.LocalEntryContentInsets
import app.logdate.feature.editor.ui.layout.clipEntryContentToSafeWindow
import app.logdate.feature.editor.ui.state.BlocksUiState
import app.logdate.feature.editor.ui.text.TextBlockContent
import app.logdate.ui.foldable.FoldableSplitLayout
import app.logdate.ui.foldable.calculateFoldableSplitLayout
import app.logdate.ui.foldable.relativeTo
import app.logdate.ui.foldable.rememberFoldableLayoutInfo
import app.logdate.ui.foldable.rememberWindowOrigin
import app.logdate.ui.platform.rememberLogDateHaptics
import app.logdate.ui.theme.Spacing
import kotlinx.coroutines.launch
import logdate.client.feature.editor.generated.resources.Res
import logdate.client.feature.editor.generated.resources.entry_page_browse_earlier_today
import logdate.client.feature.editor.generated.resources.entry_page_earlier_today
import logdate.client.feature.editor.generated.resources.entry_page_now
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt
import kotlin.uuid.Uuid

internal data class EntryPageScrollAnchor(
    val blockId: Uuid,
    val top: Float,
    val width: Int,
)

/** One scroll owner, including both halves of book posture; draft content stays in the editor. */
class EntryPageOverviewState internal constructor(
    initiallyExpanded: Boolean,
    initialScroll: Int = 0,
    initialHistoryScroll: Int = 0,
) {
    val scroll = ScrollState(initialScroll)
    val historyScroll = ScrollState(initialHistoryScroll)
    var earlierExpanded by mutableStateOf(initiallyExpanded)
    internal var browsingProgress by mutableFloatStateOf(if (initiallyExpanded) 1f else 0f)
    var pull by mutableFloatStateOf(0f)
    var captureBounds by mutableStateOf<Rect?>(null)
    internal var captureDrag: (Float) -> Unit = {}
    internal var releaseCaptureDrag: () -> Unit = {}
    internal var viewportWidth = 0
    internal var viewportTop by mutableFloatStateOf(0f)
    internal var focusedAnchor: EntryPageScrollAnchor? = null
    internal var focusScrollBookmark: Int? = null
    internal val blockPositions = mutableMapOf<Uuid, Float>()
    internal val blockDocumentTops = mutableStateMapOf<Uuid, Int>()
    internal var draftRevealPlacement by mutableStateOf<DraftRevealPlacement?>(null)
    internal var draftRevealHeading by mutableStateOf<DraftRevealPlacement?>(null)
    internal var headerBottom by mutableStateOf<Float?>(null)
    internal var dockTop by mutableStateOf<Float?>(null)
}

@Composable
internal fun EntryPageOverview(
    uiState: BlocksUiState,
    notes: List<JournalNote>,
    state: EntryPageOverviewState,
    defaultMode: EntryStartMode = EntryStartMode.TEXT,
    onAudioResolverReady: (Uuid, PendingAudioResolver) -> Unit = { _, _ -> },
) {
    val continuity = LocalEditorBlockContinuity.current
    val safeInsets = LocalEntryContentInsets.current.asPaddingValues()
    val contentTop = 88.dp + safeInsets.calculateTopPadding()
    val scroll = state.scroll
    val earlierExpanded = state.earlierExpanded
    val browsing = rememberEarlierDayTransition(state)
    val headingBounds =
        Modifier.padding(horizontal = 16.dp).onGloballyPositioned { coordinates ->
            uiState.draftReveal?.let { request ->
                state.draftRevealHeading =
                    DraftRevealPlacement(request, coordinates.positionInWindow().y, coordinates.size.height)
            }
        }
    var pull by state::pull
    val threshold = with(LocalDensity.current) { 112.dp.toPx() }
    val armed = kotlin.math.abs(pull) >= threshold
    val currentUiState by rememberUpdatedState(uiState)
    val currentDefault by rememberUpdatedState(defaultMode)
    val haptics = rememberLogDateHaptics()
    LaunchedEffect(armed) { if (armed) haptics.detentTick() }

    fun add(type: BlockType) {
        val id = Uuid.random()
        state.focusedAnchor = EntryPageScrollAnchor(id, state.captureBounds?.top ?: 88f, state.viewportWidth)
        currentUiState.onCreateBlock(type, id)
        currentUiState.onBlockFocused(id)
    }

    fun releasePull(): Boolean {
        val released = pull
        pull = 0f
        if (released >= threshold && notes.isNotEmpty()) state.earlierExpanded = true
        if (released <= -threshold && currentUiState.allowBlockCreation) add(currentDefault.blockType())
        return released != 0f
    }
    SideEffect {
        state.captureDrag = drag@{ delta ->
            if (currentUiState.expandedBlockId != null) return@drag
            currentUiState.onCancelDraftReveal()
            var remaining = delta
            if (pull != 0f) {
                val previous = pull
                val next = (pull + remaining * 0.6f).coerceIn(-threshold * 1.4f, threshold * 1.4f)
                pull = if (previous > 0f) next.coerceAtLeast(0f) else next.coerceAtMost(0f)
                remaining -= (pull - previous) / 0.6f
            }
            val consumed = scroll.dispatchRawDelta(-remaining)
            if (consumed != 0f) state.focusedAnchor = null
            remaining += consumed
            if ((!scroll.canScrollBackward && remaining > 0f) || (!scroll.canScrollForward && remaining < 0f)) {
                pull = (pull + remaining * 0.6f).coerceIn(-threshold * 1.4f, threshold * 1.4f)
            }
        }
        state.releaseCaptureDrag = { releasePull() }
    }
    val connection =
        remember(scroll, threshold, notes.isNotEmpty()) {
            object : NestedScrollConnection {
                override fun onPreScroll(
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (source == NestedScrollSource.UserInput) currentUiState.onCancelDraftReveal()
                    if (currentUiState.expandedBlockId != null ||
                        state.earlierExpanded ||
                        source != NestedScrollSource.UserInput ||
                        pull == 0f
                    ) {
                        return Offset.Zero
                    }
                    if (pull * available.y >= 0f) {
                        pull = (pull + available.y * 0.6f).coerceIn(-threshold * 1.4f, threshold * 1.4f)
                        return available
                    }
                    val old = pull
                    pull = if (pull > 0f) (pull + available.y * 0.6f).coerceAtLeast(0f) else (pull + available.y * 0.6f).coerceAtMost(0f)
                    return Offset(0f, (pull - old) / 0.6f)
                }

                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (currentUiState.expandedBlockId != null ||
                        state.earlierExpanded ||
                        source != NestedScrollSource.UserInput
                    ) {
                        return Offset.Zero
                    }
                    if (consumed.y != 0f) state.focusedAnchor = null
                    if ((!scroll.canScrollBackward && available.y > 0f) || (!scroll.canScrollForward && available.y < 0f)) {
                        pull = (pull + available.y * 0.6f).coerceIn(-threshold * 1.4f, threshold * 1.4f)
                        return Offset(0f, available.y)
                    }
                    return Offset.Zero
                }

                override suspend fun onPreFling(available: Velocity): Velocity = if (releasePull()) available else Velocity.Zero
            }
        }
    val fold = rememberFoldableLayoutInfo()
    val (origin, trackOrigin) = rememberWindowOrigin()
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .then(
                if (continuity ==
                    null
                ) {
                    Modifier.windowInsetsPadding(LocalEntryContentInsets.current.only(WindowInsetsSides.Horizontal))
                } else {
                    Modifier
                },
            ).then(trackOrigin)
            .onGloballyPositioned { state.viewportTop = it.positionInWindow().y }
            .clipToBounds()
            .nestedScroll(connection)
            .testTag("study_edge_pull")
            .semantics {
                if (uiState.expandedBlockId == null) {
                    progressBarRangeInfo = ProgressBarRangeInfo((kotlin.math.abs(pull) / threshold).coerceIn(0f, 1f), 0f..1f)
                }
            },
    ) {
        val viewportWidthPx = constraints.maxWidth
        val viewportHeightPx = constraints.maxHeight
        revealLoadedDraft(
            uiState,
            state,
            viewportHeightPx,
            with(LocalDensity.current) { contentTop.roundToPx() },
            with(LocalDensity.current) {
                (LocalEntryChromeHeight.current + Spacing.lg + safeInsets.calculateBottomPadding()).roundToPx()
            },
            continuity?.let { it.focusedId != null || it.progress.values.any { progress -> progress > 0f } } == true,
        )
        state.viewportWidth = viewportWidthPx
        val book =
            calculateFoldableSplitLayout(maxWidth, maxHeight, fold.relativeTo(origin), minPaneWidth = 280.dp)
                as? FoldableSplitLayout.Vertical
        val reading: @Composable () -> Unit = {
            if (notes.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp).clipEntryContentToSafeWindow(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(Res.string.entry_page_earlier_today),
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        FilledTonalIconButton(onClick = { state.earlierExpanded = true }) {
                            Icon(Icons.Outlined.History, stringResource(Res.string.entry_page_browse_earlier_today))
                        }
                    }
                    Box(Modifier.padding(horizontal = Spacing.lg)) {
                        EarlierEntryContext(notes, showHeading = false)
                    }
                }
            }
        }
        val writing: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(Res.string.entry_page_now),
                    headingBounds,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(Modifier.padding(horizontal = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    EntryBlocks(uiState, state, viewportWidthPx, viewportHeightPx, onAudioResolverReady)
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(
                            LocalEntryChromeHeight.current + Spacing.lg + safeInsets.calculateBottomPadding(),
                        ).testTag("entry_page_creation")
                        .onGloballyPositioned {
                            state.captureBounds = Rect(it.positionInWindow(), Size(it.size.width.toFloat(), it.size.height.toFloat()))
                        },
                )
            }
        }
        if (pull > 0f && !earlierExpanded && notes.isNotEmpty()) {
            EarlierDayHeading(
                Modifier.padding(top = contentTop, start = 16.dp).graphicsLayer { alpha = (pull / threshold).coerceIn(0f, 1f) },
            )
        }
        if (continuity != null) {
            RetainedEditorDocument(
                uiState,
                continuity,
                state,
                IntSize(viewportWidthPx, viewportHeightPx),
                with(LocalDensity.current) { contentTop.roundToPx() },
                book,
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationY = if (pull > 0f) pull else pull * 0.35f }
                    .verticalScroll(scroll, enabled = uiState.expandedBlockId == null && !earlierExpanded)
                    .testTag("entry_page_scroll")
                    .then(if (earlierExpanded) Modifier.clearAndSetSemantics { hideFromAccessibility() } else Modifier),
                reading = reading,
                heading = {
                    Text(
                        stringResource(Res.string.entry_page_now),
                        headingBounds,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                dock = {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(LocalEntryChromeHeight.current + Spacing.lg + safeInsets.calculateBottomPadding())
                            .testTag("entry_page_creation")
                            .onGloballyPositioned {
                                state.captureBounds = Rect(it.positionInWindow(), Size(it.size.width.toFloat(), it.size.height.toFloat()))
                            },
                    )
                },
            )
            if (earlierExpanded) EarlierDayBrowser(notes, state.historyScroll)
        } else {
            browsing.AnimatedContent(
                transitionSpec = {
                    (slideInVertically(tween(420)) { if (targetState) -it / 3 else it / 3 } + fadeIn(tween(180))) togetherWith
                        (slideOutVertically(tween(420)) { if (targetState) it / 3 else -it / 3 } + fadeOut(tween(220)))
                },
            ) { browsing ->
                if (browsing) {
                    EarlierDayBrowser(notes, state.historyScroll)
                } else {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer { translationY = if (pull > 0f) pull else pull * 0.35f }
                            .background(MaterialTheme.colorScheme.surfaceDim)
                            .verticalScroll(scroll)
                            .testTag("entry_page_scroll"),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        if (book != null) {
                            Row(Modifier.fillMaxWidth().padding(top = contentTop)) {
                                Box(
                                    Modifier.width(book.leftPane.width).padding(
                                        end = Spacing.lg,
                                        bottom = Spacing.lg + safeInsets.calculateBottomPadding(),
                                    ),
                                ) { reading() }
                                Box(Modifier.width(book.hingeBounds.width))
                                Box(Modifier.width(book.rightPane.width).padding(start = 16.dp)) { writing() }
                            }
                        } else {
                            Column(
                                Modifier.widthIn(max = EntryPageRailMaxWidth).fillMaxWidth().padding(top = contentTop),
                                verticalArrangement = Arrangement.spacedBy(24.dp),
                            ) {
                                reading()
                                writing()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryBlocks(
    uiState: BlocksUiState,
    state: EntryPageOverviewState,
    viewportWidth: Int,
    viewportHeight: Int,
    onAudioResolverReady: (Uuid, PendingAudioResolver) -> Unit,
) {
    val shared = LocalSharedTransitionScope.current
    val visibility = LocalAnimatedVisibilityScope.current
    val scope = rememberCoroutineScope()
    uiState.blocks.forEach { block ->
        key(block.id) {
            val focus = {
                state.focusedAnchor = EntryPageScrollAnchor(block.id, state.blockPositions[block.id] ?: 88f, viewportWidth)
                uiState.onBlockFocused(block.id)
            }
            val bounds =
                if (block is HandwritingBlockUiState || shared == null || visibility == null) {
                    Modifier
                } else {
                    with(shared) {
                        Modifier.editorBlockFocusElement(
                            shared,
                            visibility,
                            block.id,
                            EditorBlockCornerRadius,
                            LocalBlockFocusProgress.current ?: 0f,
                        )
                    }
                }
            Box(
                Modifier.fillMaxWidth().onGloballyPositioned { coordinates ->
                    state.recordDraftReveal(uiState.draftReveal, block.id, coordinates)
                    val top = coordinates.positionInWindow().y
                    state.blockPositions[block.id] = top
                    val anchor = state.focusedAnchor
                    if (uiState.draftReveal == null && anchor?.blockId == block.id && anchor.width != viewportWidth) {
                        val desiredTop = anchor.top.coerceAtMost(state.viewportTop + viewportHeight / 2f)
                        state.focusedAnchor = anchor.copy(top = desiredTop, width = viewportWidth)
                        scope.launch { state.scroll.scrollTo(state.scroll.value + (top - desiredTop).roundToInt()) }
                    }
                },
            ) {
                MemoryBlockSurface(
                    block,
                    false,
                    focus,
                    uiState.onUpdateBlock,
                    { uiState.onDeleteBlock(block.id) },
                    modifier = bounds,
                    morphSurface = true,
                    customPresentation = block is HandwritingBlockUiState,
                    focusTransitionProgress = uiState.blockPresentations[block.id]?.focusTransitionProgress ?: 0f,
                ) {
                    if (block is TextBlockUiState) {
                        TextBlockContent(
                            block,
                            isExpanded = false,
                            onTextChanged = { uiState.onUpdateBlock(block.copy(content = it)) },
                            onFocused = focus,
                        )
                    } else {
                        MemoryBlockContent(
                            block,
                            requestTextFocus = false,
                            isSelected = false,
                            editRequest = 0,
                            onSelect = focus,
                            onUpdate = uiState.onUpdateBlock,
                            onRemove = { uiState.onDeleteBlock(block.id) },
                            onAudioResolverReady = onAudioResolverReady,
                            onPersistAudioRecordingTarget = uiState.onPersistAudioRecordingTarget,
                            presentation = uiState.blockPresentations[block.id],
                        )
                    }
                }
            }
        }
    }
}
