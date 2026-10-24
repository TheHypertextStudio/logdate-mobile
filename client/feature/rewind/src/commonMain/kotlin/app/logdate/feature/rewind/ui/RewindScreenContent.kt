@file:Suppress("ktlint:standard:function-naming", "ktlint:standard:no-wildcard-imports")

package app.logdate.feature.rewind.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.logdate.feature.rewind.ui.components.CollapsingRewindAppBar
import app.logdate.feature.rewind.ui.components.RewindCoverCard
import app.logdate.feature.rewind.ui.detail.rewindAccentColor
import app.logdate.feature.rewind.ui.overview.RewindHistoryUiState
import app.logdate.feature.rewind.ui.overview.RewindOverviewPeriod
import app.logdate.feature.rewind.ui.overview.RewindOverviewScreenUiState
import app.logdate.feature.rewind.ui.overview.RewindPreviewUiState
import app.logdate.ui.adaptive.FoldableBookLayout
import app.logdate.ui.common.AspectRatios
import app.logdate.ui.common.formatting.LocalToday
import app.logdate.ui.content.ImageScrimOverlay
import app.logdate.ui.platform.PlatformIcons
import app.logdate.ui.platform.rememberSystemReduceMotion
import app.logdate.ui.theme.Spacing
import app.logdate.ui.workspace.LocalWorkspaceEnabled
import app.logdate.ui.workspace.PanelContainment
import app.logdate.ui.workspace.WorkspacePanel
import app.logdate.ui.workspace.WorkspaceSectionSwitch
import app.logdate.util.getLocaleFirstDayOfWeek
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import logdate.client.feature.rewind.generated.resources.*
import logdate.client.feature.rewind.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The main rewind screen content that displays a vertically scrollable list of floating cards.
 *
 * Each card represents a weekly rewind with snap-to-center scrolling behavior. The design ensures
 * there's always at least one card visible, including a placeholder for the current week when no
 * rewind is ready yet.
 *
 * ## UX Design Principles:
 * - **Always Visible Content**: Never shows empty state - current week placeholder ensures floating
 *   card design is always showcased
 * - **Instagram Story-like Navigation**: Vertical scrolling with cards snapping to screen center
 * - **Visual Hierarchy**: Ready cards are vibrant and clickable, pending cards are muted
 * - **Progressive Disclosure**: Visual affordances guide users to scroll for more content
 *
 * ## Interaction Model:
 * - **Snap Scrolling**: Cards automatically center when user stops scrolling
 * - **State-Aware Clicks**: Only available rewinds are interactive
 * - **Next Card Indicator**: Floating button shows when more cards are available below
 * - **End Surprise**: Celebration message appears when user reaches the bottom
 *
 * @param state The current UI state containing rewind data
 * @param onOpenRewind Callback invoked when user taps on an available rewind card
 * @param modifier Modifier for customizing the appearance and behavior
 */
