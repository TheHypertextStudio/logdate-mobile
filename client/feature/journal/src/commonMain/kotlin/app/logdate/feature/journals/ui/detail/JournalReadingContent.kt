@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.journals.ui.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.logdate.ui.foldable.FoldableLayoutInfo
import app.logdate.ui.foldable.FoldablePaneBounds
import app.logdate.ui.foldable.FoldableSplitLayout
import app.logdate.ui.foldable.calculateFoldableSplitLayout
import app.logdate.ui.foldable.relativeTo
import app.logdate.ui.foldable.rememberFoldableLayoutInfo
import app.logdate.ui.foldable.rememberWindowOrigin
import app.logdate.ui.theme.Spacing
import kotlin.uuid.Uuid

@Composable
internal fun JournalReadingContent(
    uiState: JournalDetailUiState.Success,
    mediaEntries: List<EntryDisplayData>,
    hasMedia: Boolean,
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    onNavigateToNoteDetail: (Uuid) -> Unit,
    onRemoveNoteFromJournal: (Uuid) -> Unit,
    onToggleSortOrder: () -> Unit,
    onNavigateToShare: (Uuid) -> Unit,
    onNavigateToSettings: (Uuid) -> Unit,
    onRequestDelete: () -> Unit,
    mergeEnabled: Boolean,
    onNavigateToMerge: (Uuid) -> Unit,
    modifier: Modifier = Modifier,
    contextVisible: Boolean? = null,
) {
    JournalReadingLayout(
        contextVisible = contextVisible,
        modifier = modifier,
        context = {
            JournalDetailBookSummaryPane(
                uiState = uiState,
                onToggleSortOrder = onToggleSortOrder,
                onNavigateToShare = onNavigateToShare,
                onNavigateToSettings = onNavigateToSettings,
                onRequestDelete = onRequestDelete,
                mergeEnabled = mergeEnabled,
                onNavigateToMerge = onNavigateToMerge,
                modifier = Modifier.widthIn(max = 360.dp).fillMaxSize(),
            )
        },
        contents = {
            JournalDetailEntriesPane(
                uiState = uiState,
                mediaEntries = mediaEntries,
                hasMedia = hasMedia,
                selectedTab = selectedTab,
                onSelectTab = onSelectTab,
                onNavigateToNoteDetail = onNavigateToNoteDetail,
                onRemoveNoteFromJournal = onRemoveNoteFromJournal,
                constrainContentWidth = true,
                modifier = Modifier.fillMaxSize(),
            )
        },
    )
}

@Composable
internal fun JournalReadingFrame(
    modifier: Modifier = Modifier,
    content: @Composable (contextVisible: Boolean) -> Unit,
) {
    val foldable = rememberFoldableLayoutInfo()
    val (origin, originModifier) = rememberWindowOrigin()
    val fontScale = LocalDensity.current.fontScale
    val direction = LocalLayoutDirection.current
    BoxWithConstraints(modifier.fillMaxSize().then(originModifier), contentAlignment = AbsoluteAlignment.TopLeft) {
        val split = journalReadingSplit(maxWidth, maxHeight, foldable.relativeTo(origin))
        // A wide header has a 48dp action row with 16dp framing above and below.
        val contextVisible = journalReadingContextFits(maxWidth, maxHeight - 80.dp, fontScale, direction, split)
        val region = if (contextVisible) null else journalReadingSingleRegion(split)
        val contentModifier = region?.let { Modifier.readingRegion(it) } ?: Modifier.fillMaxSize()
        Box(contentModifier) { content(contextVisible) }
    }
}

@Composable
internal fun JournalReadingLayout(
    context: @Composable () -> Unit,
    contents: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    contextVisible: Boolean? = null,
) {
    val foldable = rememberFoldableLayoutInfo()
    val (origin, originModifier) = rememberWindowOrigin()
    val direction = LocalLayoutDirection.current
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier.fillMaxSize().then(originModifier), contentAlignment = AbsoluteAlignment.TopLeft) {
        val split = journalReadingSplit(maxWidth, maxHeight, foldable.relativeTo(origin))
        val leading = (split as? FoldableSplitLayout.Vertical)?.let { if (direction == LayoutDirection.Rtl) it.rightPane else it.leftPane }
        val canSplit = contextVisible != false && journalReadingContextFits(maxWidth, maxHeight, fontScale, direction, split)
        val singleRegion =
            if (canSplit) {
                null
            } else {
                journalReadingSingleRegion(split)
            }
        val contextWidth = leading?.width ?: 304.dp
        val gutter = (split as? FoldableSplitLayout.Vertical)?.hingeBounds?.width ?: Spacing.lg
        val regionModifier = singleRegion?.let { Modifier.readingRegion(it) } ?: Modifier.fillMaxSize()
        Row(regionModifier) {
            if (canSplit) {
                Box(Modifier.width(contextWidth).fillMaxHeight().testTag("journal_reading_context")) { context() }
                Spacer(Modifier.width(gutter))
            }
            Box(Modifier.weight(1f).fillMaxHeight().testTag("journal_reading_contents")) { contents() }
        }
    }
}

private fun Modifier.readingRegion(bounds: FoldablePaneBounds): Modifier =
    absoluteOffset(bounds.left, bounds.top).size(bounds.width, bounds.height)

private fun journalReadingSplit(
    width: Dp,
    height: Dp,
    foldable: FoldableLayoutInfo,
): FoldableSplitLayout {
    val hinge = foldable.hinge?.bounds
    val intersects = hinge != null && hinge.right > 0.dp && hinge.left < width && hinge.bottom > 0.dp && hinge.top < height
    return if (intersects) calculateFoldableSplitLayout(width, height, foldable, 0.dp, 0.dp) else FoldableSplitLayout.None
}

private fun journalReadingContextFits(
    width: Dp,
    height: Dp,
    fontScale: Float,
    direction: LayoutDirection,
    split: FoldableSplitLayout,
): Boolean {
    if (fontScale >= 1.5f || height < 440.dp) return false
    return when (split) {
        is FoldableSplitLayout.Vertical -> {
            val leading = if (direction == LayoutDirection.Rtl) split.rightPane else split.leftPane
            val trailing = if (direction == LayoutDirection.Rtl) split.leftPane else split.rightPane
            leading.width >= 288.dp && trailing.width >= 480.dp
        }

        is FoldableSplitLayout.Horizontal -> {
            false
        }

        FoldableSplitLayout.None -> {
            width >= 816.dp
        }
    }
}

private fun journalReadingSingleRegion(split: FoldableSplitLayout): FoldablePaneBounds? =
    when (split) {
        is FoldableSplitLayout.Vertical -> listOf(split.leftPane, split.rightPane).maxBy { it.width }
        is FoldableSplitLayout.Horizontal -> listOf(split.topPane, split.bottomPane).maxBy { it.height }
        FoldableSplitLayout.None -> null
    }
