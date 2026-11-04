@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.editor.ui.handwriting

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import app.logdate.client.datastore.EntryStartMode
import app.logdate.feature.editor.ui.EditorInputFocusBookmarks
import app.logdate.feature.editor.ui.LocalEditorInputFocusBookmarks
import app.logdate.feature.editor.ui.editor.CameraBlockUiState
import app.logdate.feature.editor.ui.editor.EditorState
import app.logdate.feature.editor.ui.editor.isEmptyCreationBlock
import app.logdate.feature.editor.ui.layout.EditorContentLayer
import app.logdate.feature.editor.ui.layout.EntryPageRailMaxWidth
import app.logdate.feature.editor.ui.layout.LocalEditorContextControlsVisible
import app.logdate.feature.editor.ui.layout.LocalEditorFocusPresentation
import app.logdate.feature.editor.ui.layout.LocalEditorRecordingActive
import app.logdate.feature.editor.ui.layout.LocalEntryChromeHeight
import app.logdate.feature.editor.ui.layout.LocalEntryContentInsets
import app.logdate.feature.editor.ui.layout.LocalEntryCreationReserve
import app.logdate.feature.editor.ui.layout.LocalEntryHeaderReserve
import app.logdate.feature.editor.ui.layout.editorFocus
import app.logdate.feature.editor.ui.layout.editorFocusPresentation
import app.logdate.ui.creation.LocalEntryCreationProgress
import app.logdate.ui.creation.entryCreationBarProgress
import app.logdate.ui.foldable.FoldableSplitLayout
import app.logdate.ui.foldable.calculateFoldableSplitLayout
import app.logdate.ui.foldable.relativeTo
import app.logdate.ui.foldable.rememberFoldableLayoutInfo
import app.logdate.ui.foldable.rememberWindowOrigin
import app.logdate.ui.platform.rememberSystemReduceMotion
import app.logdate.ui.theme.Spacing
import logdate.client.feature.editor.generated.resources.Res
import logdate.client.feature.editor.generated.resources.entry_page_back_to_writing
import logdate.client.feature.editor.generated.resources.entry_page_return_to_writing
import org.jetbrains.compose.resources.stringResource

internal val LocalEntryCaptureBarHeight = compositionLocalOf { 0.dp }

