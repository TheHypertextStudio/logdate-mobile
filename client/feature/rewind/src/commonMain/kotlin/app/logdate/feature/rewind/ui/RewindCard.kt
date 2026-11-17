@file:Suppress("ktlint:standard:function-naming")

@file:OptIn(ExperimentalUuidApi::class)

package app.logdate.feature.rewind.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.logdate.ui.common.focusableWithRing
import app.logdate.ui.theme.LogDateTheme
import app.logdate.ui.theme.Spacing
import kotlinx.datetime.LocalDate
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * A rewind card component that displays summary information about a weekly rewind.
 *
 * This card serves as the main content container within the floating card list, presenting
 * key information about each rewind in a visually appealing and consistent format.
 *
 * ## Content Structure:
 * - **Label**: Short identifier for the week (e.g., "Week of November 1-7")
 * - **Title**: Descriptive name for the rewind (e.g., "Five Cities in a Week")
 * - **Date Range**: Start and end dates with arrow separator for clarity
 * - **Background**: Uses secondaryContainer color for subtle card differentiation
 *
 * ## Responsive Design:
 * - **Width**: Fills available width up to container constraints
 * - **Padding**: Generous internal spacing (Spacing.lg) for comfortable reading
 * - **Shape**: Uses Material 3 medium rounded corners for modern appearance
 * - **Minimum Width**: Previously 360dp, now responsive to container
 *
 * ## Interaction:
 * - **Clickable Area**: Entire card surface is interactive when ready
 * - **Visual Feedback**: Standard Material 3 click ripple effects
 * - **Disabled State**: Non-interactive when isReady is false
 *
 * ## Future Enhancement Areas:
 * - Photo thumbnails in LazyHorizontalGrid section
 * - Activity indicators or mood visualization
 * - Progress indicators for partially generated rewinds
 *
 * @param id Unique identifier for the rewind
 * @param label Short descriptive label for the time period
 * @param title Main title describing the rewind theme or highlights
 * @param onOpenRewind Callback invoked when the card is tapped
 * @param modifier Modifier for customizing card appearance and behavior
 * @param isReady Whether the rewind is fully processed and viewable
 */
@Composable
internal fun RewindCard(
    id: Uuid,
    label: String,
    title: String,
    onOpenRewind: RewindOpenCallback, // TODO: Make generic id-less callback
    modifier: Modifier = Modifier,
    isReady: Boolean = false,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.secondaryContainer,
                    MaterialTheme.shapes.medium,
                ).padding(Spacing.lg)
                .focusableWithRing()
                .clickable(isReady) { onOpenRewind(id) },
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
        horizontalAlignment = Alignment.Start,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            horizontalAlignment = Alignment.Start,
        ) {
            // Title Block
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
            )
            Row {
                // TODO: Add start and end dates
                // Format to be like "November 4
//                Text(
//                    text = start.format(DateTimeFormatBuilder)
//                )
                // If the month is different, display month.
                // If the year is different from the start, display the year.
                // Otherwise, just display the date.
            }
        }
        LazyHorizontalGrid(rows = GridCells.Adaptive(minSize = 100.dp)) {
        }
    }
}

@Preview
@Composable
private fun RewindCardPreview() {
    LogDateTheme {
        RewindCard(
            id = Uuid.random(),
            label = "Rewind 2024#01",
            title = "Just another week",
            onOpenRewind = { },
        )
    }
}

/**
 * A loading placeholder card that appears while rewind data is being fetched or processed.
 *
 * This placeholder maintains the same visual structure as a real rewind card but uses
 * animated shimmer effects to indicate loading state. It helps maintain layout stability
 * and provides visual feedback about ongoing background processes.
 *
 * ## Animation Design:
 * - **Shimmer Effect**: Subtle alpha animation (0.5f to 0.8f) with reverse repeat
 * - **Duration**: 1 second cycle with keyframe at 500ms for smooth motion
 * - **Color**: Uses secondary color with animated alpha for subtle effect
 * - **Shape**: Matches real card structure with placeholder boxes
 *
 * ## Layout Consistency:
 * - **Dimensions**: Matches real card sizing and spacing
 * - **Colors**: Uses same secondaryContainer background as real cards
 * - **Structure**: Mimics title and subtitle layout with appropriately sized boxes
 *
 * ## UX Benefits:
 * - **Perceived Performance**: Makes loading feel faster than empty states
 * - **Layout Stability**: Prevents content jumping when real data loads
 * - **Visual Continuity**: Maintains card grid structure during transitions
 *
 * @param modifier Modifier for customizing the placeholder card appearance
 */
@Composable
fun RewindCardPlaceholder(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "Loading")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0.8f,
        animationSpec =
            infiniteRepeatable(
                animation =
                    keyframes {
                        durationMillis = 1000
                        0.7f at 500
                    },
                repeatMode = RepeatMode.Reverse,
            ),
        label = "alpha",
    )
    Column(
        modifier =
            modifier
                .background(
                    MaterialTheme.colorScheme.secondaryContainer,
                    MaterialTheme.shapes.medium,
                ).padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
        horizontalAlignment = Alignment.Start,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            horizontalAlignment = Alignment.Start,
        ) {
            // Title Block
            Box(
                modifier =
                    Modifier
                        .background(
                            MaterialTheme.colorScheme.secondary.copy(alpha = alpha),
                            MaterialTheme.shapes.small,
                        ).padding(Spacing.sm)
                        .height(20.dp)
                        .width(108.dp),
            )
            Box(
                modifier =
                    Modifier
                        .background(
                            MaterialTheme.colorScheme.secondary.copy(alpha = alpha),
                            MaterialTheme.shapes.small,
                        ).padding(Spacing.sm)
                        .height(32.dp)
                        .width(240.dp),
            )
        }
        LazyHorizontalGrid(rows = GridCells.Adaptive(minSize = 100.dp)) {
        }
    }
}

@Preview
@Composable
private fun RewindCardPlaceholderPreview() {
    LogDateTheme {
        RewindCardPlaceholder()
    }
}

/**
 * Callback function type for handling rewind card interactions.
 *
 * This callback is invoked when a user taps on an available rewind card to open
 * the detailed rewind view. The callback receives the unique identifier of the
 * selected rewind for navigation and data fetching purposes.
 *
 * ## Usage Pattern:
 * ```kotlin
 * RewindScreenContent(
 *     state = uiState,
 *     onOpenRewind = { rewindId ->
 *         // Navigate to detailed rewind view
 *         navController.navigate("rewind/$rewindId")
 *     }
 * )
 * ```
 *
 * ## Navigation Contract:
 * The receiving implementation should handle:
 * - Navigation to the detailed rewind view
 * - Loading of specific rewind data
 * - Error handling for invalid or missing rewind IDs
 *
 * @param rewindId The unique identifier of the rewind to open
 */
@OptIn(ExperimentalUuidApi::class)
typealias RewindOpenCallback = (rewindId: Uuid) -> Unit