@OptIn(ExperimentalUuidApi::class, ExperimentalMaterial3Api::class)
@Composable
fun RewindScreenContent(
    state: RewindOverviewScreenUiState,
    onOpenRewind: RewindOpenCallback,
    onRetryRewind: (() -> Unit)? = null,
    onGenerateAnnualRewind: ((year: Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
    selectedPeriod: RewindOverviewPeriod = RewindOverviewPeriod.PreviousWeek,
    onSelectPeriod: ((RewindOverviewPeriod) -> Unit)? = null,
    onRefreshCurrentWeek: (() -> Unit)? = null,
    firstDayOfWeek: DayOfWeek = getLocaleFirstDayOfWeek(),
    onCreateMemory: (() -> Unit)? = null,
) {
    // Combine current and past rewinds into a single list, always showing at least current week placeholder
    val generationFailedMessage = stringResource(Res.string.rewind_generation_failed)
    val generatingMessage = stringResource(Res.string.rewind_current_week_generating)
    val isThisWeek = selectedPeriod == RewindOverviewPeriod.ThisWeek
    val emptyWeekMessage = stringResource(if (isThisWeek) Res.string.rewind_this_week_empty else Res.string.rewind_current_week_empty)
    val currentWeekLabel = stringResource(if (isThisWeek) Res.string.rewind_this_week_so_far else Res.string.rewind_last_week)
    val currentWeekTitle = stringResource(if (isThisWeek) Res.string.rewind_current_week_title else Res.string.rewind_last_week_title)
    val loadingMessage = stringResource(Res.string.rewind_loading_message)
    val loadingTitle = stringResource(Res.string.rewind_loading_title)
    val emptyPeriod = state is RewindOverviewScreenUiState.NotReady && !state.isGeneratingRewind && !state.generationFailed
    val preparationAction =
        when {
            emptyPeriod && !isThisWeek && onSelectPeriod != null -> ({ onSelectPeriod(RewindOverviewPeriod.ThisWeek) })
            emptyPeriod && isThisWeek && onCreateMemory != null -> onCreateMemory
            else -> onRetryRewind
        }
    val preparationLabel =
        stringResource(
            when {
                emptyPeriod && !isThisWeek && onSelectPeriod != null -> Res.string.rewind_this_week_so_far
                emptyPeriod && isThisWeek && onCreateMemory != null -> Res.string.rewind_add_memory
                state is RewindOverviewScreenUiState.NotReady && state.generationFailed -> Res.string.rewind_retry_generation
                else -> Res.string.rewind_create_selected_week
            },
        )
    val today =
        if (LocalInspectionMode.current) {
            LocalToday.current
        } else {
            Clock.System
                .now()
                .toLocalDateTime(TimeZone.currentSystemDefault())
                .date
        }
    val rewindItems =
        remember(
            state,
            generationFailedMessage,
            generatingMessage,
            emptyWeekMessage,
            currentWeekLabel,
            currentWeekTitle,
            loadingMessage,
            loadingTitle,
            selectedPeriod,
            firstDayOfWeek,
            today,
        ) {
            when (state) {
                is RewindOverviewScreenUiState.Ready -> {
                    listOf(
                        state.mostRecentRewind.copy(
                            label = currentWeekLabel,
                            title = if (isThisWeek) currentWeekTitle else state.mostRecentRewind.title,
                        ),
                    ) + state.pastRewinds.map { it.toPreview() }
                }

                is RewindOverviewScreenUiState.NotReady -> {
                    val (weekStart, weekEnd) = selectedWeekBounds(selectedPeriod, firstDayOfWeek, today)
                    val currentWeekPlaceholder =
                        RewindPreviewUiState(
                            message =
                                if (state.isGeneratingRewind) {
                                    generatingMessage
                                } else if (state.generationFailed) {
                                    generationFailedMessage
                                } else {
                                    emptyWeekMessage
                                },
                            rewindId = Uuid.random(),
                            label = currentWeekLabel,
                            title = currentWeekTitle,
                            start = weekStart,
                            end = weekEnd,
                            rewindAvailable = false,
                        )

                    listOf(currentWeekPlaceholder) + state.pastRewinds.map { it.toPreview() }
                }

                RewindOverviewScreenUiState.Loading -> {
                    val (weekStart, weekEnd) = selectedWeekBounds(selectedPeriod, firstDayOfWeek, today)
                    listOf(
                        RewindPreviewUiState(
                            message = loadingMessage,
                            rewindId = Uuid.random(),
                            label = currentWeekLabel,
                            title = loadingTitle,
                            start = weekStart,
                            end = weekEnd,
                            rewindAvailable = false,
                        ),
                    )
                }
            }
        }

    // Create a scroll behavior for the collapsing app bar
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    // Check if a completed calendar year has enough weekly rewinds to offer a Year in Review.
    val pastRewinds =
        when (state) {
            is RewindOverviewScreenUiState.Ready -> state.pastRewinds
            is RewindOverviewScreenUiState.NotReady -> state.pastRewinds
            RewindOverviewScreenUiState.Loading -> emptyList()
        }
    val annualRewindYear =
        remember(pastRewinds) {
            if (pastRewinds.size < 4) return@remember null
            val currentYear =
                Clock.System
                    .now()
                    .toLocalDateTime(TimeZone.currentSystemDefault())
                    .year
            // Offer the previous completed year if we have rewinds in it and no annual
            // rewind already exists (annual rewinds have a label like "2025").
            val previousYear = currentYear - 1
            val hasWeeklyInPreviousYear = pastRewinds.any { it.startDate.year == previousYear }
            val annualAlreadyExists = pastRewinds.any { it.label == "$previousYear" }
            if (hasWeeklyInPreviousYear && !annualAlreadyExists) previousYear else null
        }

    if (LocalWorkspaceEnabled.current) {
        val reduceMotion by rememberSystemReduceMotion()
        WorkspacePanel(modifier, containment = PanelContainment.Collection) {
            Column(Modifier.fillMaxSize()) {
                PeriodControls(state, selectedPeriod, onSelectPeriod, onRefreshCurrentWeek)
                RewindCardScrollPane(
                    rewinds = uniqueRewindPreviews(rewindItems),
                    onOpenRewind = onOpenRewind,
                    annualRewindYear = annualRewindYear,
                    onGenerateAnnualRewind = onGenerateAnnualRewind,
                    animateCards = !reduceMotion,
                    modifier = Modifier.weight(1f),
                )
                if (state is RewindOverviewScreenUiState.NotReady && !state.isGeneratingRewind && preparationAction != null) {
                    Button(onClick = preparationAction, modifier = Modifier.padding(Spacing.lg)) {
                        Text(preparationLabel)
                    }
                }
            }
        }
        return
    }
    Scaffold(
        topBar = {
            Column {
                CollapsingRewindAppBar(title = stringResource(Res.string.rewind_title), scrollBehavior = scrollBehavior)
                PeriodControls(state, selectedPeriod, onSelectPeriod, onRefreshCurrentWeek)
            }
        },
        containerColor = Color.Transparent,
        modifier = modifier,
        bottomBar = {
            if (state is RewindOverviewScreenUiState.NotReady && !state.isGeneratingRewind && preparationAction != null) {
                Button(
                    onClick = preparationAction,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
                ) {
                    Text(preparationLabel)
                }
            }
        },
    ) { paddingValues ->
        // Connect the scroll behavior to the list
        FloatingRewindCardList(
            rewinds = rewindItems,
            onOpenRewind = onOpenRewind,
            scrollBehavior = scrollBehavior,
            annualRewindYear = annualRewindYear,
            onGenerateAnnualRewind = onGenerateAnnualRewind,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
        )
    }
}

@Composable
private fun PeriodControls(
    state: RewindOverviewScreenUiState,
    selectedPeriod: RewindOverviewPeriod,
    onSelectPeriod: ((RewindOverviewPeriod) -> Unit)?,
    onRefreshCurrentWeek: (() -> Unit)?,
) {
    if (onSelectPeriod == null) return
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.md)) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            WorkspaceSectionSwitch(
                labels = listOf(stringResource(Res.string.rewind_last_week), stringResource(Res.string.rewind_this_week_so_far)),
                selectedIndex = selectedPeriod.ordinal,
                onSelect = { onSelectPeriod(RewindOverviewPeriod.entries[it]) },
            )
            if (selectedPeriod == RewindOverviewPeriod.ThisWeek &&
                state is RewindOverviewScreenUiState.Ready &&
                onRefreshCurrentWeek != null
            ) {
                TextButton(onClick = onRefreshCurrentWeek, enabled = !state.isRefreshing) {
                    Text(stringResource(if (state.isRefreshing) Res.string.rewind_updating else Res.string.rewind_update))
                }
            }
        }
        if (state is RewindOverviewScreenUiState.Ready && state.refreshFailed) {
            Text(
                stringResource(Res.string.rewind_update_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * A simple vertically scrollable list of floating rewind cards with snap-to-center behavior.
 *
 * This composable implements a clean scrolling experience with Material 3 design principles:
 * - Cards maintain consistent size and appearance
 * - Snap behavior centers the focused card on scroll
 * - Visual depth created through gradient background
 * - Simple layout with consistent spacing and dimensions
 *
 * ## Layout Structure:
 * - **Background Gradient**: Creates visual depth from surface to surfaceContainer
 * - **Snap Scrolling**: Uses Compose's built-in snap fling behavior
 * - **Fixed Dimensions**: 360dp width cards with 3:2 aspect ratio
 * - **Simple Spacing**: 32dp between cards, 200dp top padding for centering
 * - **Card Constraints**: 24dp edge padding, fixed max width
 *
 * @param rewinds List of rewind data to display as cards
 * @param onOpenRewind Callback for card interactions
 * @param modifier Modifier for customizing the list container
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FloatingRewindCardList(
    rewinds: List<RewindPreviewUiState>,
    onOpenRewind: RewindOpenCallback,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    annualRewindYear: Int? = null,
    onGenerateAnnualRewind: ((year: Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val featuredRewinds = rewinds.take(1).ifEmpty { rewinds }
    val historyRewinds = rewinds.drop(1)

    FoldableBookLayout(
        modifier = modifier,
        minPaneWidth = 360.dp,
        startPane = {
            RewindCardScrollPane(
                rewinds = featuredRewinds,
                onOpenRewind = onOpenRewind,
                scrollBehavior = scrollBehavior,
                showEndOfListSurprise = false,
                showNextCardIndicator = false,
                animateCards = false,
            )
        },
        endPane = {
            RewindHistoryBookPane(
                rewinds = historyRewinds,
                onOpenRewind = onOpenRewind,
                annualRewindYear = annualRewindYear,
                onGenerateAnnualRewind = onGenerateAnnualRewind,
            )
        },
        standardContent = {
            RewindCardScrollPane(
                rewinds = rewinds,
                onOpenRewind = onOpenRewind,
                scrollBehavior = scrollBehavior,
                annualRewindYear = annualRewindYear,
                onGenerateAnnualRewind = onGenerateAnnualRewind,
            )
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RewindCardScrollPane(
    rewinds: List<RewindPreviewUiState>,
    onOpenRewind: RewindOpenCallback,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    annualRewindYear: Int? = null,
    onGenerateAnnualRewind: ((year: Int) -> Unit)? = null,
    showEndOfListSurprise: Boolean = true,
    showNextCardIndicator: Boolean = true,
    animateCards: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val snapFlingBehavior = rememberSnapFlingBehavior(lazyListState = listState)

    val workspace = LocalWorkspaceEnabled.current
    val animateEntrance = animateCards && !LocalInspectionMode.current
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val coverLayout = rewindCoverLayout(maxWidth, maxHeight)
        val cardWidth = if (workspace) coverLayout.width else 360.dp
        val cardHeight = if (workspace) coverLayout.height else cardWidth * AspectRatios.RATIO_3_2
        val framing = if (workspace) coverLayout.framing else 200.dp
        val indicatorBottom = if (workspace) (framing - Spacing.xxxl).coerceAtLeast(Spacing.lg) else Spacing.lg
        if (!workspace) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surfaceContainer)),
                ),
            )
        }

        // Track which card indices have already played their entrance animation,
        // so scrolling away and back doesn't replay it. This intentionally does
        // not survive configuration changes — replaying on rotate is fine since
        // the list's identity changes.
        val animatedIndices = remember { mutableStateSetOf<Int>() }

        LazyColumn(
            state = listState,
            flingBehavior = snapFlingBehavior,
            contentPadding =
                PaddingValues(
                    top = framing,
                    bottom = if (workspace) framing else 300.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(if (workspace) Spacing.xxxl else Spacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(rewinds) { index, rewind ->
                val alreadyAnimated = index in animatedIndices
                var visible by remember { mutableStateOf(!animateEntrance || alreadyAnimated) }
                LaunchedEffect(animateEntrance) {
                    if (animateEntrance && !alreadyAnimated) {
                        delay((index * 80L).coerceAtMost(480L))
                        visible = true
                        animatedIndices += index
                    }
                }
                AnimatedVisibility(
                    visible = visible,
                    // Fade only — no vertical slide. With spacedBy gaps between cards, a
                    // slide-up made each card look like it was materializing out of the
                    // empty space beneath it as it scrolled into view.
                    enter = fadeIn(animationSpec = tween(300)),
                ) {
                    FloatingRewindCard(
                        rewind = rewind,
                        onOpenRewind = onOpenRewind,
                        listState = listState,
                        index = index,
                        cardHeight = cardHeight,
                        modifier =
                            if (workspace) {
                                Modifier.widthIn(max = cardWidth).fillMaxWidth()
                            } else {
                                Modifier.fillMaxWidth().padding(horizontal = 24.dp).widthIn(max = cardWidth)
                            },
                    )
                }
            }

            // Year in Review trigger — shown when a completed year has enough data
            if (annualRewindYear != null && onGenerateAnnualRewind != null) {
                item {
                    Card(
                        onClick = { onGenerateAnnualRewind(annualRewindYear) },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp)
                                .widthIn(max = 360.dp)
                                .height(120.dp),
                        colors =
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            ),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(Spacing.lg),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text =
                                    stringResource(
                                        Res.string.annual_rewind_generate,
                                        annualRewindYear,
                                    ),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }

            // Surprise ending item
            if (showEndOfListSurprise) {
                item {
                    EndOfListSurprise(
                        listState = listState,
                        itemCount = rewinds.size,
                        cardHeight = cardHeight,
                        modifier =
                            if (workspace) {
                                Modifier.widthIn(max = cardWidth).fillMaxWidth()
                            } else {
                                Modifier.fillMaxWidth().padding(horizontal = 24.dp).widthIn(max = cardWidth)
                            },
                    )
                }
            }
        }

        // Next card indicator
        if (showNextCardIndicator) {
            NextCardIndicator(
                listState = listState,
                itemCount = rewinds.size,
                modifier =
                    Modifier
                        .align(if (workspace) Alignment.BottomCenter else Alignment.BottomEnd)
                        .padding(start = Spacing.lg, end = Spacing.lg, bottom = indicatorBottom, top = Spacing.lg),
            )
        }
    }
}

@Composable
private fun RewindHistoryBookPane(
    rewinds: List<RewindPreviewUiState>,
    onOpenRewind: RewindOpenCallback,
    annualRewindYear: Int? = null,
    onGenerateAnnualRewind: ((year: Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val cardWidth = 360.dp
    val cardHeight = cardWidth * AspectRatios.RATIO_3_2

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors =
                                listOf(
                                    MaterialTheme.colorScheme.surface,
                                    MaterialTheme.colorScheme.surfaceContainer,
                                ),
                        ),
                    ),
        )

        LazyColumn(
            state = listState,
            contentPadding =
                PaddingValues(
                    horizontal = 24.dp,
                    vertical = 32.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Text(
                    text = stringResource(Res.string.past_rewind),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .widthIn(max = cardWidth),
                )
            }

            if (rewinds.isEmpty()) {
                item {
                    Text(
                        text = stringResource(Res.string.past_rewinds_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .widthIn(max = cardWidth),
                    )
                }
            } else {
                itemsIndexed(rewinds) { index, rewind ->
                    FloatingRewindCard(
                        rewind = rewind,
                        onOpenRewind = onOpenRewind,
                        listState = listState,
                        index = index,
                        cardHeight = cardHeight,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .widthIn(max = cardWidth),
                    )
                }
            }

            if (annualRewindYear != null && onGenerateAnnualRewind != null) {
                item {
                    Card(
                        onClick = { onGenerateAnnualRewind(annualRewindYear) },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .widthIn(max = cardWidth)
                                .height(120.dp),
                        colors =
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            ),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(Spacing.lg),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text =
                                    stringResource(
                                        Res.string.annual_rewind_generate,
                                        annualRewindYear,
                                    ),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A simple floating rewind card with consistent size and appearance.
 *
 * ## Visual States:
 * - **Available Rewind**: Primary container surface, bold typography, fully interactive
 * - **Pending Rewind**: Muted surfaceVariant color, medium weight typography, non-interactive
 *
 * ## Content Layout:
 * - **Label**: Week identifier with primary/outline color based on availability
 * - **Title**: Main rewind name with bold/medium weight based on state
 * - **Message**: Descriptive text about the rewind contents
 * - **Date Range**: Start and end dates with arrow separator
 *
 * ## Interaction:
 * - Only available rewinds respond to clicks
 * - Visual feedback through Material 3 card ripple effects
 * - Maintains accessibility for screen readers and keyboard navigation
 *
 * @param rewind The rewind data to display
 * @param onOpenRewind Callback invoked when an available card is tapped
 * @param listState The scroll state (unused but kept for API consistency)
 * @param index The card's position in the list (unused but kept for API consistency)
 * @param cardHeight Fixed height for consistent card sizing
 * @param modifier Modifier for customizing the card appearance
 */
@Composable
fun FloatingRewindCard(
    rewind: RewindPreviewUiState,
    onOpenRewind: RewindOpenCallback,
    listState: LazyListState,
    index: Int,
    cardHeight: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    if (LocalWorkspaceEnabled.current) {
        val reduceMotion by rememberSystemReduceMotion()
        val elevation by remember(listState, index, reduceMotion) {
            derivedStateOf {
                val layout = listState.layoutInfo
                val item = layout.visibleItemsInfo.firstOrNull { it.index == index }
                val viewportCenter = (layout.viewportStartOffset + layout.viewportEndOffset) / 2f
                val viewportHalfHeight = (layout.viewportEndOffset - layout.viewportStartOffset) / 2f
                val distance = item?.let { (it.offset + it.size / 2f - viewportCenter) / viewportHalfHeight.coerceAtLeast(1f) } ?: 1f
                rewindCoverElevation(distance, reduceMotion)
            }
        }
        RewindCoverContent(rewind, onOpenRewind, cardHeight, elevation, modifier)
    } else {
        RewindCoverContent(rewind, onOpenRewind, cardHeight, 0.dp, modifier)
    }
}

@Composable
private fun RewindCoverContent(
    rewind: RewindPreviewUiState,
    onOpenRewind: RewindOpenCallback,
    cardHeight: androidx.compose.ui.unit.Dp,
    elevation: androidx.compose.ui.unit.Dp,
    modifier: Modifier,
) {
    val isMilestone = rewind.milestone != null
    val shape = MaterialTheme.shapes.extraLarge

    Card(
        onClick = {
            if (rewind.rewindAvailable) {
                onOpenRewind(rewind.rewindId)
            }
        },
        modifier =
            modifier
                .then(if (LocalWorkspaceEnabled.current) Modifier.heightIn(min = cardHeight) else Modifier.height(cardHeight))
                .then(
                    // Milestone weeks get a visibly different frame — the "special pull"
                    // of the set, not just a small badge bolted onto an ordinary card.
                    if (isMilestone) {
                        Modifier.border(
                            width = 2.dp,
                            brush = Brush.linearGradient(MilestoneBorderColors),
                            shape = shape,
                        )
                    } else {
                        Modifier
                    },
                ),
        shape = shape,
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (rewind.rewindAvailable) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                contentColor =
                    if (rewind.rewindAvailable) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            ),
        elevation =
            CardDefaults.cardElevation(
                defaultElevation = if (isMilestone) elevation + (if (LocalWorkspaceEnabled.current) 2.dp else 8.dp) else elevation,
            ),
    ) {
        Box(modifier = if (LocalWorkspaceEnabled.current) Modifier.fillMaxWidth().heightIn(min = cardHeight) else Modifier.fillMaxSize()) {
            // Every available card is built from that week's real content — a real
            // photo first, falling back to a per-rewind accent gradient (never flat
            // decorative color as the primary treatment). Pending cards stay neutral.
            if (rewind.rewindAvailable) {
                if (rewind.heroImageUri != null) {
                    AsyncImage(
                        model = rewind.heroImageUri,
                        contentDescription = null,
                        modifier = Modifier.matchParentSize(),
                        contentScale = ContentScale.Crop,
                    )
                    ImageScrimOverlay(
                        topAlpha = if (LocalWorkspaceEnabled.current) 0.5f else 0.15f,
                        bottomAlpha = 0.8f,
                        modifier = Modifier.matchParentSize(),
                    )
                } else {
                    val accent = rewindAccentColor(rewind.dominantActivity, rewind.rewindId.hashCode())
                    Box(
                        modifier =
                            Modifier
                                .matchParentSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors =
                                            listOf(
                                                accent,
                                                accent.copy(
                                                    red = accent.red * 0.7f,
                                                    green = accent.green * 0.7f,
                                                    blue = accent.blue * 0.7f,
                                                ),
                                            ),
                                    ),
                                ),
                    )
                    if (LocalWorkspaceEnabled.current) {
                        ImageScrimOverlay(topAlpha = 0.55f, bottomAlpha = 0.25f, modifier = Modifier.matchParentSize())
                    }
                }
            }
            RewindCoverCard(
                rewind = rewind,
                modifier =
                    Modifier
                        .then(
                            if (LocalWorkspaceEnabled.current) {
                                Modifier.fillMaxWidth().heightIn(
                                    min = cardHeight,
                                )
                            } else {
                                Modifier.fillMaxSize()
                            },
                        ).padding(Spacing.lg),
            )
        }
    }
}

private val MilestoneBorderColors =
    listOf(
        Color(0xFFE8C547), // honey gold
        Color(0xFFF5E6A8), // pale shine
        Color(0xFFE8C547),
    )

/**
 * A floating indicator that appears when more cards are available below the current view.
 *
 * This affordance helps users discover that they can scroll to see more rewinds. The indicator:
 * - Appears only when there are more cards below the currently centered one
 * - Uses a subtle circular design with Material 3 primaryContainer color
 * - Positioned in the bottom-right corner with appropriate padding
 * - Automatically disappears when user reaches the last card
 *
 * ## UX Rationale:
 * The indicator solves the "mystery meat navigation" problem by clearly signaling that more
 * content is available. This is especially important in snap-scrolling interfaces where
 * users might not realize there's more content below.
 *
 * ## Visual Design:
 * - **Size**: 48dp circular surface (meets minimum touch target)
 * - **Icon**: Simple downward arrow indicating scroll direction
 * - **Transparency**: 70% opacity to avoid overwhelming the main content
 * - **Color**: primaryContainer for subtle brand consistency
 *
 * @param listState The scroll state for determining current position
 * @param itemCount Total number of items to check if more are available
 * @param modifier Modifier for positioning and styling the indicator
 */
@Composable
fun NextCardIndicator(
    listState: LazyListState,
    itemCount: Int,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val reduceMotion by rememberSystemReduceMotion()
    val currentIndex by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo

            if (visibleItems.isEmpty()) return@derivedStateOf 0

            val viewportHeight = layoutInfo.viewportSize.height
            if (viewportHeight == 0) return@derivedStateOf 0

            val center = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2f

            // Find item closest to center with optimized calculation
            var closestIndex = 0
            var minDistance = Float.MAX_VALUE

            for (item in visibleItems) {
                val itemCenter = item.offset + item.size / 2f
                val distance = abs(itemCenter - center)
                if (distance < minDistance) {
                    minDistance = distance
                    closestIndex = item.index
                }
            }

            closestIndex
        }
    }

    val hasNext = currentIndex < itemCount - 1

    if (hasNext) {
        Surface(
            onClick = {
                scope.launch {
                    if (reduceMotion) listState.scrollToItem(currentIndex + 1) else listState.animateScrollToItem(currentIndex + 1)
                }
            },
            modifier =
                modifier
                    .size(48.dp)
                    .alpha(0.7f),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Box(
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = PlatformIcons.expandMore(),
                    contentDescription = stringResource(Res.string.more_rewinds_below),
                )
            }
        }
    }
}

/**
 * A delightful surprise message that appears when users reach the end of their rewind history.
 *
 * This hidden gem celebrates users who take the time to scroll through all their past rewinds.
 * The element uses careful z-index positioning to appear behind the floating cards, creating
 * a layered reveal effect as users scroll past the final card.
 *
 * ## UX Philosophy:
 * Small moments of delight like this help create emotional connection with the app. Users who
 * engage deeply with their content (scrolling through all rewinds) deserve recognition and
 * positive reinforcement for their engagement.
 *
 * ## Technical Implementation:
 * - **Z-Index**: Positioned at -1 to appear behind cards
 * - **Visibility**: Only appears when last item is visible
 * - **Content**: Celebration emoji + encouraging message
 * - **Timing**: Perfectly aligned with the last card's scroll position
 *
 * ## Content Strategy:
 * - **Emoji**: Universal celebration symbol (🎉)
 * - **Primary Message**: Achievement-focused ("Congrats, you've reached the end!")
 * - **Secondary Message**: Contextual explanation of the accomplishment
 * - **Tone**: Warm and encouraging, matching app personality
 *
 * @param listState The scroll state for detecting when to show the surprise
 * @param itemCount Total number of cards to determine if we're at the end
 * @param cardHeight Height matching the cards for proper visual alignment
 * @param modifier Modifier for positioning and sizing the surprise content
 */
@Composable
fun EndOfListSurprise(
    listState: LazyListState,
    itemCount: Int,
    cardHeight: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val isNearEnd by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()
            lastVisibleItem?.index == itemCount // This is the surprise item index
        }
    }

    Box(
        modifier =
            modifier
                .height(cardHeight)
                .alpha(if (isNearEnd) 1f else 0f)
                .zIndex(-1f),
        // Behind the cards
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                text = stringResource(Res.string.text_2),
                style = MaterialTheme.typography.displayLarge,
            )
            Text(
                text = stringResource(Res.string.congrats_youve_reached_the_end),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = stringResource(Res.string.youve_reviewed_all_your_weekly_rewinds),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Fallback content for the rare case when no rewind data is available.
 *
 * **Note**: This composable is currently unused in the new floating card design, as the screen
 * now always shows at least a placeholder card for the current week. However, it's retained
 * for potential future use cases or as a safety fallback.
 *
 * ## Original UX Intent:
 * - Encourages user engagement when no content is available
 * - Uses friendly, conversational tone ("Go live a little, will ya?")
 * - Provides clear call-to-action to create content
 *
 * ## Design Principles:
 * - **Encouraging tone**: Motivates rather than frustrates users
 * - **Clear action**: "Share something" button provides immediate next step
 * - **Centered layout**: Focuses attention on the message and action
 *
 * @param onTouchGrass Callback for the action button to start content creation
 * @param modifier Modifier for customizing the empty state container
 */
@Composable
fun EmptyRewindContent(
    onTouchGrass: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.xl),
        ) {
            Text(
                text = stringResource(Res.string.go_live_a_little_will_ya),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(Res.string.rewind_empty_description),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onTouchGrass) {
                Text(stringResource(Res.string.share_something))
            }
        }
    }
}