/** The capture selector is one persistent composition whose position follows the scrolling page. */
@Composable
internal fun EntryPageChrome(
    editor: EditorState,
    overview: EntryPageOverviewState,
    defaultMode: EntryStartMode,
    onReturn: () -> Unit,
    onExit: () -> Unit,
    onCapture: (StoryCaptureMode) -> Unit,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
    focusProgress: Float? = null,
    safeInsets: WindowInsets = WindowInsets.safeDrawing,
    onAddMemory: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val focusBookmarks = remember { EditorInputFocusBookmarks() }
    val returnToWriting = stringResource(Res.string.entry_page_return_to_writing)
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val keyboardBottom = WindowInsets.ime.getBottom(density)
    val safeBottom = safeInsets.getBottom(density)
    val focused = editor.expandedBlockId != null
    val block = editor.blocks.firstOrNull { it.id == editor.expandedBlockId }
    val journal = editor.availableJournals.firstOrNull()
    val creationAllowed =
        !editor.isLoading &&
            !editor.isEditingLocked &&
            !editor.isSaving &&
            !editor.shouldExit &&
            !editor.isRecordingAudio &&
            editor.audioCaptureStartReservations.isEmpty() &&
            !editor.disableEmptyBlockCreation &&
            (block == null || !editor.isReadOnly(block.id))
    val cameraOwnsInput = block is CameraBlockUiState
    val showModes =
        creationAllowed &&
            !cameraOwnsInput &&
            !overview.earlierExpanded &&
            (block?.isEmptyCreationBlock() == true || editor.blocks.isEmpty())
    val showAdd = creationAllowed && !overview.earlierExpanded && !focused && editor.blocks.isNotEmpty()
    val reduceMotion = rememberSystemReduceMotion().value
    val routeProgress = LocalEntryCreationProgress.current
    val routeReady = routeProgress >= 0.999f
    val routeReveal = entryCreationBarProgress(routeProgress)
    val dock = rememberCreationDockProgress(showModes, showAdd, reduceMotion)
    val modeProgress = if (cameraOwnsInput) 0f else dock.modes * routeReveal
    val addProgress = if (cameraOwnsInput) 0f else dock.add * routeReveal
    val dockProgress = if (cameraOwnsInput) 0f else dock.visible * routeReveal
    val defaultCapture =
        when (defaultMode) {
            EntryStartMode.TEXT -> StoryCaptureMode.Write
            EntryStartMode.HANDWRITING -> StoryCaptureMode.Handwrite
            EntryStartMode.CAMERA -> StoryCaptureMode.Camera
            EntryStartMode.AUDIO -> StoryCaptureMode.Voice
            EntryStartMode.GALLERY -> StoryCaptureMode.Photos
        }
    val (origin, trackOrigin) = rememberWindowOrigin()
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceDim)
            .then(trackOrigin)
            .testTag("stories_editor")
            .semantics { isTraversalGroup = true },
    ) {
        val split =
            calculateFoldableSplitLayout(maxWidth, maxHeight, rememberFoldableLayoutInfo().relativeTo(origin), minPaneWidth = 280.dp)
        val safeTop = safeInsets.getTop(density)
        val safeLeft = with(density) { safeInsets.getLeft(density, direction).toDp() }
        val safeRight = with(density) { safeInsets.getRight(density, direction).toDp() }
        val safeWidth = (maxWidth - safeLeft - safeRight).coerceAtLeast(0.dp)
        val safeHeight = maxHeight - with(density) { (safeBottom + safeTop).toDp() }
        val chromeHeight = if (safeHeight < 240.dp) 48.dp else 64.dp
        val anchor = overview.captureBounds
        val focusLeft =
            if (split is FoldableSplitLayout.Vertical) {
                split.rightPane.left.coerceAtLeast(safeLeft)
            } else {
                (
                    safeWidth -
                        minOf(safeWidth, EntryPageRailMaxWidth)
                ) /
                    2 + safeLeft
            }
        val focusWidth =
            if (split is FoldableSplitLayout.Vertical) {
                (split.rightPane.right.coerceAtMost(maxWidth - safeRight) - focusLeft).coerceAtLeast(0.dp)
            } else {
                minOf(safeWidth, EntryPageRailMaxWidth)
            }
        val focusTop = maxHeight - (chromeHeight + Spacing.lg) * dockProgress - with(density) { maxOf(keyboardBottom, safeBottom).toDp() }
        val progress = focusProgress ?: if (focused) 1f else 0f
        val overviewLeft = anchor?.let { with(density) { it.left.toDp() - origin.x } } ?: safeLeft
        val overviewWidth = anchor?.let { with(density) { it.width.toDp() } } ?: safeWidth
        // Long document content can put the creation anchor below the viewport.
        val overviewTop = with(density) { anchor?.top?.toDp()?.minus(origin.y) ?: maxHeight }.coerceAtMost(focusTop)
        val barLeft = lerp(overviewLeft, focusLeft, progress)
        val barWidth = lerp(overviewWidth, focusWidth, progress)
        val writingTop = lerp(overviewTop, focusTop, progress)
        val barTop = lerp(writingTop, maxHeight + 80.dp, overview.browsingProgress)
        val barAlpha = (1f - overview.browsingProgress) * dockProgress
        val pullProgress = (-overview.pull / with(density) { 112.dp.toPx() }).coerceIn(0f, 1f)
        CompositionLocalProvider(
            LocalEditorInputFocusBookmarks provides focusBookmarks,
            LocalEntryChromeHeight provides chromeHeight,
            LocalEntryHeaderReserve provides chromeHeight,
            LocalEntryContentInsets provides safeInsets,
            LocalEntryCreationReserve provides (chromeHeight + Spacing.lg) * modeProgress,
        ) {
            CompositionLocalProvider(
                LocalEditorFocusPresentation provides
                    editorFocusPresentation(editor.editorFocus(), maxWidth, split != FoldableSplitLayout.None, keyboardBottom > 0),
                LocalEditorContextControlsVisible provides false,
                LocalEditorRecordingActive provides editor.isRecordingAudio,
                LocalEntryCaptureBarHeight provides chromeHeight * modeProgress,
            ) {
                EditorContentLayer(
                    Modifier
                        .fillMaxSize()
                        .clipToBounds()
                        .semantics {
                            isTraversalGroup = true
                            traversalIndex = 1f
                        },
                ) { content() }
            }
            Box(
                Modifier
                    .align(
                        Alignment.TopStart,
                    ).windowInsetsPadding(safeInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .onGloballyPositioned { overview.headerBottom = it.positionInWindow().y + it.size.height }
                    .semantics {
                        isTraversalGroup = true
                        traversalIndex = 0f
                    },
            ) {
                if (!overview.earlierExpanded) {
                    Box(Modifier.graphicsLayer { alpha = 1f - overview.browsingProgress }) {
                        if (header != null) {
                            header()
                        } else {
                            EntryFocusPrototypeHeader(
                                block,
                                canvasOnly = true,
                                recording = editor.isRecordingAudio,
                                onReturnToEntry = onReturn,
                                onExit = onExit,
                                journal = journal,
                                journalSelected = true,
                                onJournalSelection = {},
                                onCaptureMode = onCapture,
                                floating = true,
                            )
                        }
                    }
                }
                if (overview.browsingProgress > 0f || overview.earlierExpanded) {
                    FilledTonalButton(
                        onClick = { overview.earlierExpanded = false },
                        enabled = overview.earlierExpanded,
                        modifier =
                            Modifier
                                .padding(16.dp)
                                .graphicsLayer { alpha = overview.browsingProgress }
                                .semantics { contentDescription = returnToWriting },
                        colors =
                            ButtonDefaults.filledTonalButtonColors(
                                containerColor = StoryChrome,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, null)
                        Text(stringResource(Res.string.entry_page_back_to_writing), Modifier.padding(start = 8.dp))
                    }
                }
            }
            EntryCreationDock(
                mode = captureModeForBlock(block) ?: defaultCapture,
                onMode = onCapture,
                onAddMemory = { onAddMemory?.invoke() ?: onCapture(defaultCapture) },
                modeEligible = showModes && routeReady && overview.browsingProgress == 0f,
                addEligible = showAdd && routeReady && overview.browsingProgress == 0f,
                modeProgress = modeProgress,
                addProgress = addProgress,
                visibilityProgress = dockProgress,
                chromeHeight = chromeHeight,
                modifier =
                    Modifier
                        .align(AbsoluteAlignment.TopLeft)
                        .absoluteOffset(barLeft, barTop)
                        .width(barWidth)
                        .height(chromeHeight * dockProgress)
                        .padding(horizontal = Spacing.lg)
                        .onGloballyPositioned { overview.dockTop = if (dockProgress > 0f) it.positionInWindow().y else null }
                        .captureBarPullGestures(overview, enabled = routeReady && !focused && !overview.earlierExpanded)
                        .then(
                            if (showModes && routeReady && modeProgress >= 0.99f && overview.browsingProgress == 0f) {
                                Modifier.captureModeSwipes(
                                    captureModeForBlock(block) ?: defaultCapture,
                                    onCapture,
                                )
                            } else {
                                Modifier
                            },
                        ).graphicsLayer {
                            alpha = barAlpha
                            scaleX = 1f + pullProgress * 0.025f
                            scaleY = 1f + pullProgress * 0.025f
                        }.semantics {
                            isTraversalGroup = true
                            traversalIndex = 2f
                            progressBarRangeInfo = ProgressBarRangeInfo(pullProgress, 0f..1f)
                        },
            )
        }
    }
}
