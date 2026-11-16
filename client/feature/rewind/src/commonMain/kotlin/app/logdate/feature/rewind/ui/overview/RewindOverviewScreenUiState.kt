package app.logdate.feature.rewind.ui.overview

import app.logdate.shared.model.ActivityType
import kotlinx.datetime.LocalDate
import kotlin.uuid.Uuid

/**
 * Sealed interface defining all possible UI states for the Rewind overview screen.
 *
 * This state hierarchy manages the different phases of rewind data availability,
 * ensuring the UI can gracefully handle loading, partial data, and complete data
 * scenarios while maintaining the "always show content" design principle.
 *
 * ## State Progression:
 * The typical state flow is: Loading → NotReady → Ready
 * However, direct Loading → Ready transitions are possible when current week
 * rewinds are immediately available.
 *
 * ## Design Philosophy:
 * Each state ensures users always see meaningful content rather than empty states.
 * Even Loading shows placeholder cards to maintain the floating card design.
 *
 * ## Architecture Benefits:
 * - **Type Safety**: Compiler ensures all states are handled
 * - **Predictable UI**: Each state has clear presentation requirements
 * - **Future-Proof**: New states can be added without breaking existing code
 */
sealed interface RewindOverviewScreenUiState {
    /**
     * The UI state when rewind data is being fetched from the backend.
     *
     * This initial state occurs when the screen first loads and no rewind data
     * is available yet. The UI shows placeholder cards to maintain the floating
     * card design even during loading.
     *
     * ## UI Behavior:
     * - Shows "Loading..." placeholder card for current week
     * - Maintains floating card layout structure
     * - No past rewinds are shown until data arrives
     *
     * ## Duration:
     * Typically brief (< 2 seconds) unless network issues occur.
     *
     * @see NotReady When past rewinds load but current week isn't ready
     * @see Ready When both current and past rewinds are available
     */
    data object Loading : RewindOverviewScreenUiState

    /**
     * The UI state when past rewinds are available but the current week's rewind is not ready.
     *
     * This is the most common steady state, as current week rewinds require ongoing
     * processing and are typically not available until the week is complete or
     * significant activity has occurred.
     *
     * ## UI Behavior:
     * - Shows a muted in-progress card for the current week
     * - Displays all available past rewinds as interactive cards
     * - Maintains the same floating card layout and interactions
     *
     * ## User Communication:
     * The placeholder card clearly indicates that the current week's rewind is
     * "Still working on this week's rewind..." to set proper expectations.
     *
     * ## Transition Conditions:
     * Moves to Ready when current week processing completes, typically:
     * - At end of week (Sunday night)
     * - When sufficient activity data exists
     * - When user manually triggers rewind generation
     *
     * @param pastRewinds List of historical rewinds available for viewing
     */
    data class NotReady(
        val pastRewinds: List<RewindHistoryUiState>,
        /**
         * Indicates whether a rewind is currently being generated.
         * When true, the UI can show a loading indicator to inform the user
         * that their rewind is being processed.
         */
        val isGeneratingRewind: Boolean = false,
        val generationFailed: Boolean = false,
    ) : RewindOverviewScreenUiState

    /**
     * The UI state when both current week and past rewind data are fully available.
     *
     * This is the "complete" state where users can interact with all available
     * rewinds, including the most recent one. The current week rewind is fully
     * processed and ready for viewing.
     *
     * ## UI Behavior:
     * - Shows current week rewind as the top card with full styling and interactivity
     * - Displays all past rewinds below in chronological order
     * - All cards are clickable and lead to detailed rewind views
     * - Uses vibrant colors and bold typography for available content
     *
     * ## Content Quality:
     * The mostRecentRewind contains processed data including:
     * - AI-generated descriptive messages
     * - Proper date ranges
     * - Activity summaries and highlights
     * - Personalized titles based on week's content
     *
     * ## User Experience:
     * This state provides the optimal user experience with full content availability
     * and rich interaction possibilities.
     *
     * @param pastRewinds List of historical rewinds available for viewing
     * @param mostRecentRewind The current/most recent week's fully processed rewind
     */
    data class Ready(
        val pastRewinds: List<RewindHistoryUiState>,
        val mostRecentRewind: RewindPreviewUiState,
        val isRefreshing: Boolean = false,
        val refreshFailed: Boolean = false,
    ) : RewindOverviewScreenUiState
}

/**
 * UI state representing a single rewind for display in the floating card interface.
 *
 * This rich data class contains all the information needed to render a rewind card,
 * including content, metadata, and availability status. It serves as the bridge
 * between domain data and UI presentation.
 *
 * ## Content Structure:
 * - **Header**: Label and title for quick identification
 * - **Message**: AI-generated descriptive text about the week's highlights
 * - **Dates**: Clear start/end date range for temporal context
 * - **Activities**: Future expansion for rich content previews
 * - **Status**: Availability flag controlling interactivity
 *
 * ## Usage Contexts:
 * - Current week placeholder (rewindAvailable = false)
 * - Current week completed rewind (rewindAvailable = true)
 * - Historical rewind (always rewindAvailable = true)
 *
 * ## Visual Mapping:
 * Each property directly corresponds to visual elements in the floating card:
 * - `label` → Primary color header text
 * - `title` → Bold/medium weight headline
 * - `message` → Body text description
 * - `start/end` → Date range with arrow separator
 * - `rewindAvailable` → Controls card styling and interactivity
 *
 * @param message AI-generated greeting or description highlighting the week's content
 * @param rewindId Unique identifier for navigation and data retrieval
 * @param label Short descriptive identifier (e.g., "This Week", "Week of Nov 1-7")
 * @param title Main headline describing the rewind theme or highlights
 * @param start Start date of the rewind period (inclusive)
 * @param end End date of the rewind period (inclusive)
 * @param rewindAvailable Whether the rewind is fully processed and viewable
 */
data class RewindPreviewUiState(
    /**
     * A greeting message to display to the user.
     */
    val message: String,
    val rewindId: Uuid,
    val label: String,
    val title: String,
    val start: LocalDate,
    val end: LocalDate,
    /** Whether this week's rewind is ready to be viewed. */
    val rewindAvailable: Boolean = false,
    /** Whether this rewind has been viewed at least once. */
    val isViewed: Boolean = true,
    /** Number of text entries in this rewind. */
    val entryCount: Int = 0,
    /** Number of photos in this rewind. */
    val photoCount: Int = 0,
    /** Number of audio journal entries in this rewind. */
    val audioCount: Int = 0,
    /** Number of people mentioned in this rewind. */
    val peopleCount: Int = 0,
    /** Primary location name, if available. */
    val primaryLocation: String? = null,
    /** A real photo from the week, when one exists — the card's primary background. */
    val heroImageUri: String? = null,
    /** A verbatim quote from the week, shown in place of [message] when present. */
    val highlightedQuote: String? = null,
    /** The week's dominant activity, driving the type badge and the accent-color fallback. */
    val dominantActivity: ActivityType? = null,
    val memories: List<RewindSceneMemory> = emptyList(),
    /** Present when this rewind marks a detected milestone — drives the rarity treatment. */
    val milestone: MilestoneSummaryUiState? = null,
)