/**
 * Computes the start and end of the previous complete week, matching [GetWeekRewindUseCase].
 */
private fun selectedWeekBounds(
    period: RewindOverviewPeriod,
    weekStartDay: DayOfWeek,
    today: LocalDate,
): Pair<LocalDate, LocalDate> {
    val daysFromWeekStart = (today.dayOfWeek.ordinal - weekStartDay.ordinal + 7) % 7
    val startOfThisWeek = today.minus(daysFromWeekStart, DateTimeUnit.DAY)
    val startOfLastWeek = startOfThisWeek.minus(7, DateTimeUnit.DAY)
    val endOfLastWeek = startOfThisWeek.minus(1, DateTimeUnit.DAY)
    return if (period == RewindOverviewPeriod.ThisWeek) startOfThisWeek to today else startOfLastWeek to endOfLastWeek
}

@OptIn(ExperimentalUuidApi::class, ExperimentalMaterial3Api::class)
@Composable
@Preview
private fun RewindScreenPreview() {
    val lastId = Uuid.random()
    RewindScreenContent(
        state =
            RewindOverviewScreenUiState.NotReady(
                pastRewinds =
                    listOf(
                        RewindHistoryUiState(
                            uid = Uuid.random(),
                            title = "Caught up on sleep",
                            label = "Week 42",
                            startDate = LocalDate(2024, 10, 14),
                            endDate = LocalDate(2024, 10, 20),
                            message = "A quiet week, but yours nonetheless",
                        ),
                        RewindHistoryUiState(
                            uid = Uuid.random(),
                            title = "New coffee spot on 5th",
                            label = "Week 41",
                            startDate = LocalDate(2024, 10, 7),
                            endDate = LocalDate(2024, 10, 13),
                            message = "Moments worth remembering",
                        ),
                        RewindHistoryUiState(
                            uid = Uuid.random(),
                            title = "The one with the deadline",
                            label = "Week 40",
                            startDate = LocalDate(2024, 9, 30),
                            endDate = LocalDate(2024, 10, 6),
                            message = "Your mind's been busy",
                        ),
                        RewindHistoryUiState(
                            uid = lastId,
                            title = "Errands and leftovers",
                            label = "Week 39",
                            startDate = LocalDate(2024, 9, 23),
                            endDate = LocalDate(2024, 9, 29),
                            message = "Quality over quantity",
                        ),
                    ),
            ),
        onOpenRewind = {},
    )
}

