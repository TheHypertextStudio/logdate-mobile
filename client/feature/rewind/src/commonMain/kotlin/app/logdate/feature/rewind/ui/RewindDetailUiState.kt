package app.logdate.feature.rewind.ui

/**
 * UI state for the rewind detail screen.
 *
 * This sealed interface represents the various states the rewind detail view can be in,
 * including loading, success with panels, and error states.
 */
sealed interface RewindDetailUiState {
    /**
     * Loading state while rewind data is being fetched.
     */
    data object Loading : RewindDetailUiState

    /**
     * Success state with loaded rewind panels.
     *
     * @property panels List of panel UI states to display in the story
     */
    data class Success(
        val panels: List<RewindPanelUiState> = listOf(),
    ) : RewindDetailUiState

    /**
     * Error states for the rewind detail screen.
     * Using a sealed interface for type-safe, localizable error handling.
     */
    sealed interface Error : RewindDetailUiState {
        /**
         * Error when no rewind has been selected yet.
         */
        data object RewindNotSelected : Error

        /**
         * Error when the rewind exists but has no content to display.
         */
        data object EmptyContent : Error

        /**
         * Error when the rewind could not be loaded due to a general error.
         */
        data object LoadingFailed : Error
    }
}

/**
 * Extension property to get the total number of panels in a success state.
 */
val RewindDetailUiState.Success.totalPanels
    get() = panels.size
