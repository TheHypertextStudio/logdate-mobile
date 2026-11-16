package app.logdate.feature.core.people.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.knowledge.InferredPeopleRepository
import app.logdate.client.repository.knowledge.PeopleProfileRepository
import app.logdate.shared.model.Event
import app.logdate.shared.model.Person
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/**
 * State for one person's page: who they are, the entries linked to them, and removing them from the
 * list.
 *
 * @param eventsAvailable whether Events is part of this release. Linked events are shown only when
 *   it is.
 */
class PersonDetailViewModel(
    private val peopleProfileRepository: PeopleProfileRepository,
    private val inferredPeopleRepository: InferredPeopleRepository,
    eventsAvailable: Boolean = FeatureFlag.EVENTS.availableForLaunch,
) : ViewModel() {
    data class UiState(
        val person: Person? = null,
        val linkedEntries: List<JournalNote> = emptyList(),
        val linkedEvents: List<Event> = emptyList(),
        val isLoading: Boolean = true,
        val showEvents: Boolean = false,
        val isRemoving: Boolean = false,
        /** True once the person has been removed, which is when the page closes. */
        val isRemoved: Boolean = false,
        val notice: PeopleNotice? = null,
    )

    private val uiState = MutableStateFlow(UiState(showEvents = eventsAvailable))
    private var loadJob: Job? = null

    fun uiState(): StateFlow<UiState> = uiState.asStateFlow()

    fun load(personId: Uuid) {
        loadJob?.cancel()
        loadJob =
            viewModelScope.launch {
                uiState.update { it.copy(isLoading = true) }
                refreshInference()
                observeProfile(personId)
            }
    }

    /**
     * Removes the person from the list and records that their name must not be suggested again.
     *
     * The page stops following the person first, so the removal is not shown as a person that
     * failed to load. If the removal fails the page follows the person again.
     */
    fun remove() {
        val current = uiState.value
        val person = current.person ?: return
        if (current.isRemoving || current.isRemoved) return
        loadJob?.cancel()
        uiState.update { it.copy(isRemoving = true, notice = null) }
        viewModelScope.launch {
            try {
                peopleProfileRepository.removePerson(person.uid)
                uiState.update { it.copy(isRemoving = false, isRemoved = true) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Napier.e("Could not remove a person", error)
                uiState.update { it.copy(isRemoving = false, notice = PeopleNotice.RemovePersonFailed) }
                loadJob = viewModelScope.launch { observeProfile(person.uid) }
            }
        }
    }

    fun dismissNotice() {
        uiState.update { it.copy(notice = null) }
    }

    /**
     * Looks for names in entries and transcripts again so the linked entries are current. A failure
     * is reported on the page and does not stop the person from being shown.
     */
    private suspend fun refreshInference() {
        try {
            inferredPeopleRepository.refresh()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Napier.e("Could not refresh inferred people", error)
            uiState.update { it.copy(notice = PeopleNotice.RefreshFailed) }
        }
    }

    private suspend fun observeProfile(personId: Uuid) {
        try {
            peopleProfileRepository.observeProfile(personId).collect { profile ->
                uiState.update {
                    it.copy(
                        person = profile?.person,
                        linkedEntries = profile?.linkedEntries.orEmpty(),
                        linkedEvents = profile?.linkedEvents.orEmpty(),
                        isLoading = false,
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Napier.e("Could not load a person", error)
            uiState.update {
                it.copy(
                    person = null,
                    linkedEntries = emptyList(),
                    linkedEvents = emptyList(),
                    isLoading = false,
                )
            }
        }
    }
}
