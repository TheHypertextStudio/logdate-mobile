package app.logdate.feature.journals.ui

import app.logdate.shared.model.Journal
import kotlin.uuid.Uuid

/**
 * UI state for the journals overview screen.
 *
 * Layout mode preference is persisted via DataStore so it survives app restarts.
 * Sort state is session-scoped and resets when the user leaves the screen.
 */
data class JournalsOverviewUiState(
    val journals: List<JournalListItemUiState> = emptyList(),
    val layoutMode: JournalLayoutMode = JournalLayoutMode.CAROUSEL,
    val sortOption: JournalSortOption = JournalSortOption.LAST_UPDATED,
    val searchQuery: String = "",
    val isEntrySearchInProgress: Boolean = false,
)

/**
 * How the journal list is visually arranged. Persisted across app restarts.
 */
enum class JournalLayoutMode {
    GRID,
    CAROUSEL,
}

/**
 * Sort orders available in the journal filter bar dropdown.
 */
enum class JournalSortOption {
    LAST_UPDATED,
    CREATED,
    TITLE,
}

/**
 * A single item in the journal list. The list always ends with a [CreateJournalPlaceholder]
 * so the user has a clear affordance for creating a new journal.
 */
sealed interface JournalListItemUiState {
    data class ExistingJournal(
        val data: Journal,
    ) : JournalListItemUiState

    data object CreateJournalPlaceholder : JournalListItemUiState
}

typealias JournalClickCallback = (journalId: Uuid) -> Unit
