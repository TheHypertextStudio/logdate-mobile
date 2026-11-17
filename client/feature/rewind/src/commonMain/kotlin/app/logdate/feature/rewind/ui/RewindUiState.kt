package app.logdate.feature.rewind.ui

import app.logdate.shared.model.Person
import kotlin.time.Instant
import kotlin.uuid.Uuid

sealed interface RewindUiState {
    data class Loaded(
        val ready: Boolean = false,
        val data: RewindData,
        val isLoading: Boolean = false,
    ) : RewindUiState

    data object Loading : RewindUiState
}

data class RewindData(
    val id: Uuid,
    val title: String,
    val label: String,
    val media: List<String>,
    val issueDate: Instant,
    val places: List<String>,
    val people: List<Person> = emptyList(),
)
