@file:Suppress(
    "ktlint:standard:function-naming",
    "ktlint:standard:no-wildcard-imports",
    "ktlint:standard:max-line-length",
)

@file:OptIn(ExperimentalSharedTransitionApi::class)

package app.logdate.feature.onboarding.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.logdate.client.media.MediaObject
import app.logdate.client.permissions.rememberMediaLibraryPermissionState
import app.logdate.ui.adaptive.FoldableBookLayout
import app.logdate.ui.adaptive.FoldableTabletopLayout
import app.logdate.ui.step.StepBusyButton
import app.logdate.ui.step.StepScaffoldDefaults
import app.logdate.ui.theme.LogDateTheme
import app.logdate.ui.theme.Spacing
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import logdate.client.feature.onboarding.generated.resources.*
import logdate.client.feature.onboarding.generated.resources.Res
import logdate.client.feature.onboarding.generated.resources.memory_selection_image_placeholder
import logdate.client.ui.generated.resources.common_back
import logdate.client.ui.generated.resources.common_retry
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import kotlin.time.Duration
import logdate.client.ui.generated.resources.Res as UiRes

const val MEMORY_SELECTION_ROOT_TAG = "onboarding_memory_selection_root"
const val MEMORY_SELECTION_PERMISSION_ACTION_TAG = "onboarding_memory_selection_permission_action"
const val MEMORY_SELECTION_STATUS_ACTION_TAG = "onboarding_memory_selection_status_action"
const val MEMORY_SELECTION_CONTINUE_TAG = "onboarding_memory_selection_continue"

/**
 * UI state for the memory selection screen.
 */
data class MemorySelectionUiState(
    val allMemories: List<MediaObject> = emptyList(),
    val aiCuratedMemories: List<MediaObject> = emptyList(),
    val selectedMemoryIds: Set<String> = emptySet(),
    val isLoading: Boolean = true,
    val hasMoreMemories: Boolean = true,
    val isLoadingMore: Boolean = false,
    val loadFailed: Boolean = false,
    val isImporting: Boolean = false,
    val importFailed: Boolean = false,
)

/**
 * Screen for selecting memories to import during onboarding.
 * Features AI-curated high emotional salience content and infinite scroll.
 */
@OptIn(ExperimentalMaterial3Api::class)
private const val SUGGESTED_MEMORY_LIMIT = 6