@OptIn(ExperimentalUuidApi::class, ExperimentalMaterial3Api::class)
@Composable
@Preview
private fun FloatingCardListPreview() {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val rewinds =
        listOf(
            RewindPreviewUiState(
                message = "Weaving this week's story...",
                rewindId = Uuid.random(),
                label = "This Week",
                title = "This week's story",
                start = LocalDate(2024, 11, 18),
                end = LocalDate(2024, 11, 24),
                rewindAvailable = false,
            ),
            RewindPreviewUiState(
                message = "Your week, captured",
                rewindId = Uuid.random(),
                label = "2024#44",
                title = "Rainy days and good reads",
                start = LocalDate(2024, 11, 1),
                end = LocalDate(2024, 11, 7),
                rewindAvailable = true,
                isViewed = false,
            ),
            RewindPreviewUiState(
                message = "A quiet week, but yours nonetheless",
                rewindId = Uuid.random(),
                label = "2024#43",
                title = "Just another week",
                start = LocalDate(2024, 10, 25),
                end = LocalDate(2024, 10, 31),
                rewindAvailable = true,
            ),
            RewindPreviewUiState(
                message = "Connections that matter",
                rewindId = Uuid.random(),
                label = "2024#42",
                title = "Dinner with the crew",
                start = LocalDate(2024, 10, 18),
                end = LocalDate(2024, 10, 24),
                rewindAvailable = true,
            ),
        )

    FloatingRewindCardList(
        rewinds = rewinds,
        onOpenRewind = {},
        scrollBehavior = scrollBehavior,
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * Flattens a [RewindHistoryUiState] into the [RewindPreviewUiState] form that
 * [FloatingRewindCard] renders. Historical rewinds are always available to view
 * (they wouldn't be in the list otherwise), so [RewindPreviewUiState.rewindAvailable]
 * is hardcoded to `true`.
 */
private fun RewindHistoryUiState.toPreview(): RewindPreviewUiState =
    RewindPreviewUiState(
        message = message,
        rewindId = uid,
        label = label,
        title = title,
        start = startDate,
        end = endDate,
        rewindAvailable = true,
        isViewed = isViewed,
        entryCount = entryCount,
        photoCount = photoCount,
        audioCount = audioCount,
        peopleCount = peopleCount,
        primaryLocation = primaryLocation,
        heroImageUri = heroImageUri,
        highlightedQuote = highlightedQuote,
        dominantActivity = dominantActivity,
        milestone = milestone,
    )