@Composable
fun MemorySelectionScreen(
    uiState: MemorySelectionUiState,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    onToggleMemorySelection: (String) -> Unit,
    onLoadMoreMemories: () -> Unit,
    onRefreshMemories: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expandedMemory by remember { mutableStateOf<MediaObject?>(null) }
    val permissionState = rememberMediaLibraryPermissionState()

    LaunchedEffect(permissionState.hasPermission) {
        if (permissionState.hasPermission) {
            onRefreshMemories()
        }
    }

    SharedTransitionLayout {
        val sharedTransitionScope = this

        AnimatedVisibility(
            visible = true,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            val animatedVisibilityScope = this

            Scaffold(
                modifier = modifier,
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(Res.string.select_memories)) },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(
                                    Icons.AutoMirrored.Default.ArrowBack,
                                    contentDescription = stringResource(UiRes.string.common_back),
                                )
                            }
                        },
                    )
                },
                // The list paginates as you scroll, so a Continue button at the end of it is
                // unreachable on any real photo library. Pinning it to the Scaffold keeps the
                // way forward on screen no matter how much media loads.
                bottomBar = {
                    ContinueMemoryImportButton(
                        onContinue = onContinue,
                        selectedCount = uiState.selectedMemoryIds.size,
                        isImporting = uiState.isImporting,
                        importFailed = uiState.importFailed,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface)
                                .navigationBarsPadding()
                                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                    )
                },
            ) { contentPadding ->
                Box {
                    with(sharedTransitionScope) {
                        MemorySelectionAdaptiveContent(
                            uiState = uiState,
                            onToggleMemorySelection = onToggleMemorySelection,
                            onLoadMoreMemories = onLoadMoreMemories,
                            hasMediaPermission = permissionState.hasPermission,
                            onRequestMediaPermission = permissionState.requestPermission,
                            onRetryLoad = onRefreshMemories,
                            onMemoryLongPress = { memory -> expandedMemory = memory },
                            onMemoryLongPressEnd = { expandedMemory = null },
                            expandedMemory = expandedMemory,
                            animatedVisibilityScope = animatedVisibilityScope,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .padding(contentPadding),
                        )
                    }

                    // Expanded memory overlay with shared element transition
                    AnimatedVisibility(
                        visible = expandedMemory != null,
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        val visibilityScope = this
                        expandedMemory?.let { memory ->
                            with(sharedTransitionScope) {
                                ExpandedMemoryOverlay(
                                    memory = memory,
                                    onDismiss = { expandedMemory = null },
                                    animatedVisibilityScope = visibilityScope,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SharedTransitionScope.MemorySelectionAdaptiveContent(
    uiState: MemorySelectionUiState,
    onToggleMemorySelection: (String) -> Unit,
    onLoadMoreMemories: () -> Unit,
    hasMediaPermission: Boolean,
    onRequestMediaPermission: () -> Unit,
    onRetryLoad: () -> Unit,
    onMemoryLongPress: (MediaObject) -> Unit,
    onMemoryLongPressEnd: () -> Unit,
    expandedMemory: MediaObject?,
    animatedVisibilityScope: AnimatedVisibilityScope,
    modifier: Modifier = Modifier,
) {
    FoldableTabletopLayout(
        modifier = modifier,
        minPaneHeight = 260.dp,
        topPane = {
            MemorySelectionTopPane(
                uiState = uiState,
                hasMediaPermission = hasMediaPermission,
                onRequestMediaPermission = onRequestMediaPermission,
                onRetryLoad = onRetryLoad,
                modifier = Modifier.fillMaxSize(),
            )
        },
        bottomPane = {
            MemorySelectionBottomPane(
                uiState = uiState,
                onToggleMemorySelection = onToggleMemorySelection,
                onLoadMoreMemories = onLoadMoreMemories,
                onMemoryLongPress = onMemoryLongPress,
                onMemoryLongPressEnd = onMemoryLongPressEnd,
                expandedMemory = expandedMemory,
                animatedVisibilityScope = animatedVisibilityScope,
                modifier = Modifier.fillMaxSize(),
            )
        },
        standardContent = {
            FoldableBookLayout(
                modifier = Modifier.fillMaxSize(),
                minPaneWidth = 320.dp,
                startPane = {
                    MemorySelectionTopPane(
                        uiState = uiState,
                        hasMediaPermission = hasMediaPermission,
                        onRequestMediaPermission = onRequestMediaPermission,
                        onRetryLoad = onRetryLoad,
                        modifier = Modifier.fillMaxSize(),
                    )
                },
                endPane = {
                    MemorySelectionBottomPane(
                        uiState = uiState,
                        onToggleMemorySelection = onToggleMemorySelection,
                        onLoadMoreMemories = onLoadMoreMemories,
                        onMemoryLongPress = onMemoryLongPress,
                        onMemoryLongPressEnd = onMemoryLongPressEnd,
                        expandedMemory = expandedMemory,
                        animatedVisibilityScope = animatedVisibilityScope,
                        modifier = Modifier.fillMaxSize(),
                    )
                },
                standardContent = {
                    MemorySelectionContent(
                        uiState = uiState,
                        onToggleMemorySelection = onToggleMemorySelection,
                        onLoadMoreMemories = onLoadMoreMemories,
                        hasMediaPermission = hasMediaPermission,
                        onRequestMediaPermission = onRequestMediaPermission,
                        onRetryLoad = onRetryLoad,
                        onMemoryLongPress = onMemoryLongPress,
                        onMemoryLongPressEnd = onMemoryLongPressEnd,
                        expandedMemory = expandedMemory,
                        animatedVisibilityScope = animatedVisibilityScope,
                        modifier = Modifier.fillMaxSize(),
                    )
                },
            )
        },
    )
}

@Composable
private fun MemorySelectionTopPane(
    uiState: MemorySelectionUiState,
    hasMediaPermission: Boolean,
    onRequestMediaPermission: () -> Unit,
    onRetryLoad: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier =
            modifier
                .padding(horizontal = Spacing.lg, vertical = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        item {
            Text(
                text = stringResource(Res.string.select_memories),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium,
            )
        }

        when {
            !hasMediaPermission -> {
                item {
                    MemorySelectionStatusCard(
                        title = stringResource(Res.string.memory_access_needed),
                        body = stringResource(Res.string.memory_access_needed_body),
                        actionLabel = stringResource(Res.string.enable),
                        onAction = onRequestMediaPermission,
                        actionTag = MEMORY_SELECTION_PERMISSION_ACTION_TAG,
                    )
                }
            }

            uiState.loadFailed -> {
                item {
                    MemorySelectionStatusCard(
                        title = stringResource(Res.string.select_memories),
                        body = stringResource(Res.string.memory_load_failed_body),
                        actionLabel = stringResource(UiRes.string.common_retry),
                        onAction = onRetryLoad,
                        actionTag = MEMORY_SELECTION_STATUS_ACTION_TAG,
                    )
                }
            }

            uiState.aiCuratedMemories.isEmpty() && uiState.allMemories.isEmpty() -> {
                item {
                    MemorySelectionStatusCard(
                        title = stringResource(Res.string.select_memories),
                        body = stringResource(Res.string.memory_no_recent_items_body),
                        actionLabel = stringResource(UiRes.string.common_retry),
                        onAction = onRetryLoad,
                        actionTag = MEMORY_SELECTION_STATUS_ACTION_TAG,
                    )
                }
            }

            else -> {
                item {
                    Text(
                        text = stringResource(Res.string.moments_that_might_matter_most),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SharedTransitionScope.MemorySelectionBottomPane(
    uiState: MemorySelectionUiState,
    onToggleMemorySelection: (String) -> Unit,
    onLoadMoreMemories: () -> Unit,
    onMemoryLongPress: (MediaObject) -> Unit,
    onMemoryLongPressEnd: () -> Unit,
    expandedMemory: MediaObject?,
    animatedVisibilityScope: AnimatedVisibilityScope,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier =
            modifier
                .padding(horizontal = Spacing.lg, vertical = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        when {
            uiState.isLoading -> {
                item {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = Spacing.xl),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }

            uiState.aiCuratedMemories.isNotEmpty() || uiState.allMemories.isNotEmpty() -> {
                if (uiState.aiCuratedMemories.isNotEmpty()) {
                    item {
                        AICuratedMemoriesSection(
                            memories = uiState.aiCuratedMemories,
                            selectedMemoryIds = uiState.selectedMemoryIds,
                            onToggleMemorySelection = onToggleMemorySelection,
                            onMemoryLongPress = onMemoryLongPress,
                            onMemoryLongPressEnd = onMemoryLongPressEnd,
                            expandedMemory = expandedMemory,
                            animatedVisibilityScope = animatedVisibilityScope,
                        )
                    }
                }

                if (uiState.allMemories.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(Res.string.all_memories),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    item {
                        AllMemoriesStaggeredGrid(
                            memories = uiState.allMemories,
                            selectedMemoryIds = uiState.selectedMemoryIds,
                            onToggleMemorySelection = onToggleMemorySelection,
                            onMemoryLongPress = onMemoryLongPress,
                            onMemoryLongPressEnd = onMemoryLongPressEnd,
                            isLoadingMore = uiState.isLoadingMore,
                            hasMoreMemories = uiState.hasMoreMemories,
                            onLoadMoreMemories = onLoadMoreMemories,
                            expandedMemory = expandedMemory,
                            animatedVisibilityScope = animatedVisibilityScope,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Content for memory selection with scrollable list.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SharedTransitionScope.MemorySelectionContent(
    uiState: MemorySelectionUiState,
    onToggleMemorySelection: (String) -> Unit,
    onLoadMoreMemories: () -> Unit,
    hasMediaPermission: Boolean,
    onRequestMediaPermission: () -> Unit,
    onRetryLoad: () -> Unit,
    onMemoryLongPress: (MediaObject) -> Unit,
    onMemoryLongPressEnd: () -> Unit,
    expandedMemory: MediaObject?,
    animatedVisibilityScope: AnimatedVisibilityScope,
    modifier: Modifier = Modifier,
) {
    // One scroller for the whole page. The grids used to be nested inside this list inside
    // fixed-height boxes - 200dp for the suggestions, a guessed `rows * 120dp` capped at 600dp
    // for the rest - so the inner grids competed with the outer list for the same drag and
    // anything past the cap could not be reached, including the Continue button. A single
    // staggered grid with full-line headers scrolls once and reaches the end.
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(3),
        modifier = modifier.testTag(MEMORY_SELECTION_ROOT_TAG),
        contentPadding = PaddingValues(Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalItemSpacing = Spacing.sm,
    ) {
        if (uiState.isLoading) {
            item(span = StaggeredGridItemSpan.FullLine) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = Spacing.xl),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            return@LazyVerticalStaggeredGrid
        }

        if (!hasMediaPermission) {
            item(span = StaggeredGridItemSpan.FullLine) {
                MemorySelectionStatusCard(
                    title = stringResource(Res.string.memory_access_needed),
                    body = stringResource(Res.string.memory_access_needed_body),
                    actionLabel = stringResource(Res.string.enable),
                    onAction = onRequestMediaPermission,
                    actionTag = MEMORY_SELECTION_PERMISSION_ACTION_TAG,
                )
            }
            return@LazyVerticalStaggeredGrid
        }

        if (uiState.aiCuratedMemories.isNotEmpty()) {
            item(span = StaggeredGridItemSpan.FullLine) {
                MemorySectionHeader(
                    text = stringResource(Res.string.moments_that_might_matter_most),
                    showBullet = true,
                )
            }
            items(uiState.aiCuratedMemories.take(SUGGESTED_MEMORY_LIMIT), key = { "suggested:${it.uri}" }) { memory ->
                MemoryThumbnail(
                    memory = memory,
                    isSelected = memory.uri in uiState.selectedMemoryIds,
                    onToggleSelection = { onToggleMemorySelection(memory.uri) },
                    onLongPress = { onMemoryLongPress(memory) },
                    onLongPressEnd = onMemoryLongPressEnd,
                    isExpanded = expandedMemory == memory,
                    animatedVisibilityScope = animatedVisibilityScope,
                )
            }
        }

        if (uiState.allMemories.isNotEmpty()) {
            item(span = StaggeredGridItemSpan.FullLine) {
                MemorySectionHeader(text = stringResource(Res.string.all_memories))
            }
            items(uiState.allMemories, key = { "all:${it.uri}" }) { memory ->
                MemoryThumbnail(
                    memory = memory,
                    isSelected = memory.uri in uiState.selectedMemoryIds,
                    onToggleSelection = { onToggleMemorySelection(memory.uri) },
                    onLongPress = { onMemoryLongPress(memory) },
                    onLongPressEnd = onMemoryLongPressEnd,
                    isExpanded = expandedMemory == memory,
                    animatedVisibilityScope = animatedVisibilityScope,
                )
            }

            if (uiState.isLoadingMore) {
                item(span = StaggeredGridItemSpan.FullLine) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(Spacing.lg),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }

            if (uiState.hasMoreMemories && !uiState.isLoadingMore) {
                item(span = StaggeredGridItemSpan.FullLine) {
                    LaunchedEffect(Unit) { onLoadMoreMemories() }
                }
            }
        }
    }
}

/** Section label for a run of thumbnails. */
@Composable
private fun MemorySectionHeader(
    text: String,
    showBullet: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        modifier = Modifier.padding(top = Spacing.md, bottom = Spacing.sm),
    ) {
        if (showBullet) {
            Box(
                modifier =
                    Modifier
                        .size(8.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun MemorySelectionStatusCard(
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
    actionTag: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onAction, modifier = Modifier.testTag(actionTag)) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun ContinueMemoryImportButton(
    onContinue: () -> Unit,
    selectedCount: Int = 0,
    isImporting: Boolean = false,
    importFailed: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.widthIn(max = StepScaffoldDefaults.ContentMaxWidth).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            StepBusyButton(
                text =
                    if (selectedCount > 0) {
                        stringResource(Res.string.continue_with_memories_count, selectedCount)
                    } else {
                        stringResource(Res.string.continue_without_importing_memories)
                    },
                onClick = onContinue,
                busy = isImporting,
                modifier = Modifier.testTag(MEMORY_SELECTION_CONTINUE_TAG),
            )
            OnboardingActionError(
                if (importFailed) stringResource(Res.string.memory_import_failed_body) else null,
            )
        }
    }
}

/**
 * Section displaying AI-curated memories with high emotional salience.
 */
@Composable
private fun SharedTransitionScope.AICuratedMemoriesSection(
    memories: List<MediaObject>,
    selectedMemoryIds: Set<String>,
    onToggleMemorySelection: (String) -> Unit,
    onMemoryLongPress: (MediaObject) -> Unit,
    onMemoryLongPressEnd: () -> Unit,
    expandedMemory: MediaObject?,
    animatedVisibilityScope: AnimatedVisibilityScope,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(8.dp)
                        .background(
                            MaterialTheme.colorScheme.primary,
                            CircleShape,
                        ),
            )
            Text(
                text = stringResource(Res.string.moments_that_might_matter_most),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        // Fixed height staggered grid for AI curated content
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(200.dp),
        ) {
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(3),
                contentPadding = PaddingValues(0.dp),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalItemSpacing = Spacing.sm,
                modifier = Modifier.fillMaxSize(),
            ) {
                items(memories.take(6)) { memory ->
                    MemoryThumbnail(
                        memory = memory,
                        isSelected = memory.uri in selectedMemoryIds,
                        onToggleSelection = { onToggleMemorySelection(memory.uri) },
                        onLongPress = { onMemoryLongPress(memory) },
                        onLongPressEnd = onMemoryLongPressEnd,
                        isExpanded = expandedMemory == memory,
                        animatedVisibilityScope = animatedVisibilityScope,
                    )
                }
            }
        }
    }
}

/**
 * Staggered grid for all memories with infinite scroll.
 */
@Composable
private fun SharedTransitionScope.AllMemoriesStaggeredGrid(
    memories: List<MediaObject>,
    selectedMemoryIds: Set<String>,
    onToggleMemorySelection: (String) -> Unit,
    onMemoryLongPress: (MediaObject) -> Unit,
    onMemoryLongPressEnd: () -> Unit,
    isLoadingMore: Boolean,
    hasMoreMemories: Boolean,
    onLoadMoreMemories: () -> Unit,
    expandedMemory: MediaObject?,
    animatedVisibilityScope: AnimatedVisibilityScope,
    modifier: Modifier = Modifier,
) {
    // Calculate height based on content to avoid infinite dimensions
    val gridHeight =
        remember(memories.size) {
            // Estimate height based on number of items and average item height
            val estimatedRows = (memories.size + 2) / 3 // 3 columns
            (estimatedRows * 120).dp // Average item height
        }

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(gridHeight.coerceAtMost(600.dp)),
        // Max height to prevent infinite dimensions
    ) {
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(3),
            contentPadding = PaddingValues(0.dp),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalItemSpacing = Spacing.sm,
            modifier = Modifier.fillMaxSize(),
        ) {
            items(memories) { memory ->
                MemoryThumbnail(
                    memory = memory,
                    isSelected = memory.uri in selectedMemoryIds,
                    onToggleSelection = { onToggleMemorySelection(memory.uri) },
                    onLongPress = { onMemoryLongPress(memory) },
                    onLongPressEnd = onMemoryLongPressEnd,
                    isExpanded = expandedMemory == memory,
                    animatedVisibilityScope = animatedVisibilityScope,
                )
            }

            // Loading indicator
            if (isLoadingMore) {
                item {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(Spacing.lg),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }

            // Load more trigger
            if (hasMoreMemories && !isLoadingMore) {
                item {
                    LaunchedEffect(Unit) {
                        onLoadMoreMemories()
                    }
                }
            }
        }
    }
}

/**
 * Calculates the native aspect ratio of a media object, constrained to max 3:2.
 */
internal fun MediaObject.getNativeAspectRatio(): Float {
    // Until media metadata is available, use a stable placeholder derived from the URI. A
    // random ratio makes the grid jump between recompositions and makes screenshot output
    // nondeterministic, which is also jarring when a user returns to this screen.
    val stableIndex = (uri.hashCode() and Int.MAX_VALUE)
    return when (this) {
        is MediaObject.Image -> {
            listOf(1.0f, 1.1f, 1.2f, 1.3f, 1.4f, 1.5f)[stableIndex % 6]
        }

        is MediaObject.Video -> {
            listOf(1f, 1.2f, 1.33f, 1.5f)[stableIndex % 4]
        }
    }
}

/**
 * A thumbnail for a memory (photo or video) with selection state and long press support.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SharedTransitionScope.MemoryThumbnail(
    memory: MediaObject,
    isSelected: Boolean,
    onToggleSelection: () -> Unit,
    onLongPress: () -> Unit,
    onLongPressEnd: () -> Unit,
    isExpanded: Boolean,
    animatedVisibilityScope: AnimatedVisibilityScope,
    modifier: Modifier = Modifier,
) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 1.05f else 1f,
        animationSpec = tween(durationMillis = 100),
        label = "thumbnail_scale",
    )

    // Use native aspect ratio of the content, constrained to max 3:2
    val aspectRatio = remember(memory.uri) { memory.getNativeAspectRatio() }

    val thumbnailScope = rememberCoroutineScope()

    Card(
        modifier =
            modifier
                .aspectRatio(aspectRatio)
                .scale(scale)
                .then(
                    if (!isExpanded) {
                        Modifier.sharedElement(
                            rememberSharedContentState("memory-${memory.uri}"),
                            animatedVisibilityScope,
                        )
                    } else {
                        Modifier
                    },
                ).pointerInput(memory.uri) {
                    detectTapGestures(
                        onTap = { onToggleSelection() },
                        onPress = {
                            isPressed = true
                            val longPressJob =
                                thumbnailScope.launch {
                                    delay(500) // 500ms for long press
                                    onLongPress()
                                }
                            tryAwaitRelease()
                            longPressJob.cancel()
                            isPressed = false
                            onLongPressEnd()
                        },
                    )
                },
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        elevation =
            CardDefaults.cardElevation(
                defaultElevation = if (isSelected) 4.dp else 1.dp,
            ),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .then(
                        if (isSelected) {
                            Modifier.border(
                                2.dp,
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.shapes.medium,
                            )
                        } else {
                            Modifier
                        },
                    ),
        ) {
            // This grid asks the user to choose between their own photos, so it has to show
            // them. It previously rendered a literal "IMG" label per tile, which made the
            // choice impossible to make.
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clip(MaterialTheme.shapes.medium),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = memory.uri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )

                if (memory is MediaObject.Video) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = stringResource(Res.string.video),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Selection indicator
            if (isSelected) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(Spacing.sm)
                            .size(24.dp)
                            .background(
                                MaterialTheme.colorScheme.primary,
                                CircleShape,
                            ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = stringResource(Res.string.selected),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/**
 * Expanded memory overlay that appears when long-pressing a memory with shared element transition.
 */
@Composable
private fun SharedTransitionScope.ExpandedMemoryOverlay(
    memory: MediaObject,
    onDismiss: () -> Unit,
    animatedVisibilityScope: AnimatedVisibilityScope,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.7f))
                .clickable { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier =
                Modifier
                    .fillMaxWidth(0.9f)
                    .aspectRatio(1f)
                    .sharedElement(
                        rememberSharedContentState("memory-${memory.uri}"),
                        animatedVisibilityScope,
                    ).clickable { },
            // Prevent dismissing when clicking on the card itself
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            elevation =
                CardDefaults.cardElevation(
                    defaultElevation = 8.dp,
                ),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                when (memory) {
                    is MediaObject.Video -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(Spacing.md),
                        ) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = stringResource(Res.string.video),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(48.dp),
                            )
                            Text(
                                memory.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    is MediaObject.Image -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(Spacing.md),
                        ) {
                            Text(
                                stringResource(Res.string.memory_selection_image_placeholder),
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                memory.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun MemorySelectionScreenPreview() {
    LogDateTheme {
        val sampleMemories =
            (1..20).map { index ->
                if (index % 3 == 0) {
                    MediaObject.Video(
                        uri = "sample$index",
                        size = 2048,
                        name = "VID_$index.mp4",
                        timestamp = Clock.System.now(),
                        duration = kotlin.time.Duration.parse("30s"),
                    )
                } else {
                    MediaObject.Image(
                        uri = "sample$index",
                        size = 1024,
                        name = "IMG_$index.jpg",
                        timestamp = Clock.System.now(),
                    )
                }
            }

        MemorySelectionScreen(
            uiState =
                MemorySelectionUiState(
                    allMemories = sampleMemories,
                    aiCuratedMemories = sampleMemories.take(6),
                    selectedMemoryIds = setOf("sample1", "sample5"),
                    isLoading = false,
                    hasMoreMemories = true,
                    isLoadingMore = false,
                ),
            onBack = {},
            onContinue = {},
            onToggleMemorySelection = {},
            onLoadMoreMemories = {},
            onRefreshMemories = {},
        )
    }
}
